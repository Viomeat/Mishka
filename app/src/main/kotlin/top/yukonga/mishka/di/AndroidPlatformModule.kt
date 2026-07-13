package top.yukonga.mishka.di

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module
import top.yukonga.mishka.data.database.getAppDatabase
import top.yukonga.mishka.platform.AppListProvider
import top.yukonga.mishka.platform.BootStartManager
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.ProxyServiceController
import top.yukonga.mishka.platform.WifiPolicyController

/**
 * Android 平台单例：均绑定 application Context（`androidContext()`）。
 * 需 Activity 上下文的资源（FilePicker / VPN 授权 launcher）由 MainActivity 直接持有，不入 Koin。
 */
val androidPlatformModule: Module = module {
    single { getAppDatabase(androidContext()) }
    single { PlatformStorage(androidContext()) }
    single { ProxyServiceController(androidContext()) }
    single { AppListProvider(androidContext()) }
    single { WifiPolicyController(androidContext()) }
    single { BootStartManager(androidContext()) }
}
