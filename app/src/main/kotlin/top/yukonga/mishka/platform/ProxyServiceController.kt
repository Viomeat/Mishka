package top.yukonga.mishka.platform

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.SystemClock
import androidx.activity.result.ActivityResultLauncher
import kotlinx.coroutines.flow.StateFlow
import top.yukonga.mishka.R
import java.io.File

enum class ProxyState {
    Stopped,
    Starting,
    Running,
    Stopping,
    Error,
}

enum class TunMode { Vpn, RootTun, RootTproxy }

data class ProxyServiceStatus(
    val state: ProxyState = ProxyState.Stopped,
    val secret: String = "",
    val externalController: String = "127.0.0.1:9090",
    val errorMessage: String = "",
    val tunMode: TunMode = TunMode.Vpn,
    val startTime: Long = 0L,
    val mihomoPid: Int = -1,
)

class ProxyServiceController(private val context: Context) {

    private val storage by lazy { PlatformStorage(context) }

    // 见 verifyAndSyncState：自动连接每进程只消费一次
    private var launchAutoConnectConsumed = false

    val status: StateFlow<ProxyServiceStatus> = ProxyServiceBridge.state

    fun start(subscriptionId: String? = null) {
        val id = resolveStartSubscriptionId(subscriptionId) ?: return
        val mode = getTunMode()
        val intent = buildServiceIntent(mode, "START").apply {
            putExtra(EXTRA_SUBSCRIPTION_ID, id)
        }
        context.startForegroundService(intent)
    }

    fun restart(subscriptionId: String? = null) {
        val id = resolveStartSubscriptionId(subscriptionId) ?: return
        // 优先读 bridge：代理运行中时它反映实际 Service；否则读 storage（用户最新选择）
        val mode = activeModeOrStored()
        val intent = buildServiceIntent(mode, "RESTART").apply {
            putExtra(EXTRA_SUBSCRIPTION_ID, id)
        }
        context.startService(intent)
    }

    fun stop() {
        val mode = activeModeOrStored()
        val intent = buildServiceIntent(mode, "STOP")
        context.startService(intent)
    }

    /**
     * app reopen 时对 ROOT 模式的重连入口（attach-only）：只尝试重连仍存活的 mihomo 进程，
     * attach 失败时 Service 端不会回退到全新启动（与 [start] 语义不同）。用户未主动请求启动，
     * 仅是打开了 app，因此进程若已不存在必须保持停止，而非自动跑起来。
     */
    fun reattachRoot() {
        val mode = getTunMode()
        if (mode != TunMode.RootTun && mode != TunMode.RootTproxy) return
        val id = storage.getString(StorageKeys.ACTIVE_PROFILE_UUID, "").ifEmpty { null } ?: run {
            storage.putString(StorageKeys.SERVICE_WAS_RUNNING, "false")
            return
        }
        val intent = buildServiceIntent(mode, "START").apply {
            putExtra(EXTRA_SUBSCRIPTION_ID, id)
            putExtra("attach_only", true)
        }
        context.startForegroundService(intent)
    }

    /**
     * 启动前的订阅校验统一入口。校验失败时 toast、emit Bridge.Error，并在代理仍 Running 时
     * 同步发 STOP 让状态自洽。HomeUiState.errorMessage 当前未在 UI 展示，因此 Toast 必要。
     */
    fun resolveStartSubscriptionId(subscriptionId: String? = null): String? {
        startableSubscriptionId(subscriptionId)?.let { return it }

        val msg = noActiveProfileMessage()
        showToast(msg, long = true)
        val mode = activeModeOrStored()
        val wasRunning = ProxyServiceBridge.state.value.state == ProxyState.Running
        ProxyServiceBridge.updateState(
            ProxyServiceStatus(
                state = ProxyState.Error,
                errorMessage = msg,
                tunMode = mode,
            )
        )
        if (wasRunning) {
            context.startService(buildServiceIntent(mode, "STOP"))
        }
        // 防止 Boot/升级路径在订阅缺失下被 BootReceiver 反复触发
        storage.putString(StorageKeys.SERVICE_WAS_RUNNING, "false")
        return null
    }

    /**
     * 纯校验版：active 订阅存在且 config.yaml 已落盘时返回其 uuid，否则 null。无任何副作用，
     * 供自动连接这类「不该因缺订阅打断用户」的静默路径使用。
     */
    private fun startableSubscriptionId(subscriptionId: String? = null): String? {
        val effective = subscriptionId
            ?: storage.getString(StorageKeys.ACTIVE_PROFILE_UUID, "").ifEmpty { null }
            ?: return null
        val config = File(context.filesDir, "mihomo/imported/$effective/config.yaml")
        return effective.takeIf { config.isFile && config.length() > 0 }
    }

    private fun noActiveProfileMessage(): String =
        context.getString(R.string.error_no_active_profile)

    /**
     * 解析当前应该操作的 TunMode：
     * - 代理正在 Running/Starting/Stopping 时，用 bridge 的 tunMode（对应实际在跑的 Service）
     * - 否则用 storage 里的当前选择（用户可能刚改但还没启动）
     */
    private fun activeModeOrStored(): TunMode {
        val bridge = ProxyServiceBridge.state.value
        return if (bridge.state != ProxyState.Stopped && bridge.state != ProxyState.Error) {
            bridge.tunMode
        } else {
            getTunMode()
        }
    }

    /**
     * 组装指向目标 Service 的 Intent。ROOT_TUN / ROOT_TPROXY 共用 MishkaRootService，
     * 通过 EXTRA_SUBMODE 区分内部分支。
     */
    private fun buildServiceIntent(mode: TunMode, op: String): Intent {
        val (className, action, submode) = when (mode) {
            TunMode.Vpn -> Triple(
                "top.yukonga.mishka.service.MishkaTunService",
                "top.yukonga.mishka.$op",
                null,
            )

            TunMode.RootTun -> Triple(
                "top.yukonga.mishka.service.MishkaRootService",
                "top.yukonga.mishka.ROOT_$op",
                "tun",
            )

            TunMode.RootTproxy -> Triple(
                "top.yukonga.mishka.service.MishkaRootService",
                "top.yukonga.mishka.ROOT_$op",
                "tproxy",
            )
        }
        return Intent().apply {
            setClassName(context.packageName, className)
            this.action = action
            submode?.let { putExtra("submode", it) }
        }
    }

    // 由宿主 Activity（MainActivity）在 onCreate 注册后注入，替代已弃用的 startActivityForResult
    private var vpnPermissionLauncher: ActivityResultLauncher<Intent>? = null

    fun setVpnPermissionLauncher(launcher: ActivityResultLauncher<Intent>?) {
        vpnPermissionLauncher = launcher
    }

    fun requestVpnPermission() {
        if (getTunMode() != TunMode.Vpn) return
        // prepare 返回 null 表示已授权，无需弹窗
        val intent = VpnService.prepare(context) ?: return
        vpnPermissionLauncher?.launch(intent)
    }

    fun hasVpnPermission(): Boolean {
        if (getTunMode() != TunMode.Vpn) return true
        return VpnService.prepare(context) == null
    }

    fun hasRootPermission(): Boolean {
        return storage.getString(StorageKeys.HAS_ROOT, "false") == "true"
    }

    fun getTunMode(): TunMode {
        return when (storage.getString(StorageKeys.TUN_MODE, "vpn")) {
            "root_tun" -> TunMode.RootTun
            "root_tproxy" -> TunMode.RootTproxy
            else -> TunMode.Vpn
        }
    }

    fun verifyAndSyncState() {
        // 「打开应用时自动连接」是每次冷启动一次的意图：controller 由 Koin 以 single 提供，
        // 标记随进程存活，因此回前台的后续 onResume 不再触发——否则用户手动停止后切回来又被拉起
        val autoConnect = !launchAutoConnectConsumed &&
                storage.getString(StorageKeys.AUTO_CONNECT_ON_LAUNCH, "false") == "true"
        launchAutoConnectConsumed = true

        val wasRunning = storage.getString(StorageKeys.SERVICE_WAS_RUNNING, "false") == "true"
        val bridgeState = ProxyServiceBridge.state.value.state

        // bridge 认为在运行，但 VPN 权限已丢失（被其他 VPN 顶替），纠正状态
        if (bridgeState == ProxyState.Running || bridgeState == ProxyState.Starting) {
            if (getTunMode() == TunMode.Vpn && !hasVpnPermission()) {
                storage.putString(StorageKeys.SERVICE_WAS_RUNNING, "false")
                ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Stopped))
            }
            return
        }

        // 卡在 Stopping 状态（异步块被取消的残留场景），直接重置
        if (bridgeState == ProxyState.Stopping) {
            storage.putString(StorageKeys.SERVICE_WAS_RUNNING, "false")
            ProxyServiceBridge.updateState(ProxyServiceStatus(ProxyState.Stopped))
            return
        }

        val currentMode = getTunMode()
        // ROOT 模式：app 被杀但设备未重启时，mihomo root 进程仍存活，重新打开 app 时尝试
        // attach-only 重连（绝不全新启动）。但设备重启会杀死 root 进程，此时持久化的 PID 已
        // 过期——仅凭"PID 字符串非空"会误判为"进程仍活"而自动全新启动（用户未开启开机自启
        // 却看到代理自动跑起来的根源）。用 boot session 标记识别重启：elapsedRealtime 重启
        // 归零、单调递增，now < 启动时刻 ⇒ 期间重启过 ⇒ 进程必死，直接清状态、保持停止。
        if (wasRunning && (currentMode == TunMode.RootTun || currentMode == TunMode.RootTproxy)) {
            val hasPid = storage.getString(StorageKeys.ROOT_MIHOMO_PID, "").isNotEmpty()
            val startElapsed = storage.getString(StorageKeys.ROOT_START_ELAPSED, "").toLongOrNull()
            val rebooted = startElapsed != null && SystemClock.elapsedRealtime() < startElapsed
            if (hasPid && !rebooted) {
                // 同一 boot session 内且有持久化 PID：可能仍存活，交给 Service 做三重存活校验重连。
                // 自动连接开启时改走 start()——它同样先尝试 attach 复用存活进程，区别只在 attach
                // 不成时允许全新启动，而用户已表达「打开应用就要连上」的意图
                if (autoConnect) launchAutoConnect() else reattachRoot()
                return
            }
            // 设备已重启 / 无 PID 可重连：清掉过期状态，保持停止
            storage.putString(StorageKeys.SERVICE_WAS_RUNNING, "false")
            storage.putString(StorageKeys.ROOT_MIHOMO_PID, "")
            storage.putString(StorageKeys.ROOT_START_ELAPSED, "")
            if (autoConnect) launchAutoConnect()
            return
        }

        if (wasRunning && currentMode == TunMode.Vpn) {
            storage.putString(StorageKeys.SERVICE_WAS_RUNNING, "false")
        }
        if (autoConnect) launchAutoConnect()
    }

    /**
     * 冷启动时按用户设置自动连接。无可用订阅时静默返回——用户只是打开了 app，不该用错误 toast
     * 打断；VPN 缺授权时弹系统授权框，授权回调（MainActivity 的 launcher）会重新发起启动。
     */
    private fun launchAutoConnect() {
        val id = startableSubscriptionId() ?: return
        if (getTunMode() == TunMode.Vpn && !hasVpnPermission()) {
            requestVpnPermission()
            return
        }
        start(id)
    }

    companion object {
        const val EXTRA_SUBSCRIPTION_ID = "subscription_id"
    }
}
