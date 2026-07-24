package top.yukonga.mishka.data.backup

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import top.yukonga.mishka.data.database.ImportedDao
import top.yukonga.mishka.data.database.ImportedEntity
import top.yukonga.mishka.data.database.PendingDao
import top.yukonga.mishka.data.database.PendingEntity
import top.yukonga.mishka.data.database.SelectionDao
import top.yukonga.mishka.data.database.SelectionEntity
import top.yukonga.mishka.data.repository.ProfileProcessor
import top.yukonga.mishka.domain.model.ProfileType
import top.yukonga.mishka.platform.BootStartManager
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.service.ProfileFileOps
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupException(message: String) : Exception(message)

@Serializable
data class BackupProfile(
    val uuid: String,
    val name: String,
    val type: String,
    val source: String,
    val userAgent: String = "",
    val ageSecretKey: String = "",
    val interval: Long = 0,
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    val expire: Long = 0,
    val createdAt: Long,
)

@Serializable
data class BackupSelection(
    val uuid: String,
    val proxy: String,
    val selected: String,
)

@Serializable
data class BackupSnapshot(
    val version: Int,
    val createdAt: Long,
    val imported: List<BackupProfile> = emptyList(),
    val pending: List<BackupProfile> = emptyList(),
    val selections: List<BackupSelection> = emptyList(),
    val stringPrefs: Map<String, String> = emptyMap(),
    val stringSetPrefs: Map<String, List<String>> = emptyMap(),
    val bootStartEnabled: Boolean = false,
)

/**
 * WebDAV 备份/恢复的打包与落地。
 *
 * zip 布局：`backup.json`（版本 + 三表 JSON + prefs）+ files/imported、files/pending
 * 两棵目录树 + `files/override.user.json`。DB 走 JSON 导出重放而非拷贝
 * db 文件——绕开 WAL 一致性问题，且跨 schema 版本可由字段默认值兜底。
 *
 * 备份与恢复都持 [ProfileProcessor.withProcessLock] 进程级锁：备份期间不能有导入管线
 * commit 改写 imported/，恢复期间不能有任何管线在跑。恢复要求代理已停止（调用方校验），
 * 完成后必须重启进程——OverrideJsonStore / Repository Flow 等内存热状态不随磁盘恢复而刷新。
 */
class BackupManager(
    private val context: Context,
    private val storage: PlatformStorage,
    private val importedDao: ImportedDao,
    private val pendingDao: PendingDao,
    private val selectionDao: SelectionDao,
    private val bootStartManager: BootStartManager,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val mihomoDir: File
        get() = File(context.filesDir, "mihomo")

    suspend fun createBackup(): ByteArray = ProfileProcessor.withProcessLock {
        withContext(Dispatchers.IO) {
            val snapshot = BackupSnapshot(
                version = BACKUP_VERSION,
                createdAt = System.currentTimeMillis(),
                imported = importedDao.queryAll().map { it.toBackup() },
                pending = pendingDao.queryAll().map { it.toBackup() },
                selections = selectionDao.queryAll().map { BackupSelection(it.uuid, it.proxy, it.selected) },
                stringPrefs = storage.dumpAll()
                    .filterKeys { it !in EXCLUDED_PREF_KEYS }
                    .mapNotNull { (k, v) -> (v as? String)?.let { k to it } }
                    .toMap(),
                stringSetPrefs = storage.dumpAll()
                    .filterKeys { it !in EXCLUDED_PREF_KEYS }
                    .mapNotNull { (k, v) ->
                        @Suppress("UNCHECKED_CAST")
                        (v as? Set<String>)?.let { k to it.toList() }
                    }
                    .toMap(),
                bootStartEnabled = bootStartManager.isEnabled(),
            )

            ByteArrayOutputStream().use { bos ->
                ZipOutputStream(bos).use { zip ->
                    zip.putEntry(ENTRY_SNAPSHOT, json.encodeToString(snapshot).toByteArray())
                    zipDirIfExists(zip, File(mihomoDir, "imported"), "$ENTRY_FILES_PREFIX/imported")
                    zipDirIfExists(zip, File(mihomoDir, "pending"), "$ENTRY_FILES_PREFIX/pending")
                    val override = File(mihomoDir, OVERRIDE_FILE)
                    if (override.isFile) {
                        zip.putEntry("$ENTRY_FILES_PREFIX/$OVERRIDE_FILE", override.readBytes())
                    }
                }
                bos.toByteArray()
            }
        }
    }

    /**
     * 恢复：覆盖式。imported/ 与 pending/ 目录整体替换，三表清空重放，prefs 逐 key 写入
     * （本机多出的 key 保留）。调用方保证代理已停止；成功返回后必须重启进程。
     */
    suspend fun restoreBackup(bytes: ByteArray) = ProfileProcessor.withProcessLock {
        withContext(Dispatchers.IO) {
            val entries = readZip(bytes)
            val snapshotBytes = entries[ENTRY_SNAPSHOT]
                ?: throw BackupException("backup.json not found in archive")
            val snapshot = runCatching { json.decodeFromString<BackupSnapshot>(snapshotBytes.decodeToString()) }
                .getOrElse { throw BackupException("Invalid backup.json: ${it.message}") }
            if (snapshot.version > BACKUP_VERSION) {
                throw BackupException("Backup version ${snapshot.version} is newer than supported $BACKUP_VERSION")
            }

            // 文件先落地：目录整体替换
            val importedDir = File(mihomoDir, "imported")
            val pendingDir = File(mihomoDir, "pending")
            importedDir.deleteRecursively()
            pendingDir.deleteRecursively()
            File(mihomoDir, OVERRIDE_FILE).delete()
            for ((name, data) in entries) {
                if (!name.startsWith("$ENTRY_FILES_PREFIX/")) continue
                val relative = name.removePrefix("$ENTRY_FILES_PREFIX/")
                val target = File(mihomoDir, relative)
                // zip-slip 防御：规范化后必须仍在 mihomo 目录内
                if (!target.canonicalPath.startsWith(mihomoDir.canonicalPath + File.separator)) {
                    throw BackupException("Illegal entry path: $name")
                }
                target.parentFile?.mkdirs()
                target.writeBytes(data)
            }

            // DB 清空重放；未知 ProfileType（未来版本新增）跳过该条而非失败
            importedDao.clearAll()
            pendingDao.clearAll()
            selectionDao.clearAll()
            snapshot.imported.forEach { p ->
                p.toImportedEntity()?.let { importedDao.insert(it) }
            }
            snapshot.pending.forEach { p ->
                p.toPendingEntity()?.let { pendingDao.insert(it) }
            }
            snapshot.selections.forEach {
                selectionDao.insert(SelectionEntity(it.uuid, it.proxy, it.selected))
            }

            // prefs 恢复（黑名单 key 不写入，本机运行时状态不被备份污染）
            snapshot.stringPrefs.forEach { (k, v) ->
                if (k !in EXCLUDED_PREF_KEYS) storage.putString(k, v)
            }
            snapshot.stringSetPrefs.forEach { (k, v) ->
                if (k !in EXCLUDED_PREF_KEYS) storage.putStringSet(k, v.toSet())
            }

            // active 指向已不存在的订阅时清空，避免启动校验单点报「配置缺失」死循环
            val activeUuid = storage.getString(StorageKeys.ACTIVE_PROFILE_UUID, "")
            if (activeUuid.isNotEmpty() && importedDao.queryByUUID(activeUuid) == null) {
                storage.putString(StorageKeys.ACTIVE_PROFILE_UUID, "")
                storage.putString(StorageKeys.ACTIVE_PROFILE_NAME, "")
            }

            // Wi-Fi 策略的组件位由重启后 MainActivity 按恢复出的 pref reconcile，此处不处理
            bootStartManager.setEnabled(snapshot.bootStartEnabled)
        }
    }

    private fun ImportedEntity.toBackup() = BackupProfile(
        uuid, name, type.name, source, userAgent, ageSecretKey,
        interval, upload, download, total, expire, createdAt,
    )

    private fun PendingEntity.toBackup() = BackupProfile(
        uuid, name, type.name, source, userAgent, ageSecretKey,
        interval, upload, download, total, expire, createdAt,
    )

    private fun BackupProfile.profileType(): ProfileType? =
        runCatching { ProfileType.valueOf(type) }.getOrNull()

    private fun BackupProfile.toImportedEntity(): ImportedEntity? = profileType()?.let {
        ImportedEntity(uuid, name, it, source, userAgent, ageSecretKey, interval, upload, download, total, expire, createdAt)
    }

    private fun BackupProfile.toPendingEntity(): PendingEntity? = profileType()?.let {
        PendingEntity(uuid, name, it, source, userAgent, ageSecretKey, interval, upload, download, total, expire, createdAt)
    }

    private fun ZipOutputStream.putEntry(name: String, data: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(data)
        closeEntry()
    }

    private fun zipDirIfExists(zip: ZipOutputStream, dir: File, entryPrefix: String) {
        if (!dir.isDirectory) return
        dir.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                // 订阅目录里的 GeoIP 是共享 geodata/ 的符号链接（symlink 失败时退化为实体拷贝），
                // 体积大且启动/校验路径的 ensureGeodataLinks 会自动重建，不进备份；
                // 符号链接一律跳过，防止 readBytes 追随链接把目标内容实体化进 zip
                if (java.nio.file.Files.isSymbolicLink(file.toPath())) return@forEach
                if (file.name in ProfileFileOps.GEODATA_FILES) return@forEach
                val relative = file.relativeTo(dir).invariantSeparatorsPath
                zip.putEntry("$entryPrefix/$relative", file.readBytes())
            }
    }

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    result[entry.name] = zis.readBytes()
                }
                entry = zis.nextEntry
            }
        }
        return result
    }

    companion object {
        const val BACKUP_VERSION = 1

        private const val ENTRY_SNAPSHOT = "backup.json"
        private const val ENTRY_FILES_PREFIX = "files"
        private const val OVERRIDE_FILE = "override.user.json"

        /**
         * 不进备份也不从备份恢复的 key：本机/本次运行的设备态（root 探测、进程 PID、
         * boot session、Wi-Fi 策略运行时中间态、一次性迁移标记）与 WebDAV 凭据自身
         * （凭据是「连到这份备份」的前提，写进备份既无意义又多一份泄露面）。
         */
        private val EXCLUDED_PREF_KEYS = setOf(
            StorageKeys.SERVICE_WAS_RUNNING,
            StorageKeys.HAS_ROOT,
            StorageKeys.ROOT_MIHOMO_PID,
            StorageKeys.ROOT_MIHOMO_SECRET,
            StorageKeys.ROOT_START_TIME,
            StorageKeys.ROOT_ACTIVE_SUBSCRIPTION_ID,
            StorageKeys.ROOT_START_ELAPSED,
            StorageKeys.ROOT_SUBMODE_ACTIVE,
            StorageKeys.ROOT_TETHER_MODE_ACTIVE,
            StorageKeys.ROOT_TPROXY_KERNEL_CAPABLE,
            StorageKeys.WIFI_POLICY_MATCHED,
            StorageKeys.WIFI_POLICY_MATCHED_ACTION,
            StorageKeys.WIFI_POLICY_PENDING_RESTART,
            StorageKeys.WIFI_POLICY_RUNTIME_MODE,
            StorageKeys.MIGRATION_ROOT_RECLAIM_DONE,
            StorageKeys.WEBDAV_URL,
            StorageKeys.WEBDAV_USERNAME,
            StorageKeys.WEBDAV_PASSWORD,
        )
    }
}
