package top.yukonga.mishka.ui.screen.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import mishka.shared.generated.resources.Res
import mishka.shared.generated.resources.external_control_title
import mishka.shared.generated.resources.root_settings_summary
import mishka.shared.generated.resources.root_settings_title
import mishka.shared.generated.resources.settings_about
import mishka.shared.generated.resources.settings_app_proxy
import mishka.shared.generated.resources.settings_app_proxy_summary
import mishka.shared.generated.resources.settings_auto_restart
import mishka.shared.generated.resources.settings_auto_restart_summary
import mishka.shared.generated.resources.settings_dynamic_notification
import mishka.shared.generated.resources.settings_dynamic_notification_summary
import mishka.shared.generated.resources.settings_dynamic_notification_summary_root_unsupported
import mishka.shared.generated.resources.settings_external_control_summary
import mishka.shared.generated.resources.settings_file_manager
import mishka.shared.generated.resources.settings_file_manager_summary
import mishka.shared.generated.resources.settings_general
import mishka.shared.generated.resources.settings_hide_task_card
import mishka.shared.generated.resources.settings_hide_task_card_summary
import mishka.shared.generated.resources.settings_meta_settings
import mishka.shared.generated.resources.settings_meta_summary
import mishka.shared.generated.resources.settings_network
import mishka.shared.generated.resources.settings_override_settings
import mishka.shared.generated.resources.settings_override_summary
import mishka.shared.generated.resources.settings_subscription_via_proxy
import mishka.shared.generated.resources.settings_subscription_via_proxy_summary
import mishka.shared.generated.resources.settings_theme_summary
import mishka.shared.generated.resources.settings_theme_title
import mishka.shared.generated.resources.settings_title
import mishka.shared.generated.resources.settings_tun_mode
import mishka.shared.generated.resources.settings_tun_mode_root_tproxy
import mishka.shared.generated.resources.settings_tun_mode_root_tun
import mishka.shared.generated.resources.settings_tun_mode_vpn
import mishka.shared.generated.resources.settings_tun_root_tproxy_summary
import mishka.shared.generated.resources.settings_tun_root_tun_summary
import mishka.shared.generated.resources.settings_tun_vpn_summary
import mishka.shared.generated.resources.settings_vpn_settings
import mishka.shared.generated.resources.settings_vpn_summary
import mishka.shared.generated.resources.settings_wifi_policy
import mishka.shared.generated.resources.settings_wifi_policy_summary
import org.jetbrains.compose.resources.stringResource
import top.yukonga.mishka.platform.BootStartManager
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.ProxyServiceBridge
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.CardItem
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.component.groupedCardItems
import top.yukonga.mishka.ui.util.WideContentBox
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
    onNavigateVpnSettings: () -> Unit = {},
    onNavigateRootSettings: () -> Unit = {},
    onNavigateNetworkSettings: () -> Unit = {},
    onNavigateMetaSettings: () -> Unit = {},
    onNavigateExternalControl: () -> Unit = {},
    onNavigateAppProxy: () -> Unit = {},
    onNavigateWifiPolicy: () -> Unit = {},
    onNavigateThemeSettings: () -> Unit = {},
    onNavigateFileManager: () -> Unit = {},
    onNavigateAbout: () -> Unit = {},
    bootStartManager: BootStartManager? = null,
    storage: PlatformStorage? = null,
    onHideTaskCardChange: ((Boolean) -> Unit)? = null,
    hasRootPermission: Boolean = false,
    isProxyRunning: Boolean = false,
) {
    val scrollBehavior = MiuixScrollBehavior()
    var isAutoStartEnabled by remember {
        mutableStateOf(bootStartManager?.isEnabled() ?: false)
    }
    var isDynamicNotificationEnabled by remember {
        mutableStateOf(storage?.getString(StorageKeys.DYNAMIC_NOTIFICATION, "true") != "false")
    }
    var isUpdateViaProxyEnabled by remember {
        mutableStateOf(storage?.getString(StorageKeys.SUBSCRIPTION_UPDATE_VIA_PROXY, "true") != "false")
    }
    var isHideTaskCardEnabled by remember {
        mutableStateOf(storage?.getString(StorageKeys.HIDE_TASK_CARD, "false") == "true")
    }
    var tunModeIndex by remember {
        mutableIntStateOf(
            when (storage?.getString(StorageKeys.TUN_MODE, "vpn")) {
                "root_tun" -> 1
                "root_tproxy" -> 2
                else -> 0
            }
        )
    }

    val tunModeItems = listOf(
        stringResource(Res.string.settings_tun_mode_vpn),
        stringResource(Res.string.settings_tun_mode_root_tun),
        stringResource(Res.string.settings_tun_mode_root_tproxy),
    )

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    Scaffold(
        modifier = modifier,
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = stringResource(Res.string.settings_title),
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { innerPadding ->
        WideContentBox { sidePadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    start = sidePadding,
                    end = sidePadding,
                    bottom = bottomPadding,
                ),
            ) {
                item {
                    SmallTitle(text = stringResource(Res.string.settings_network))
                }
                groupedCardItems(
                    keyPrefix = "settings_network",
                    items = buildList {
                        if (hasRootPermission) {
                            add(CardItem("tunMode") {
                                OverlayDropdownPreference(
                                    title = stringResource(Res.string.settings_tun_mode),
                                    summary = when (tunModeIndex) {
                                        1 -> stringResource(Res.string.settings_tun_root_tun_summary)
                                        2 -> stringResource(Res.string.settings_tun_root_tproxy_summary)
                                        else -> stringResource(Res.string.settings_tun_vpn_summary)
                                    },
                                    items = tunModeItems,
                                    selectedIndex = tunModeIndex,
                                    onSelectedIndexChange = { index ->
                                        val mode = when (index) {
                                            1 -> "root_tun"
                                            2 -> "root_tproxy"
                                            else -> "vpn"
                                        }
                                        storage?.putString(StorageKeys.TUN_MODE, mode)
                                        tunModeIndex = index
                                    },
                                    enabled = !isProxyRunning,
                                )
                            })
                        }
                        if (tunModeIndex == 0) {
                            add(CardItem("vpnSettings") {
                                ArrowPreference(
                                    title = stringResource(Res.string.settings_vpn_settings),
                                    summary = stringResource(Res.string.settings_vpn_summary),
                                    onClick = onNavigateVpnSettings,
                                )
                            })
                        }
                        if (tunModeIndex == 1 || tunModeIndex == 2) {
                            add(CardItem("rootSettings") {
                                ArrowPreference(
                                    title = stringResource(Res.string.root_settings_title),
                                    summary = stringResource(Res.string.root_settings_summary),
                                    onClick = onNavigateRootSettings,
                                )
                            })
                        }
                        add(CardItem("override") {
                            ArrowPreference(
                                title = stringResource(Res.string.settings_override_settings),
                                summary = stringResource(Res.string.settings_override_summary),
                                onClick = onNavigateNetworkSettings,
                            )
                        })
                        add(CardItem("meta") {
                            ArrowPreference(
                                title = stringResource(Res.string.settings_meta_settings),
                                summary = stringResource(Res.string.settings_meta_summary),
                                onClick = onNavigateMetaSettings,
                            )
                        })
                        add(CardItem("externalControl") {
                            ArrowPreference(
                                title = stringResource(Res.string.external_control_title),
                                summary = stringResource(Res.string.settings_external_control_summary),
                                onClick = onNavigateExternalControl,
                            )
                        })
                        add(CardItem("appProxy") {
                            ArrowPreference(
                                title = stringResource(Res.string.settings_app_proxy),
                                summary = stringResource(Res.string.settings_app_proxy_summary),
                                onClick = onNavigateAppProxy,
                            )
                        })
                        add(CardItem("wifiPolicy") {
                            ArrowPreference(
                                title = stringResource(Res.string.settings_wifi_policy),
                                summary = stringResource(Res.string.settings_wifi_policy_summary),
                                onClick = onNavigateWifiPolicy,
                            )
                        })
                        add(CardItem("fileManager") {
                            ArrowPreference(
                                title = stringResource(Res.string.settings_file_manager),
                                summary = stringResource(Res.string.settings_file_manager_summary),
                                onClick = onNavigateFileManager,
                            )
                        })
                    },
                )
                item {
                    SmallTitle(text = stringResource(Res.string.settings_general))
                }
                groupedCardItems(
                    keyPrefix = "settings_general",
                    outerBottomPadding = 12.dp,
                    items = buildList {
                        if (onHideTaskCardChange != null) {
                            add(CardItem("hideTaskCard") {
                                SwitchPreference(
                                    title = stringResource(Res.string.settings_hide_task_card),
                                    summary = stringResource(Res.string.settings_hide_task_card_summary),
                                    checked = isHideTaskCardEnabled,
                                    onCheckedChange = { checked ->
                                        storage?.putString(StorageKeys.HIDE_TASK_CARD, if (checked) "true" else "false")
                                        isHideTaskCardEnabled = checked
                                        onHideTaskCardChange(checked)
                                    },
                                )
                            })
                        }
                        add(CardItem("dynamicNotification") {
                            val isVpnMode = tunModeIndex == 0
                            SwitchPreference(
                                title = stringResource(Res.string.settings_dynamic_notification),
                                summary = stringResource(
                                    if (isVpnMode) Res.string.settings_dynamic_notification_summary
                                    else Res.string.settings_dynamic_notification_summary_root_unsupported
                                ),
                                checked = isDynamicNotificationEnabled && isVpnMode,
                                enabled = isVpnMode,
                                onCheckedChange = { checked ->
                                    storage?.putString(StorageKeys.DYNAMIC_NOTIFICATION, if (checked) "true" else "false")
                                    isDynamicNotificationEnabled = checked
                                    ProxyServiceBridge.requestNotificationRefresh()
                                },
                            )
                        })
                        add(CardItem("subscriptionViaProxy") {
                            SwitchPreference(
                                title = stringResource(Res.string.settings_subscription_via_proxy),
                                summary = stringResource(Res.string.settings_subscription_via_proxy_summary),
                                checked = isUpdateViaProxyEnabled,
                                onCheckedChange = { checked ->
                                    storage?.putString(StorageKeys.SUBSCRIPTION_UPDATE_VIA_PROXY, if (checked) "true" else "false")
                                    isUpdateViaProxyEnabled = checked
                                },
                            )
                        })
                        if (bootStartManager != null) {
                            add(CardItem("autoRestart") {
                                SwitchPreference(
                                    title = stringResource(Res.string.settings_auto_restart),
                                    summary = stringResource(Res.string.settings_auto_restart_summary),
                                    checked = isAutoStartEnabled,
                                    onCheckedChange = { checked ->
                                        bootStartManager.setEnabled(checked)
                                        isAutoStartEnabled = checked
                                    },
                                )
                            })
                        }
                        add(CardItem("theme") {
                            ArrowPreference(
                                title = stringResource(Res.string.settings_theme_title),
                                summary = stringResource(Res.string.settings_theme_summary),
                                onClick = onNavigateThemeSettings,
                            )
                        })
                        add(CardItem("about") {
                            ArrowPreference(
                                title = stringResource(Res.string.settings_about),
                                summary = "Mishka v${misc.VersionInfo.VERSION_NAME}",
                                onClick = onNavigateAbout,
                            )
                        })
                    },
                )
            }
        }
    }
}
