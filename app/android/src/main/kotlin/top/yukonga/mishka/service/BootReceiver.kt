package top.yukonga.mishka.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.ProxyServiceController
import top.yukonga.mishka.platform.StorageKeys

/**
 * 开机/升级自动重启接收器。开关由 BootStartManager 控制 enabled/disabled 状态。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val storage = PlatformStorage(context)
                val wasRunning = storage.getString(StorageKeys.SERVICE_WAS_RUNNING, "false") == "true"
                if (!wasRunning) return
                ProxyServiceController(context).start()
            }
        }
    }
}
