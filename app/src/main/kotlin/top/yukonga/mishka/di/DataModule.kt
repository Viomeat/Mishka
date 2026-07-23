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
 * data 层 Koin 装配：仓库 / 存储 / 处理器。
 * Android 单例（AppDatabase / PlatformStorage / 各控制器）由 `androidPlatformModule` 提供；
 * ProfileFileManager 实现由 `androidAppModule` 绑定。
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
