package top.yukonga.mishka.ui.navigation

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.NavDisplayTransitionEffects
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mishka.shared.generated.resources.Res
import mishka.shared.generated.resources.nav_home
import mishka.shared.generated.resources.nav_proxy
import mishka.shared.generated.resources.nav_settings
import mishka.shared.generated.resources.nav_subscription
import org.jetbrains.compose.resources.stringResource
import top.yukonga.mishka.platform.BootStartManager
import top.yukonga.mishka.platform.FilePicker
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.WifiPolicyController
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.component.liquid.IosLiquidGlassNavigationBar
import top.yukonga.mishka.ui.navigation3.LocalNavigator
import top.yukonga.mishka.ui.navigation3.Navigator
import top.yukonga.mishka.ui.navigation3.Route
import top.yukonga.mishka.ui.screen.connection.ConnectionScreen
import top.yukonga.mishka.ui.screen.dns.DnsQueryScreen
import top.yukonga.mishka.ui.screen.home.HomeScreen
import top.yukonga.mishka.ui.screen.log.LogScreen
import top.yukonga.mishka.ui.screen.provider.ProviderScreen
import top.yukonga.mishka.ui.screen.proxy.ProxyScreen
import top.yukonga.mishka.ui.screen.settings.AboutScreen
import top.yukonga.mishka.ui.screen.settings.AppProxyScreen
import top.yukonga.mishka.ui.screen.settings.ExternalControlScreen
import top.yukonga.mishka.ui.screen.settings.FileManagerEditorScreen
import top.yukonga.mishka.ui.screen.settings.FileManagerScreen
import top.yukonga.mishka.ui.screen.settings.MetaSettingsScreen
import top.yukonga.mishka.ui.screen.settings.NetworkSettingsScreen
import top.yukonga.mishka.ui.screen.settings.RootSettingsScreen
import top.yukonga.mishka.ui.screen.settings.SettingsScreen
import top.yukonga.mishka.ui.screen.settings.ThemeSettingsScreen
import top.yukonga.mishka.ui.screen.settings.VpnSettingsScreen
import top.yukonga.mishka.ui.screen.settings.WifiPolicyScreen
import top.yukonga.mishka.ui.theme.BottomBarMode
import top.yukonga.mishka.ui.theme.FloatingBottomBarStyle
import top.yukonga.mishka.ui.theme.LocalAppDarkMode
import top.yukonga.mishka.ui.theme.ThemeConfig
import top.yukonga.mishka.ui.screen.subscription.SubscriptionAddScreen
import top.yukonga.mishka.ui.screen.subscription.SubscriptionAddUrlScreen
import top.yukonga.mishka.ui.screen.subscription.SubscriptionEditScreen
import top.yukonga.mishka.ui.screen.subscription.SubscriptionScreen
import top.yukonga.mishka.ui.util.rememberIsWideScreen
import top.yukonga.mishka.viewmodel.AppProxyViewModel
import top.yukonga.mishka.viewmodel.ConnectionViewModel
import top.yukonga.mishka.viewmodel.DnsQueryViewModel
import top.yukonga.mishka.viewmodel.ExternalControlViewModel
import top.yukonga.mishka.viewmodel.HomeUiState
import top.yukonga.mishka.viewmodel.HomeViewModel
import top.yukonga.mishka.viewmodel.LogViewModel
import top.yukonga.mishka.viewmodel.MetaSettingsViewModel
import top.yukonga.mishka.viewmodel.NetworkSettingsViewModel
import top.yukonga.mishka.viewmodel.ProviderViewModel
import top.yukonga.mishka.viewmodel.ProxyViewModel
import top.yukonga.mishka.viewmodel.SubscriptionViewModel
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberNavigationRailState
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.icon.extended.UploadCloud
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs

// Route 是 @Serializable sealed 层级，直接多态编码整个栈；新增路由自动获得持久化能力，无需手工注册
private val NavBackStackSaver = Saver<SnapshotStateList<NavKey>, List<String>>(
    save = { backStack ->
        backStack.mapNotNull { key ->
            (key as? Route)?.let { Json.encodeToString<Route>(it) }
        }.ifEmpty {
            listOf(Json.encodeToString<Route>(Route.Main))
        }
    },
    restore = { savedRoutes ->
        val restoredRoutes = savedRoutes.mapNotNull { value ->
            // 版本升降级后可能残留未知路由条目：丢弃该条而非崩溃
            runCatching { Json.decodeFromString<Route>(value) }.getOrNull()
        }
        mutableStateListOf<NavKey>().apply {
            if (restoredRoutes.isEmpty()) {
                add(Route.Main)
            } else {
                if (restoredRoutes.first() !is Route.Main) add(Route.Main)
                addAll(restoredRoutes)
            }
        }
    },
)

val LocalMainPagerState = staticCompositionLocalOf<MainPagerState> {
    error("LocalMainPagerState not provided")
}

@Composable
fun AppNavigation(
    themeConfig: ThemeConfig = ThemeConfig(),
    onThemeConfigChange: (ThemeConfig) -> Unit = {},
    homeViewModel: HomeViewModel? = null,
    subscriptionViewModel: SubscriptionViewModel? = null,
    proxyViewModel: ProxyViewModel? = null,
    logViewModel: LogViewModel? = null,
    providerViewModel: ProviderViewModel? = null,
    connectionViewModel: ConnectionViewModel? = null,
    dnsQueryViewModel: DnsQueryViewModel? = null,
    networkSettingsViewModel: NetworkSettingsViewModel? = null,
    metaSettingsViewModel: MetaSettingsViewModel? = null,
    externalControlViewModel: ExternalControlViewModel? = null,
    appProxyViewModel: AppProxyViewModel? = null,
    filePicker: FilePicker? = null,
    storage: PlatformStorage? = null,
    bootStartManager: BootStartManager? = null,
    mihomoVersion: String = "",
    onScanQR: ((callback: (String?) -> Unit) -> Unit)? = null,
    wifiPolicyController: WifiPolicyController? = null,
    onRequestWifiPermission: (((Boolean) -> Unit) -> Unit)? = null,
    onPredictiveBackChange: ((Boolean) -> Unit)? = null,
    onHideTaskCardChange: ((Boolean) -> Unit)? = null,
    hasRootPermission: Boolean = false,
) {
    val backStack = rememberSaveable(saver = NavBackStackSaver) { mutableStateListOf<NavKey>(Route.Main) }
    val navigator = remember { Navigator(backStack) }
    val pagerState = rememberPagerState(pageCount = { 4 })
    val mainPagerState = rememberMainPagerState(pagerState)

    LaunchedEffect(mainPagerState.pagerState.currentPage) {
        mainPagerState.syncPage()
        // 切到代理 Tab 时刷新 mihomo 缓存的 history.delay；URL-Test 组的周期测试结果靠这里同步到 UI
        if (mainPagerState.pagerState.currentPage == 1) proxyViewModel?.loadProxies()
    }

    MainScreenBackHandler(mainPagerState, navigator)

    CompositionLocalProvider(
        LocalNavigator provides navigator,
        LocalMainPagerState provides mainPagerState,
    ) {
        val provider = entryProvider<NavKey> {
            entry<Route.Main> {
                MainPage(
                    homeViewModel,
                    proxyViewModel,
                    subscriptionViewModel,
                    navigator,
                    mainPagerState,
                    bootStartManager,
                    themeConfig,
                    onThemeConfigChange,
                    storage,
                    onPredictiveBackChange,
                    onHideTaskCardChange,
                    hasRootPermission,
                    wifiPolicyController,
                    onRequestWifiPermission,
                )
            }
            entry<Route.Subscription> {
                subscriptionViewModel?.let {
                    SubscriptionScreen(
                        viewModel = it,
                        onBack = { navigator.pop() },
                        onNavigateAdd = { navigator.push(Route.SubscriptionAdd) },
                        onNavigateEdit = { uuid -> navigator.push(Route.SubscriptionEdit(uuid)) },
                        onActiveChanged = { homeViewModel?.onActiveSubscriptionChanged() },
                    )
                }
            }
            entry<Route.SubscriptionAdd> {
                SubscriptionAddScreen(
                    viewModel = subscriptionViewModel,
                    onBack = { navigator.pop() },
                    onPickFile = {
                        filePicker?.pickYamlFile { result ->
                            if (result != null && subscriptionViewModel != null) {
                                subscriptionViewModel.addFromFile(
                                    fileName = result.fileName,
                                    content = result.content,
                                    onComplete = {
                                        navigator.popUntil { key -> key is Route.Subscription }
                                    },
                                )
                            }
                        }
                    },
                    onNavigateUrl = { navigator.push(Route.SubscriptionAddUrl()) },
                    onScanQR = if (onScanQR != null) {
                        {
                            onScanQR { url ->
                                if (url != null) {
                                    navigator.push(Route.SubscriptionAddUrl(initialUrl = url))
                                }
                            }
                        }
                    } else null,
                )
            }
            entry<Route.SubscriptionAddUrl> { route ->
                subscriptionViewModel?.let {
                    SubscriptionAddUrlScreen(
                        viewModel = it,
                        initialUrl = route.initialUrl,
                        onBack = { navigator.pop() },
                        onSaved = { navigator.popUntil { key -> key is Route.Subscription } },
                    )
                }
            }
            entry<Route.SubscriptionEdit> { route ->
                subscriptionViewModel?.let {
                    SubscriptionEditScreen(
                        uuid = route.uuid,
                        viewModel = it,
                        onBack = { navigator.pop() },
                        onSaved = { navigator.pop() },
                    )
                }
            }
            entry<Route.Log> {
                logViewModel?.let {
                    LogScreen(
                        viewModel = it,
                        onBack = { navigator.pop() },
                    )
                }
            }
            entry<Route.Provider> {
                providerViewModel?.let {
                    ProviderScreen(
                        viewModel = it,
                        onBack = { navigator.pop() },
                    )
                }
            }
            entry<Route.Connection> {
                connectionViewModel?.let {
                    ConnectionScreen(
                        viewModel = it,
                        onBack = { navigator.pop() },
                    )
                }
            }
            entry<Route.DnsQuery> {
                dnsQueryViewModel?.let {
                    DnsQueryScreen(
                        viewModel = it,
                        onBack = { navigator.pop() },
                    )
                }
            }
            entry<Route.VpnSettings> {
                storage?.let {
                    VpnSettingsScreen(
                        storage = it,
                        isSystemProxySupported = true,
                        onBack = { navigator.pop() },
                    )
                }
            }
            entry<Route.RootSettings> {
                storage?.let {
                    val homeState = homeViewModel?.uiState?.collectAsStateWithLifecycle()?.value
                    RootSettingsScreen(
                        storage = it,
                        isProxyRunning = homeState?.isRunning == true || homeState?.isStarting == true,
                        onBack = { navigator.pop() },
                    )
                }
            }
            entry<Route.NetworkSettings> {
                networkSettingsViewModel?.let {
                    NetworkSettingsScreen(
                        viewModel = it,
                        onBack = { navigator.pop() },
                    )
                }
            }
            entry<Route.MetaSettings> {
                metaSettingsViewModel?.let {
                    MetaSettingsScreen(
                        viewModel = it,
                        onBack = { navigator.pop() },
                    )
                }
            }
            entry<Route.AppProxy> {
                appProxyViewModel?.let {
                    AppProxyScreen(
                        viewModel = it,
                        onBack = { navigator.pop() },
                    )
                }
            }
            entry<Route.WifiPolicy> {
                storage?.let {
                    WifiPolicyScreen(
                        storage = it,
                        controller = wifiPolicyController,
                        onRequestPermission = onRequestWifiPermission,
                        onBack = { navigator.pop() },
                    )
                }
            }
            entry<Route.ThemeSettings> {
                storage?.let {
                    ThemeSettingsScreen(
                        storage = it,
                        themeConfig = themeConfig,
                        onThemeConfigChange = onThemeConfigChange,
                        onPredictiveBackChange = onPredictiveBackChange,
                        onBack = { navigator.pop() },
                    )
                }
            }
            entry<Route.ExternalControl> {
                externalControlViewModel?.let {
                    ExternalControlScreen(
                        viewModel = it,
                        onBack = { navigator.pop() },
                    )
                }
            }
            entry<Route.FileManager> {
                FileManagerScreen(
                    subscriptionViewModel = subscriptionViewModel,
                    onBack = { navigator.pop() },
                    onOpenFile = { uuid, relPath ->
                        navigator.push(Route.FileManagerEditor(uuid, relPath))
                    },
                )
            }
            entry<Route.FileManagerEditor> { route ->
                FileManagerEditorScreen(
                    uuid = route.uuid,
                    relativePath = route.relativePath,
                    subscriptionViewModel = subscriptionViewModel,
                    onBack = { navigator.pop() },
                )
            }
            entry<Route.About> {
                val uriHandler = LocalUriHandler.current
                AboutScreen(
                    onBack = { navigator.pop() },
                    mihomoVersion = mihomoVersion,
                    onOpenUrl = { url -> uriHandler.openUri(url) },
                )
            }
        }

        val entries = rememberDecoratedNavEntries(
            backStack = backStack,
            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
            entryProvider = provider,
        )

        NavDisplay(
            entries = entries,
            onBack = { navigator.pop() },
            transitionEffects = NavDisplayTransitionEffects(
                enableCornerClip = true,
                dimAmount = 0.5f,
                blockInputDuringTransition = true,
            ),
        )
    }
}

@Composable
private fun MainPage(
    homeViewModel: HomeViewModel?,
    proxyViewModel: ProxyViewModel?,
    subscriptionViewModel: SubscriptionViewModel?,
    navigator: Navigator,
    mainPagerState: MainPagerState,
    bootStartManager: BootStartManager? = null,
    themeConfig: ThemeConfig = ThemeConfig(),
    onThemeConfigChange: (ThemeConfig) -> Unit = {},
    storage: PlatformStorage? = null,
    onPredictiveBackChange: ((Boolean) -> Unit)? = null,
    onHideTaskCardChange: ((Boolean) -> Unit)? = null,
    hasRootPermission: Boolean = false,
    wifiPolicyController: WifiPolicyController? = null,
    onRequestWifiPermission: (((Boolean) -> Unit) -> Unit)? = null,
) {
    val homeUiState = homeViewModel?.uiState?.collectAsStateWithLifecycle()?.value ?: HomeUiState()
    val selectedPage = mainPagerState.selectedPage

    // 页面主体：手机与宽屏两套外壳共用，仅传入不同的容器 modifier 与底部留白
    val pagerContent: @Composable (Modifier, Dp) -> Unit = { pagerModifier, bottomPadding ->
        HorizontalPager(
            modifier = pagerModifier,
            state = mainPagerState.pagerState,
            verticalAlignment = Alignment.Top,
        ) { page ->
            when (page) {
                0 -> HomeScreen(
                    bottomPadding = bottomPadding,
                    uiState = homeUiState,
                    viewModel = homeViewModel,
                    onRestart = { homeViewModel?.restartProxy() },
                    onStop = { homeViewModel?.stopProxy() },
                    onReload = { homeViewModel?.reloadConfig() },
                    onTestLatency = { homeViewModel?.testLatency() },
                    onNavigateLog = { navigator.push(Route.Log) },
                    onNavigateProvider = { navigator.push(Route.Provider) },
                    onNavigateConnection = { navigator.push(Route.Connection) },
                    onNavigateDnsQuery = { navigator.push(Route.DnsQuery) },
                    onStartProxy = { homeViewModel?.startProxy() },
                    onSwitchMode = { homeViewModel?.switchMode(it) },
                    onSwitchTunStack = { homeViewModel?.switchTunStack(it) },
                    onSwitchProxyGroup = { homeViewModel?.switchProxyGroup(it) },
                )

                1 -> ProxyScreen(bottomPadding = bottomPadding, viewModel = proxyViewModel)
                2 -> subscriptionViewModel?.let {
                    SubscriptionScreen(
                        viewModel = it,
                        bottomPadding = bottomPadding,
                        onNavigateAdd = { navigator.push(Route.SubscriptionAdd) },
                        onNavigateEdit = { uuid -> navigator.push(Route.SubscriptionEdit(uuid)) },
                        onActiveChanged = { homeViewModel?.onActiveSubscriptionChanged() },
                    )
                }

                3 -> SettingsScreen(
                    bottomPadding = bottomPadding,
                    onNavigateVpnSettings = { navigator.push(Route.VpnSettings) },
                    onNavigateRootSettings = { navigator.push(Route.RootSettings) },
                    onNavigateNetworkSettings = { navigator.push(Route.NetworkSettings) },
                    onNavigateMetaSettings = { navigator.push(Route.MetaSettings) },
                    onNavigateExternalControl = { navigator.push(Route.ExternalControl) },
                    onNavigateAppProxy = { navigator.push(Route.AppProxy) },
                    onNavigateWifiPolicy = { navigator.push(Route.WifiPolicy) },
                    onNavigateThemeSettings = { navigator.push(Route.ThemeSettings) },
                    onNavigateFileManager = { navigator.push(Route.FileManager) },
                    onNavigateAbout = { navigator.push(Route.About) },
                    bootStartManager = bootStartManager,
                    storage = storage,
                    onHideTaskCardChange = onHideTaskCardChange,
                    hasRootPermission = hasRootPermission,
                    isProxyRunning = homeUiState.isRunning || homeUiState.isStarting,
                )
            }
        }
    }

    if (rememberIsWideScreen()) {
        // 宽屏：侧边 NavigationRail 取代底部 NavigationBar，纵向空间全部让给内容
        Scaffold(modifier = Modifier.fillMaxSize()) { _ ->
            Row(Modifier.fillMaxSize()) {
                NavigationRail(state = rememberNavigationRailState()) {
                    NavigationRailItem(
                        selected = selectedPage == 0,
                        onClick = { mainPagerState.animateToPage(0) },
                        icon = MiuixIcons.Home,
                        label = stringResource(Res.string.nav_home),
                    )
                    NavigationRailItem(
                        selected = selectedPage == 1,
                        onClick = { mainPagerState.animateToPage(1) },
                        icon = MiuixIcons.Tune,
                        label = stringResource(Res.string.nav_proxy),
                    )
                    NavigationRailItem(
                        selected = selectedPage == 2,
                        onClick = { mainPagerState.animateToPage(2) },
                        icon = MiuixIcons.UploadCloud,
                        label = stringResource(Res.string.nav_subscription),
                    )
                    NavigationRailItem(
                        selected = selectedPage == 3,
                        onClick = { mainPagerState.animateToPage(3) },
                        icon = MiuixIcons.Settings,
                        label = stringResource(Res.string.nav_settings),
                    )
                }
                pagerContent(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        // rail 已吸收起始侧的刘海/导航栏 inset；末尾侧无 rail，在此补齐并对后代标记为已消费
                        .consumeWindowInsets(
                            WindowInsets.displayCutout.union(WindowInsets.navigationBars)
                                .only(WindowInsetsSides.Start),
                        )
                        .windowInsetsPadding(
                            WindowInsets.systemBars.union(WindowInsets.displayCutout)
                                .only(WindowInsetsSides.End),
                        ),
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                )
            }
        }
    } else {
        val bottomBarBackdrop = rememberBlurBackdrop(themeConfig.blurEnabled)
        val bottomBarBlurActive = bottomBarBackdrop != null
        val barColor = if (bottomBarBlurActive) Color.Transparent else MiuixTheme.colorScheme.surface
        val floatingBarColor = if (bottomBarBlurActive) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer
        val floatingPillRadius = 50.dp
        val floatingBarShape = RoundedCornerShape(floatingPillRadius)
        val isDark = LocalAppDarkMode.current
        val floatingHighlight = remember(isDark) {
            if (isDark) Highlight.GlassStrokeMiddleDark else Highlight.GlassStrokeMiddleLight
        }
        val floatingBarModifier = if (bottomBarBackdrop != null) {
            Modifier.textureBlur(
                backdrop = bottomBarBackdrop,
                shape = floatingBarShape,
                blurRadius = 25f,
                colors = BlurDefaults.blurColors(
                    blendColors = listOf(
                        BlendColorEntry(
                            color = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f),
                        ),
                    ),
                ),
                highlight = floatingHighlight,
            )
        } else {
            Modifier
        }
        val bottomBarDisplayMode = when (themeConfig.bottomBarMode) {
            BottomBarMode.IconAndText -> NavigationBarDisplayMode.IconAndText
            BottomBarMode.IconOnly -> NavigationBarDisplayMode.IconOnly
        }
        val showBottomBarLabels = themeConfig.bottomBarMode == BottomBarMode.IconAndText
        val navigationItems = listOf(
            NavigationItem(label = stringResource(Res.string.nav_home), icon = MiuixIcons.Home),
            NavigationItem(label = stringResource(Res.string.nav_proxy), icon = MiuixIcons.Tune),
            NavigationItem(label = stringResource(Res.string.nav_subscription), icon = MiuixIcons.UploadCloud),
            NavigationItem(label = stringResource(Res.string.nav_settings), icon = MiuixIcons.Settings),
        )

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                if (themeConfig.floatingBottomBar) {
                    if (themeConfig.floatingBottomBarStyle == FloatingBottomBarStyle.IosLike) {
                        IosLiquidGlassNavigationBar(
                            items = navigationItems,
                            selectedIndex = selectedPage,
                            onItemClick = { index -> mainPagerState.animateToPage(index) },
                            backdrop = bottomBarBackdrop,
                            isBlurActive = bottomBarBlurActive,
                            isDark = isDark,
                            showLabels = showBottomBarLabels,
                        )
                    } else {
                        FloatingNavigationBar(
                            modifier = floatingBarModifier,
                            color = floatingBarColor,
                            cornerRadius = floatingPillRadius,
                        ) {
                            navigationItems.forEachIndexed { index, item ->
                                MiuixFloatingNavigationBarItem(
                                    item = item,
                                    selected = selectedPage == index,
                                    onClick = { mainPagerState.animateToPage(index) },
                                    showLabel = showBottomBarLabels,
                                )
                            }
                        }
                    }
                } else {
                    BlurredBar(backdrop = bottomBarBackdrop, blurActive = bottomBarBlurActive) {
                        NavigationBar(
                            color = barColor,
                            mode = bottomBarDisplayMode,
                        ) {
                            navigationItems.forEachIndexed { index, item ->
                                NavigationBarItem(
                                    selected = selectedPage == index,
                                    onClick = { mainPagerState.animateToPage(index) },
                                    icon = item.icon,
                                    label = item.label,
                                )
                            }
                        }
                    }
                }
            },
        ) { padding ->
            pagerContent(
                if (bottomBarBackdrop != null) {
                    Modifier.fillMaxSize().layerBackdrop(bottomBarBackdrop)
                } else {
                    Modifier.fillMaxSize()
                },
                padding.calculateBottomPadding(),
            )
        }
    }
}

@Composable
private fun MiuixFloatingNavigationBarItem(
    item: NavigationItem,
    selected: Boolean,
    onClick: () -> Unit,
    showLabel: Boolean,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val onSurfaceContainerColor = MiuixTheme.colorScheme.onSurfaceContainer
    val tint = when {
        isPressed -> onSurfaceContainerColor.copy(alpha = if (selected) 0.7f else 0.5f)
        selected -> onSurfaceContainerColor
        else -> onSurfaceContainerColor.copy(alpha = 0.6f)
    }

    Column(
        modifier = modifier
            .defaultMinSize(minWidth = if (showLabel) 56.dp else 48.dp, minHeight = 48.dp)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = interactionSource,
                indication = null,
            )
            .padding(horizontal = if (showLabel) 8.dp else 6.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            modifier = Modifier.size(22.dp),
            imageVector = item.icon,
            contentDescription = if (showLabel) null else item.label,
            tint = tint,
        )
        if (showLabel) {
            Text(
                text = item.label,
                color = tint,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// === MainPagerState（参考 miuix example）===

@Stable
class MainPagerState(
    val pagerState: PagerState,
    private val coroutineScope: CoroutineScope,
) {
    var selectedPage by mutableIntStateOf(pagerState.currentPage)
        private set

    var isNavigating by mutableStateOf(false)
        private set

    private var navJob: Job? = null

    fun animateToPage(targetIndex: Int) {
        if (targetIndex == selectedPage) return

        navJob?.cancel()
        selectedPage = targetIndex
        isNavigating = true

        navJob = coroutineScope.launch {
            val myJob = coroutineContext.job
            try {
                pagerState.scroll(MutatePriority.UserInput) {
                    val distance = abs(targetIndex - pagerState.currentPage).coerceAtLeast(2)
                    val duration = 100 * distance + 100
                    val layoutInfo = pagerState.layoutInfo
                    val pageSize = layoutInfo.pageSize + layoutInfo.pageSpacing
                    val currentDistanceInPages =
                        targetIndex - pagerState.currentPage - pagerState.currentPageOffsetFraction
                    val scrollPixels = currentDistanceInPages * pageSize

                    var previousValue = 0f
                    animate(
                        initialValue = 0f,
                        targetValue = scrollPixels,
                        animationSpec = tween(easing = EaseInOut, durationMillis = duration),
                    ) { currentValue, _ ->
                        previousValue += scrollBy(currentValue - previousValue)
                    }
                }

                if (pagerState.currentPage != targetIndex) {
                    pagerState.scrollToPage(targetIndex)
                }
            } finally {
                if (navJob == myJob) {
                    isNavigating = false
                    if (pagerState.currentPage != targetIndex) {
                        selectedPage = pagerState.currentPage
                    }
                }
            }
        }
    }

    fun syncPage() {
        if (!isNavigating && selectedPage != pagerState.currentPage) {
            selectedPage = pagerState.currentPage
        }
    }
}

@Composable
fun rememberMainPagerState(
    pagerState: PagerState,
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
): MainPagerState = remember(pagerState, coroutineScope) {
    MainPagerState(pagerState, coroutineScope)
}

// === 返回键处理（参考 miuix example）===

@Composable
private fun MainScreenBackHandler(
    mainState: MainPagerState,
    navigator: Navigator,
) {
    val isPagerBackHandlerEnabled by remember {
        derivedStateOf {
            navigator.current() is Route.Main &&
                    navigator.backStackSize() == 1 &&
                    mainState.selectedPage != 0
        }
    }

    val navEventState = rememberNavigationEventState(NavigationEventInfo.None)

    NavigationBackHandler(
        state = navEventState,
        isBackEnabled = isPagerBackHandlerEnabled,
        onBackCompleted = {
            mainState.animateToPage(0)
        },
    )
}
