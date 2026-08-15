@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.example.composedtv.ui.components

import com.example.composedtv.data.remote.CountryLangMapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.composedtv.viewmodel.CategoryEntry
import com.example.composedtv.viewmodel.ChannelEntry
import com.example.composedtv.viewmodel.SidePanelData
import kotlinx.coroutines.delay

/** 侧边栏自动隐藏超时（毫秒）：无操作 15 秒后自动收起（选台需要足够时间浏览） */
private const val SIDE_PANEL_TIMEOUT_MS = 15_000L

/**
 * 左侧三排目录面板：源列表、类别列表、频道列表
 *
 * 布局：三列并排，左→右依次是 源 → 类别 → 频道。
 * 焦点默认在频道列（最常用）。D-pad 左右可在三列间切换。
 *
 * @param onAutoHide 无操作超时时回调（调用方负责将 isVisible 置为 false）
 */
@Composable
fun SidePanel(
    data: SidePanelData,
    isVisible: Boolean,
    onSourceSelected: (Int) -> Unit,
    onCategorySelected: (Int) -> Unit,
    onChannelSelected: (ChannelEntry) -> Unit,
    onAutoHide: () -> Unit = {},
    onSearchQueryChange: (String) -> Unit = {},
    onExitSearch: () -> Unit = {}
) {
    val channelFocusRequester = remember { FocusRequester() }
    // 分类列（第二列）焦点入口：其顶部固定区包含「收藏 / 搜索」，
    // 面板打开时把焦点先放到此处，确保遥控能选中收藏 / 搜索（否则它们不在 LazyColumn 焦点链内无法到达）
    val categoryFocusRequester = remember { FocusRequester() }

    // 三个列表的滚动状态提到顶层：用于检测「正在滚动」也算用户活动
    val sourceListState = rememberLazyListState()
    val categoryListState = rememberLazyListState()
    val channelListState = rememberLazyListState()

    // ===== 无操作超时自动隐藏 =====
    // 记录「最后一次用户活动」时间戳；每次点击/焦点/选中/滚动时 bump 一下；
    // 当 isVisible=true 时启动定时器，比较「现在 - 最后活动」是否超过阈值。
    var lastActiveAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val bumpActivity: () -> Unit = { lastActiveAt = System.currentTimeMillis() }
    val onAutoHideState by rememberUpdatedState(onAutoHide)

    LaunchedEffect(isVisible) {
        if (!isVisible) return@LaunchedEffect
        // 打开面板时先 bump 一次，避免立即被判定为「无操作」
        bumpActivity()
        // 记录三列上次的可见位置；位置变化即视为用户活动
        // （isScrollInProgress 在 fling 间隙/手指停顿时会短暂变 false，不可靠；
        //  用位置变化能覆盖触屏拖动、fling、D-pad 触发的滚动等所有场景）
        var lastSrcIdx = sourceListState.firstVisibleItemIndex
        var lastSrcOff = sourceListState.firstVisibleItemScrollOffset
        var lastCatIdx = categoryListState.firstVisibleItemIndex
        var lastCatOff = categoryListState.firstVisibleItemScrollOffset
        var lastChIdx = channelListState.firstVisibleItemIndex
        var lastChOff = channelListState.firstVisibleItemScrollOffset
        // 循环检查：每 300ms 看一次是否超时（缩短间隔，更快捕获滚动）
        while (true) {
            delay(300)
            val srcChanged = sourceListState.firstVisibleItemIndex != lastSrcIdx ||
                sourceListState.firstVisibleItemScrollOffset != lastSrcOff
            val catChanged = categoryListState.firstVisibleItemIndex != lastCatIdx ||
                categoryListState.firstVisibleItemScrollOffset != lastCatOff
            val chChanged = channelListState.firstVisibleItemIndex != lastChIdx ||
                channelListState.firstVisibleItemScrollOffset != lastChOff
            if (srcChanged || catChanged || chChanged ||
                sourceListState.isScrollInProgress ||
                categoryListState.isScrollInProgress ||
                channelListState.isScrollInProgress
            ) {
                bumpActivity()
            }
            lastSrcIdx = sourceListState.firstVisibleItemIndex
            lastSrcOff = sourceListState.firstVisibleItemScrollOffset
            lastCatIdx = categoryListState.firstVisibleItemIndex
            lastCatOff = categoryListState.firstVisibleItemScrollOffset
            lastChIdx = channelListState.firstVisibleItemIndex
            lastChOff = channelListState.firstVisibleItemScrollOffset
            val idle = System.currentTimeMillis() - lastActiveAt
            if (idle >= SIDE_PANEL_TIMEOUT_MS) {
                onAutoHideState()
                break
            }
        }
    }

    // 面板打开时将焦点移到频道列表
    LaunchedEffect(isVisible) {
        if (isVisible) {
            delay(100)
            // 焦点先落在分类列（收藏 / 搜索所在列），确保遥控可选中它们；
            // 频道列表的滚动定位由下方 LaunchedEffect 负责（仅滚动，不抢焦点）
            runCatching { categoryFocusRequester.requestFocus() }
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut()
    ) {
        // 外层：圆角 + 边框（玻璃边缘高光）
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(min = 300.dp, max = 440.dp)
                // 毛玻璃感：半透明深色底（alpha ~73%）+ 微白色高光边框 + 圆角
                .background(
                    color = Color(0xBB1A1C23),
                    shape = RoundedCornerShape(0.dp, 16.dp, 16.dp, 0.dp)
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0x40FFFFFF),
                            Color(0x20FFFFFF),
                            Color(0x10FFFFFF)
                        )
                    ),
                    shape = RoundedCornerShape(0.dp, 16.dp, 16.dp, 0.dp)
                )
        ) {
            // 右侧渐隐过渡层（柔化边缘，增加玻璃层次）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.horizontalGradient(
                            endX = 24f,
                            colors = listOf(
                                Color.Transparent,
                                Color(0x10FFFFFF)
                            )
                        )
                    )
            )

            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 第一列：源列表
                LaunchedEffect(isVisible, data.selectedSourceIndex) {
                    if (isVisible && data.sources.isNotEmpty()) {
                        sourceListState.scrollCenteredTo(
                            index = data.selectedSourceIndex.coerceIn(0, data.sources.lastIndex)
                        )
                    }
                }
                PanelColumn(
                    title = "直播源",
                    icon = Icons.Default.PlayArrow,
                    modifier = Modifier.weight(0.85f)
                ) {
                    LazyColumn(
                        state = sourceListState,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(data.sources) { source ->
                            val index = data.sources.indexOf(source)
                            PanelItem(
                                text = source.name,
                                isSelected = index == data.selectedSourceIndex,
                                onClick = {
                                    bumpActivity()
                                    onSourceSelected(index)
                                },
                                onFocused = { bumpActivity() }
                            )
                        }
                    }
                }

                // 第二列：类别列表（搜索/收藏固定置顶，普通分类滚动）
                // 滚动定位：把全列表 selectedCategoryIndex 映射到普通分类列表内的索引
                val normalCategories = remember(data.categories) {
                    data.categories.mapIndexed { idx, c -> idx to c }
                        .filter { !it.second.isSearch && !it.second.isFavorites }
                }
                LaunchedEffect(isVisible, data.selectedCategoryIndex) {
                    if (isVisible && normalCategories.isNotEmpty()) {
                        val selected = data.selectedCategoryIndex
                        // 全列表索引 → 普通分类列表内的索引
                        val targetInNormal = normalCategories.indexOfFirst { it.first == selected }
                        if (targetInNormal >= 0) {
                            categoryListState.scrollCenteredTo(targetInNormal)
                        }
                    }
                }
                PanelColumn(
                    title = "分类",
                    icon = Icons.Default.Star,
                    modifier = Modifier.weight(0.75f)
                ) {
                    if (data.isLoadingCategories && normalCategories.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(28.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else {
                        // 分类列拆分：搜索/收藏固定置顶，普通分类在下方 LazyColumn 滚动
                        Column {
                            // 顶部固定区：搜索 + 收藏（不随列表滚动）
                            // 注意：这里不是 LazyColumn，普通 clickable 的 Surface 不可聚焦，
                            // 必须显式加 focusable 才能被遥控器选中。
                            data.categories.forEachIndexed { index, category ->
                                if (category.isSearch || category.isFavorites) {
                                    PanelItem(
                                        modifier = if (category.isSearch) {
                                            Modifier.focusable().focusRequester(categoryFocusRequester)
                                        } else {
                                            Modifier.focusable()
                                        },
                                        text = category.name,
                                        isSelected = index == data.selectedCategoryIndex,
                                        icon = when {
                                            category.isSearch -> Icons.Default.Search
                                            category.isFavorites -> Icons.Default.Star
                                            else -> null
                                        },
                                        iconTint = MaterialTheme.colorScheme.secondary,
                                        onClick = {
                                            bumpActivity()
                                            onCategorySelected(index)
                                        },
                                        onFocused = { bumpActivity() }
                                    )
                                }
                            }

                            // 普通分类区：可滚动
                            LazyColumn(
                                state = categoryListState,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                contentPadding = PaddingValues(vertical = 4.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(items = normalCategories) { (index, category) ->
                                    PanelItem(
                                        text = category.name,
                                        isSelected = index == data.selectedCategoryIndex,
                                        icon = null,
                                        iconTint = MaterialTheme.colorScheme.secondary,
                                        onClick = {
                                            bumpActivity()
                                            onCategorySelected(index)
                                        },
                                        onFocused = { bumpActivity() }
                                    )
                                }
                            }
                        }
                    }
                }

                // 第三列：频道列表 / 搜索结果
                if (data.isSearchMode) {
                    // 搜索模式：顶部搜索框 + 结果列表
                    SearchColumn(
                        modifier = Modifier.weight(1f),
                        query = data.searchQuery,
                        results = data.searchResults,
                        selectedIndex = data.selectedChannelIndex,
                        listState = channelListState,
                        focusRequester = channelFocusRequester,
                        onQueryChange = {
                            bumpActivity()
                            onSearchQueryChange(it)
                        },
                        onChannelSelected = { ch ->
                            bumpActivity()
                            onChannelSelected(ch)
                        },
                        onExitSearch = {
                            bumpActivity()
                            onExitSearch()
                        }
                    )
                } else {
                    LaunchedEffect(isVisible, data.channels, data.selectedChannelIndex) {
                        if (isVisible && data.channels.isNotEmpty()) {
                            val idx = data.selectedChannelIndex.coerceIn(0, data.channels.lastIndex)
                            // 先滚动到居中；延迟一小段再聚焦（等滚动完成）
                            channelListState.scrollCenteredTo(idx)
                            delay(120)
                            runCatching { channelFocusRequester.requestFocus() }
                        }
                    }
                    PanelColumn(
                        title = "频道",
                        icon = Icons.Default.PlayArrow,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (data.isLoadingChannels && data.channels.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(36.dp),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else {
                            LazyColumn(
                                state = channelListState,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                contentPadding = PaddingValues(vertical = 4.dp),
                                modifier = Modifier.focusRequester(channelFocusRequester)
                            ) {
                                items(data.channels) { channel ->
                                    val index = data.channels.indexOf(channel)
                                    PanelItem(
                                        text = channel.name,
                                        isSelected = index == data.selectedChannelIndex,
                                        icon = if (channel.isFavorite) Icons.Default.Star else null,
                                        iconTint = MaterialTheme.colorScheme.secondary,
                                        countryText = CountryLangMapper.countryCn(channel.countryAttr),
                                        langText = CountryLangMapper.langsCn(channel.langs),
                                        onClick = {
                                            bumpActivity()
                                            onChannelSelected(channel)
                                        },
                                        onFocused = { bumpActivity() }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelColumn(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier.fillMaxHeight()
    ) {
        // 列标题
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp, start = 4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.width(16.dp).height(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        // 列内容
        Box(modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}

@Composable
private fun PanelItem(
    text: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurface,
    countryText: String = "",
    langText: String = "",
    onClick: () -> Unit,
    onFocused: () -> Unit = {}
) {
    var focused by remember { mutableStateOf(false) }
    val scale = if (focused) 1.03f else 1.0f
    val interactionSource = remember { MutableInteractionSource() }

    androidx.compose.material3.Surface(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .onFocusChanged {
                if (it.isFocused) onFocused()
                focused = it.isFocused
            }
            .clickable(
                interactionSource = interactionSource,
                indication = androidx.compose.material.ripple.rememberRipple(),
                onClick = onClick
            ),
        shape = RoundedCornerShape(6.dp),
        color = when {
            focused -> MaterialTheme.colorScheme.primaryContainer
            isSelected -> MaterialTheme.colorScheme.surfaceVariant
            else -> Color.Transparent
        },
        contentColor = if (focused) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.width(14.dp).height(14.dp),
                        tint = iconTint
                    )
                }
                Text(
                    text = text,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isSelected && !focused) MaterialTheme.colorScheme.primary
                    else androidx.compose.ui.graphics.Color.Unspecified,
                    modifier = Modifier.weight(1f)
                )
            }
            // 国家/语言徽章行
            if (countryText.isNotEmpty() || langText.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (countryText.isNotEmpty()) {
                        Text(
                            text = countryText,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = Color.White,
                            modifier = Modifier
                                .background(Color(0xFF111827), RoundedCornerShape(3.dp))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                                .widthIn(max = 90.dp)
                        )
                    }
                    if (langText.isNotEmpty()) {
                        Text(
                            text = langText,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = Color(0xFF1D4ED8),
                            modifier = Modifier
                                .background(Color(0xFFEFF6FF), RoundedCornerShape(3.dp))
                                .border(0.5.dp, Color(0xFFBFDBFE), RoundedCornerShape(3.dp))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                                .widthIn(max = 140.dp)
                        )
                    }
                }
            }
        }
    }
}

/* ====================== LazyListState 居中滚动扩展 ====================== */

/**
 * 滚动 LazyColumn 使指定 index 的 item 出现在可视区域的中间。
 *
 * 策略：
 * 1. 先 `scrollToItem(index - k)` 让选中项上方保留大约 k 条可见，使其出现在中间。
 * 2. 再根据当前可视区大小精确滚动额外像素偏移，使 item 居中。
 *
 * k 取经验值「可视区可见条数 / 2」≈ 4，避免出现在顶部或底部。
 */
private suspend fun androidx.compose.foundation.lazy.LazyListState.scrollCenteredTo(index: Int) {
    if (index < 0) return
    // 第一次滚动：把选中项上方保留 4 条（大约在中间位置）
    val firstVisible = (index - 4).coerceAtLeast(0)
    scrollToItem(index = firstVisible, scrollOffset = 0)
    // 等待一帧后获取精确的 layoutInfo 再次调整（确保可见区域大小已知）
    kotlinx.coroutines.delay(20)
    val info = layoutInfo
    val viewportSize = info.viewportEndOffset - info.viewportStartOffset
    val targetItem = info.visibleItemsInfo.firstOrNull { it.index == index }
    if (targetItem != null && viewportSize > 0) {
        // 目标居中位置：item 顶部要在 viewportStart + viewportSize/2 - itemSize/2
        val wantedTop = info.viewportStartOffset + (viewportSize / 2) - (targetItem.size / 2)
        val currentTop = targetItem.offset
        val deltaInPx = wantedTop - currentTop
        if (kotlin.math.abs(deltaInPx) > 1) {
            // 把差值转换为 scroll 偏移：positive → 向下滚
            requestScrollToItem(index = firstVisible, scrollOffset = -deltaInPx)
        }
    }
}

/** 以像素偏移调用 scrollToItem 的辅助（通过先滚到 firstVisible + 补 scrollOffset） */
private suspend fun androidx.compose.foundation.lazy.LazyListState.requestScrollToItem(
    index: Int,
    scrollOffset: Int
) {
    // LazyListState scrollToItem(index, offset) 中 offset 是 item 起始到 viewport 顶部的额外像素
    // 这里我们直接用 scrollToItem(index, offset)
    scrollToItem(index = index, scrollOffset = scrollOffset.coerceIn(0, 100_000))
}

/* ====================== 搜索模式 UI ====================== */

/**
 * 搜索列：顶部搜索输入框 + 下方实时筛选结果列表。
 *
 * - 搜索框聚焦后自动弹软键盘；D-pad 下键可从搜索框移动到结果列表
 * - 空查询时显示提示文本，不显示结果
 * - 选中频道即触发 [onChannelSelected]；返回键由外层处理（触发 onExitSearch）
 */
@Composable
private fun SearchColumn(
    modifier: Modifier,
    query: String,
    results: List<ChannelEntry>,
    selectedIndex: Int,
    listState: androidx.compose.foundation.lazy.LazyListState,
    focusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
    onChannelSelected: (ChannelEntry) -> Unit,
    onExitSearch: () -> Unit
) {
    val searchFieldFocus = remember { FocusRequester() }

    Column(
        modifier = modifier
            .fillMaxHeight()
    ) {
        // 列标题：显示为「搜索」+ 结果数量
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp, start = 4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.width(16.dp).height(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = if (results.isNotEmpty()) "搜索 (${results.size})" else "搜索",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // 搜索输入框
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ) {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(searchFieldFocus),
                placeholder = {
                    Text(
                        text = "输入频道名…",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.width(18.dp).height(18.dp)
                    )
                },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                    disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                )
            )
        }

        // 结果列表
        Box(modifier = Modifier.fillMaxSize()) {
            if (query.isBlank()) {
                // 空查询提示
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "输入频道名以搜索\n（跨当前源所有分类）",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else if (results.isEmpty()) {
                // 无结果提示
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "无匹配频道",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    modifier = Modifier.focusRequester(focusRequester)
                ) {
                    items(results) { channel ->
                        val index = results.indexOf(channel)
                        PanelItem(
                            text = channel.name,
                            isSelected = index == selectedIndex,
                            icon = if (channel.isFavorite) Icons.Default.Star else null,
                            iconTint = MaterialTheme.colorScheme.secondary,
                            onClick = { onChannelSelected(channel) },
                            onFocused = { }
                        )
                    }
                }
            }
        }
    }

    // 进入搜索模式时自动聚焦搜索框（弹软键盘）
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { searchFieldFocus.requestFocus() }
    }

    // 结果变化时滚动到选中项
    LaunchedEffect(results, selectedIndex) {
        if (results.isNotEmpty()) {
            val idx = selectedIndex.coerceIn(0, results.lastIndex)
            listState.scrollCenteredTo(idx)
        }
    }
}
