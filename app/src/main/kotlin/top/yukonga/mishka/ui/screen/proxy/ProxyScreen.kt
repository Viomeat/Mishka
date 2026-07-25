package top.yukonga.mishka.ui.screen.proxy

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.launch
import top.yukonga.mishka.R
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.CardSegment
import top.yukonga.mishka.ui.component.ListPopupDefaults.MenuPositionProvider
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.platform.IconLoader
import top.yukonga.mishka.ui.theme.StatusColors
import top.yukonga.mishka.ui.util.WideContentBox
import top.yukonga.mishka.viewmodel.ProxyGroupUi
import top.yukonga.mishka.viewmodel.ProxyUiState
import top.yukonga.mishka.viewmodel.ProxyViewModel
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowListPopup

@Composable
fun ProxyScreen(
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
    viewModel: ProxyViewModel? = null,
) {
    val uiState = viewModel?.uiState?.collectAsStateWithLifecycle()?.value ?: ProxyUiState()
    val sortOption = viewModel?.sortOption?.collectAsStateWithLifecycle()?.value ?: 0
    val fullWidthNodes = viewModel?.fullWidthNodes?.collectAsStateWithLifecycle()?.value == true
    val scrollBehavior = MiuixScrollBehavior()
    val groups = uiState.groups

    val showPopup = remember { mutableStateOf(false) }
    val showSortPopup = remember { mutableStateOf(false) }
    var iconCacheVersion by remember { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()

    // 展开状态上提屏幕级：节点行是顶层 lazy item，存 item 内会随其销毁丢失，故用可保存的 SnapshotStateList 统一持有
    val expandedGroups = rememberSaveable(
        saver = listSaver(
            save = { it.toList() },
            restore = { it.toMutableStateList() },
        ),
    ) { mutableStateListOf<String>() }

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    Scaffold(
        modifier = modifier,
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = stringResource(R.string.proxy_title),
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        if (groups.isNotEmpty()) {
                            Box {
                                IconButton(
                                    onClick = { showSortPopup.value = true },
                                    holdDownState = showSortPopup.value,
                                ) {
                                    Icon(
                                        imageVector = MiuixIcons.Sort,
                                        contentDescription = stringResource(R.string.proxy_sort_title),
                                        tint = MiuixTheme.colorScheme.onSurface,
                                    )
                                }

                                WindowListPopup(
                                    show = showSortPopup.value,
                                    popupPositionProvider = MenuPositionProvider,
                                    alignment = PopupPositionProvider.Align.TopEnd,
                                    onDismissRequest = { showSortPopup.value = false },
                                ) {
                                    ListPopupColumn {
                                        val sortResIds = listOf(
                                            R.string.proxy_sort_default,
                                            R.string.proxy_sort_name,
                                            R.string.proxy_sort_delay,
                                        )
                                        val currentKey = sortOption / 2
                                        val isReverse = sortOption % 2 != 0
                                        val groupSize = sortResIds.size + 1

                                        sortResIds.forEachIndexed { index, resId ->
                                            DropdownImpl(
                                                text = stringResource(resId),
                                                optionSize = groupSize,
                                                isSelected = currentKey == index,
                                                index = index,
                                                onSelectedIndexChange = {
                                                    viewModel?.updateSortOption(
                                                        index * 2 + if (isReverse) 1 else 0
                                                    )
                                                    showSortPopup.value = false
                                                },
                                            )
                                        }
                                        HorizontalDivider(
                                            modifier = Modifier
                                                .padding(horizontal = 20.dp, vertical = 4.dp),
                                            thickness = 1.5.dp,
                                        )
                                        DropdownImpl(
                                            text = stringResource(R.string.proxy_sort_reverse),
                                            optionSize = groupSize,
                                            isSelected = isReverse,
                                            index = sortResIds.size,
                                            onSelectedIndexChange = {
                                                viewModel?.updateSortOption(
                                                    currentKey * 2 + if (!isReverse) 1 else 0
                                                )
                                                showSortPopup.value = false
                                            },
                                        )
                                    }
                                }
                            }

                            Box {
                                IconButton(
                                    onClick = { showPopup.value = true },
                                    holdDownState = showPopup.value,
                                ) {
                                    Icon(
                                        imageVector = MiuixIcons.More,
                                        contentDescription = stringResource(R.string.common_more),
                                        tint = MiuixTheme.colorScheme.onSurface,
                                    )
                                }

                                WindowListPopup(
                                    show = showPopup.value,
                                    popupPositionProvider = MenuPositionProvider,
                                    alignment = PopupPositionProvider.Align.TopEnd,
                                    onDismissRequest = { showPopup.value = false },
                                ) {
                                    ListPopupColumn {
                                        DropdownImpl(
                                            text = stringResource(R.string.proxy_node_full_width),
                                            optionSize = 2,
                                            isSelected = fullWidthNodes,
                                            index = 0,
                                            onSelectedIndexChange = {
                                                viewModel?.updateFullWidthNodes(!fullWidthNodes)
                                                showPopup.value = false
                                            },
                                        )
                                        DropdownImpl(
                                            text = stringResource(R.string.proxy_refresh_icon),
                                            optionSize = 2,
                                            isSelected = false,
                                            index = 1,
                                            onSelectedIndexChange = {
                                                coroutineScope.launch { IconLoader.clear() }
                                                iconCacheVersion++
                                                showPopup.value = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        WideContentBox(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) { sidePadding ->
            if (groups.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = innerPadding.calculateTopPadding(), bottom = bottomPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = stringResource(R.string.proxy_no_groups),
                        fontSize = 16.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = stringResource(R.string.proxy_start_first),
                        modifier = Modifier.padding(top = 6.dp),
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .scrollEndHaptic()
                        .overScrollVertical()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(
                        top = innerPadding.calculateTopPadding(),
                        bottom = bottomPadding,
                        start = sidePadding,
                        end = sidePadding,
                    ),
                ) {
                    // 每组展平为「组头段 + 每行节点段」独立 lazy item；展开时只组合可见节点行，避免一次性组合整组造成卡顿
                    groups.forEach { group ->
                        val isExpanded = group.name in expandedGroups
                        val rows = if (isExpanded) {
                            sortNodes(group.all, group.delays, sortOption)
                                .chunked(if (fullWidthNodes) 1 else 2)
                        } else {
                            emptyList()
                        }

                        item(key = "group:${group.name}", contentType = "proxy_group_header") {
                            // 组头底角随展开在 16↔0 间动画，避免 isLast 随 rows 翻转导致圆角突变
                            val headerBottomCorner by animateDpAsState(
                                targetValue = if (rows.isEmpty()) 16.dp else 0.dp,
                                animationSpec = tween(300),
                                label = "groupHeaderBottomCorner",
                            )
                            CardSegment(
                                isFirst = true,
                                isLast = rows.isEmpty(),
                                modifier = Modifier.animateItem(),
                                bottomCornerRadius = headerBottomCorner,
                                outerTopPadding = 12.dp,
                            ) {
                                ProxyGroupHeader(
                                    group = group,
                                    isExpanded = isExpanded,
                                    iconCacheVersion = iconCacheVersion,
                                    isTesting = group.name in uiState.testingGroups,
                                    onTestDelay = { viewModel?.testGroupDelay(group.name) },
                                    onToggle = {
                                        if (isExpanded) expandedGroups.remove(group.name)
                                        else expandedGroups.add(group.name)
                                    },
                                )
                            }
                        }

                        if (rows.isNotEmpty()) {
                            val lastRowIndex = rows.lastIndex
                            rows.forEachIndexed { rowIndex, row ->
                                item(
                                    key = "nodes:${group.name}:$rowIndex",
                                    contentType = "proxy_node_row",
                                ) {
                                    CardSegment(
                                        isFirst = false,
                                        isLast = rowIndex == lastRowIndex,
                                        modifier = Modifier.animateItem(),
                                        insidePadding = PaddingValues(
                                            start = 12.dp,
                                            end = 12.dp,
                                            bottom = 12.dp,
                                        ),
                                    ) {
                                        ProxyNodeRow(
                                            row = row,
                                            group = group,
                                            fillRow = fullWidthNodes,
                                            testingNodes = uiState.testingNodes,
                                            onTestNodeDelay = { nodeName ->
                                                viewModel?.testNodeDelay(nodeName)
                                            },
                                            onSelect = { proxyName ->
                                                if (group.type.lowercase() == "selector") {
                                                    viewModel?.selectProxy(group.name, proxyName)
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item(key = "bottom_spacer") {
                        Spacer(Modifier.padding(bottom = 12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProxyGroupHeader(
    group: ProxyGroupUi,
    isExpanded: Boolean,
    iconCacheVersion: Int,
    isTesting: Boolean,
    onTestDelay: () -> Unit,
    onToggle: () -> Unit,
) {
    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = tween(300),
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧图标
        GroupIcon(
            icon = group.icon,
            name = group.name,
            cacheVersion = iconCacheVersion,
        )

        Spacer(Modifier.width(12.dp))

        // 中间：组名 + 当前节点
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = group.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (group.now.isNotEmpty()) {
                Text(
                    text = group.now,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 右侧：当前节点延迟 + 节点数 + 箭头
        val nowDelay = group.delays[group.now]
        if (nowDelay != null) {
            val timeoutText = stringResource(R.string.proxy_timeout)
            val delayText = if (nowDelay < 0) timeoutText else "${nowDelay}ms"
            Text(
                text = delayText,
                fontSize = 12.sp,
                color = StatusColors.delay(nowDelay),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = "${group.all.size}",
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = onTestDelay,
            enabled = !isTesting,
            modifier = Modifier.size(24.dp),
        ) {
            if (isTesting) {
                CircularProgressIndicator(
                    size = 14.dp,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = MiuixIcons.Refresh,
                    contentDescription = stringResource(R.string.proxy_test_group_delay),
                    modifier = Modifier.size(16.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        val layoutDirection = LocalLayoutDirection.current
        Image(
            imageVector = MiuixIcons.Basic.ArrowRight,
            contentDescription = null,
            modifier = Modifier
                .size(width = 10.dp, height = 16.dp)
                .graphicsLayer {
                    scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
                }
                .rotate(rotation),
            colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary),
        )
    }
}

@Composable
private fun GroupIcon(
    icon: String,
    name: String,
    cacheVersion: Int,
) {
    if (icon.isNotEmpty()) {
        var bitmap by remember(icon, cacheVersion) { mutableStateOf<ImageBitmap?>(null) }

        LaunchedEffect(icon, cacheVersion) {
            bitmap = IconLoader.loadIcon(icon)
        }

        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current,
                contentDescription = name,
                modifier = Modifier
                    .size(36.dp)
                    .squircleClip(8.dp),
            )
        } else {
            DefaultGroupIcon(name)
        }
    } else {
        DefaultGroupIcon(name)
    }
}

@Composable
private fun DefaultGroupIcon(name: String) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .squircleBackground(MiuixTheme.colorScheme.primary.copy(alpha = 0.1f), 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (name.isEmpty()) "" else {
                val first = name[0]
                if (first.isHighSurrogate() && name.length > 1 && name[1].isLowSurrogate()) {
                    name.substring(0, 2)
                } else {
                    first.toString()
                }
            },
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.primary.copy(0.8f),
        )
    }
}

// 一行 ≤2 个节点（fillRow 时每行 1 个铺满宽度），是节点网格的独立 lazy item 单元；
// 排序/分行在 LazyColumn 内容 lambda 完成
@Composable
private fun ProxyNodeRow(
    row: List<String>,
    group: ProxyGroupUi,
    fillRow: Boolean = false,
    testingNodes: ImmutableSet<String> = persistentSetOf(),
    onTestNodeDelay: (String) -> Unit = {},
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        row.forEach { proxyName ->
            val isSelected = proxyName == group.now
            val delay = group.delays[proxyName]
            val nodeType = group.nodeTypes[proxyName] ?: ""
            val isSelectable = group.type.lowercase() == "selector"

            ProxyNodeCard(
                name = proxyName,
                type = nodeType,
                delay = delay,
                isSelected = isSelected,
                isSelectable = isSelectable,
                isTesting = proxyName in testingNodes,
                onTestDelay = { onTestNodeDelay(proxyName) },
                onClick = { onSelect(proxyName) },
                modifier = Modifier.weight(1f),
            )
        }
        // 两列排布下最后一行只有 1 个节点时补空位，避免它被拉伸成整行宽
        if (!fillRow && row.size == 1) {
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ProxyNodeCard(
    name: String,
    type: String,
    delay: Int?,
    isSelected: Boolean,
    isSelectable: Boolean,
    isTesting: Boolean = false,
    onTestDelay: () -> Unit = {},
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val timeoutStr = stringResource(R.string.proxy_timeout)
    val delayText = when {
        delay == null -> null
        delay < 0 -> timeoutStr
        else -> "$delay"
    }
    val delayColor = StatusColors.delay(delay)
    val testNodeDelayLabel = stringResource(R.string.proxy_test_node_delay)

    val backgroundColor = if (isSelected) {
        StatusColors.selectedNodeContainer
    } else {
        MiuixTheme.colorScheme.surface
    }

    Box(
        modifier = modifier
            .then(
                if (isSelectable) {
                    Modifier
                        .squircleSurface(color = backgroundColor, cornerRadius = 12.dp)
                        .clickable(onClick = onClick)
                } else {
                    Modifier.squircleBackground(color = backgroundColor, cornerRadius = 12.dp)
                }
            )
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
    ) {
        Column {
            // 第一行：节点名 + 延迟区域
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 节点名不截断：超出可用宽度时走跑马灯滚动，短名保持原宽（fill = false）
                Text(
                    text = name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .basicMarquee(iterations = Int.MAX_VALUE),
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = 28.dp)
                        .clickable(
                            enabled = !isTesting,
                            interactionSource = null,
                            indication = null,
                            onClickLabel = testNodeDelayLabel,
                        ) { onTestDelay() },
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    // 测试中 / 已测显示延迟 / 未测显示刷新图标，三态互斥
                    // 行高由左侧节点名（13.sp）主导，切换不抖动
                    when {
                        isTesting -> CircularProgressIndicator(
                            size = 12.dp,
                            strokeWidth = 2.dp,
                        )

                        delayText != null -> Text(
                            text = delayText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = delayColor,
                        )

                        else -> Icon(
                            imageVector = MiuixIcons.Refresh,
                            contentDescription = testNodeDelayLabel,
                            modifier = Modifier.size(14.dp),
                            tint = StatusColors.neutral,
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // 第二行：协议类型 Badge
            if (type.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = type,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

// 节点排序：sortOption 编码 = sortKeyIndex * 2 + (if reverse 1 else 0)
// 0/1=默认 升/降，2/3=名称 升/降，4/5=延迟 升/降
// 延迟排序时超时 (-1) 与未测 (null) 永远沉底，倒序也只翻转已测部分
private fun sortNodes(
    names: List<String>,
    delays: Map<String, Int>,
    sortOption: Int,
): List<String> {
    val key = sortOption / 2
    val reverse = sortOption % 2 != 0
    return when (key) {
        1 -> {
            val sorted = names.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
            if (reverse) sorted.reversed() else sorted
        }

        2 -> {
            val (valid, invalid) = names.partition {
                val d = delays[it]
                d != null && d > 0
            }
            val sortedValid = valid.sortedBy { delays[it] ?: Int.MAX_VALUE }
            val finalValid = if (reverse) sortedValid.reversed() else sortedValid
            finalValid + invalid
        }

        else -> if (reverse) names.reversed() else names
    }
}
