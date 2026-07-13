package top.yukonga.mishka.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.dsl.module
import top.yukonga.mishka.data.api.MihomoConnectionManager
import top.yukonga.mishka.data.database.AppDatabase
import top.yukonga.mishka.data.repository.OverrideJsonStore
import top.yukonga.mishka.data.repository.ProfileProcessor
import top.yukonga.mishka.data.repository.SubscriptionProxyResolver
import top.yukonga.mishka.data.repository.SubscriptionRepositoryImpl
import top.yukonga.mishka.domain.repository.SubscriptionRepository

/**
 * data 层 Koin 装配：平台无关的仓库 / 存储 / 处理器。
 * 平台相关单例（AppDatabase / PlatformStorage / 平台控制器）由各平台的 platform module 提供
 * （Android：`androidPlatformModule`）；ProfileFileManager 实现由 app 壳模块绑定。
 */
val dataModule = module {
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    single { get<AppDatabase>().importedDao() }
    single { get<AppDatabase>().pendingDao() }
    single { get<AppDatabase>().selectionDao() }

    single { OverrideJsonStore(get()) }
    single { SubscriptionProxyResolver(get(), get()) }
    single { MihomoConnectionManager(get()) }

    single { SubscriptionRepositoryImpl(get(), get(), get(), get(), getOrNull(), get()) }
    single<SubscriptionRepository> { get<SubscriptionRepositoryImpl>() }

    factory { ProfileProcessor(get(), get(), get()) }
}
