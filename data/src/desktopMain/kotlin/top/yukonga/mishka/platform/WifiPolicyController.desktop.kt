package top.yukonga.mishka.platform

actual class WifiPolicyController actual constructor(context: PlatformContext) {
    actual fun hasRequiredPermission(): Boolean = false
    actual fun currentSsid(): String? = null
    actual fun startMonitor() = Unit
    actual fun stopMonitor() = Unit
    actual fun evaluateNow() = Unit
}
