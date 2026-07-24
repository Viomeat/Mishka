package top.yukonga.mishka.platform

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts

/**
 * 文件选择结果
 */
data class FilePickResult(
    val fileName: String,
    val content: String,
)

class FilePicker(private val activity: ComponentActivity) {

    private var callback: ((FilePickResult?) -> Unit)? = null

    // Activity Result API：在 Activity onCreate 期间构造 FilePicker 时注册，替代已弃用的
    // startActivityForResult / onActivityResult
    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> handleResult(result) }

    fun pickYamlFile(onResult: (FilePickResult?) -> Unit) {
        callback = onResult
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        launcher.launch(intent)
    }

    // === 二进制备份文件（WebDAV 本地备份同款 zip）===

    private var saveBytes: ByteArray? = null
    private var saveCallback: ((Boolean?) -> Unit)? = null
    private val saveLauncher = activity.registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> handleSaveResult(uri) }

    private var pickBytesCallback: ((ByteArray?) -> Unit)? = null
    private val pickBytesLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> handlePickBytesResult(uri) }

    /** 经 SAF 另存为文件。onResult：true 写入成功 / false 写入失败 / null 用户取消。 */
    fun saveZipFile(suggestedName: String, bytes: ByteArray, onResult: (Boolean?) -> Unit) {
        saveBytes = bytes
        saveCallback = onResult
        saveLauncher.launch(suggestedName)
    }

    /** 经 SAF 选择文件并读全部字节；取消或读取失败返回 null。 */
    fun pickZipFile(onResult: (ByteArray?) -> Unit) {
        pickBytesCallback = onResult
        // 不按 MIME 过滤：备份文件经网盘/传输工具流转后 provider 常报 octet-stream 等杂项类型
        pickBytesLauncher.launch(arrayOf("*/*"))
    }

    private fun handleSaveResult(uri: Uri?) {
        val bytes = saveBytes
        val cb = saveCallback
        saveBytes = null
        saveCallback = null
        if (uri == null || bytes == null) {
            cb?.invoke(null)
            return
        }
        val ok = try {
            // "wt" 截断写：目标文档已存在且比新内容长时，默认模式会残留旧尾部导致 zip 损坏
            activity.contentResolver.openOutputStream(uri, "wt")?.use {
                it.write(bytes)
                true
            } ?: false
        } catch (_: Exception) {
            false
        }
        cb?.invoke(ok)
    }

    private fun handlePickBytesResult(uri: Uri?) {
        val cb = pickBytesCallback
        pickBytesCallback = null
        if (uri == null) {
            cb?.invoke(null)
            return
        }
        val bytes = try {
            activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
        cb?.invoke(bytes)
    }

    private fun handleResult(result: ActivityResult) {
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data?.data == null) {
            callback?.invoke(null)
            callback = null
            return
        }
        val uri: Uri = data.data!!
        try {
            val fileName = getFileName(uri)
            val content = activity.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: ""
            callback?.invoke(FilePickResult(fileName, content))
        } catch (_: Exception) {
            callback?.invoke(null)
        }
        callback = null
    }

    private fun getFileName(uri: Uri): String {
        var name = "imported.yaml"
        activity.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }
}
