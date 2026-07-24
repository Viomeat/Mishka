package top.yukonga.mishka.viewmodel

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import top.yukonga.mishka.R
import top.yukonga.mishka.data.backup.BackupManager
import top.yukonga.mishka.data.backup.WebDavClient
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.ProxyServiceBridge
import top.yukonga.mishka.platform.ProxyState
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.platform.showToast
import top.yukonga.mishka.util.describe

@Immutable
data class BackupUiState(
    val isBusy: Boolean = false,
    val restoreCompleted: Boolean = false,
)

class BackupViewModel(
    private val backupManager: BackupManager,
    private val storage: PlatformStorage,
    private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    private fun client(): WebDavClient? {
        val url = storage.getString(StorageKeys.WEBDAV_URL, "").trim()
        val user = storage.getString(StorageKeys.WEBDAV_USERNAME, "")
        val pass = storage.getString(StorageKeys.WEBDAV_PASSWORD, "")
        if (url.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            showToast(context.getString(R.string.backup_missing_credentials))
            return null
        }
        return WebDavClient(url, user, pass)
    }

    fun testConnection() = runBusy {
        val client = client() ?: return@runBusy
        client.testConnection()
        showToast(context.getString(R.string.backup_connection_ok))
    }

    fun backup() = runBusy {
        val client = client() ?: return@runBusy
        client.upload(backupManager.createBackup())
        showToast(context.getString(R.string.backup_done))
    }

    fun restore() = runBusy {
        if (!ensureProxyStopped()) return@runBusy
        val client = client() ?: return@runBusy
        val bytes = client.download()
        if (bytes == null) {
            showToast(context.getString(R.string.backup_not_found))
            return@runBusy
        }
        backupManager.restoreBackup(bytes)
        _uiState.update { it.copy(restoreCompleted = true) }
    }

    /** 本地导出：打包后交给 [save]（SAF CreateDocument）写盘。结果回调 null 表示用户取消，不提示。 */
    fun exportBackup(save: (bytes: ByteArray, onResult: (Boolean?) -> Unit) -> Unit) = runBusy {
        val bytes = backupManager.createBackup()
        val ok = suspendCancellableCoroutine { cont ->
            save(bytes) { result -> cont.resume(result) }
        }
        when (ok) {
            true -> showToast(context.getString(R.string.backup_export_done))
            false -> showToast(context.getString(R.string.backup_local_save_failed))
            null -> Unit
        }
    }

    /** 本地导入：SAF 已读出的 zip 字节。前置校验与 WebDAV 恢复一致。 */
    fun restoreFromBytes(bytes: ByteArray) = runBusy {
        if (!ensureProxyStopped()) return@runBusy
        backupManager.restoreBackup(bytes)
        _uiState.update { it.copy(restoreCompleted = true) }
    }

    // 恢复覆盖 imported/ 与 DB，代理运行/过渡态期间禁止（mihomo 正在读这些文件）
    private fun ensureProxyStopped(): Boolean {
        val state = ProxyServiceBridge.state.value.state
        if (state != ProxyState.Stopped && state != ProxyState.Error) {
            showToast(context.getString(R.string.backup_stop_proxy_first))
            return false
        }
        return true
    }

    private fun runBusy(block: suspend () -> Unit) {
        if (_uiState.value.isBusy) return
        _uiState.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: Throwable) {
                showToast(context.getString(R.string.backup_failed, e.describe()), long = true)
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }
}
