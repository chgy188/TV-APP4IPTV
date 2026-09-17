@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.example.composedtv.ui.components

import android.content.Context
import android.util.Log
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.platform.LocalView
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import com.example.composedtv.viewmodel.CategoryEntry
import com.example.composedtv.viewmodel.ChannelEntry
import com.example.composedtv.viewmodel.SidePanelData
import kotlinx.coroutines.delay

/** 侧边栏自动隐藏超时（毫秒）：无操作 15 秒后自动收起（选台需要足够时间浏览） */
private const val SIDE_PANEL_TIMEOUT_MS = 15_000L

/** 节目栏单列表模式下的当前层级：源 / 分类 / 频道 */
private enum class PanelLevel { SOURCE, CATEGORY, CHANNEL }

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
    val categoryFocusRequester = remember { FocusRequester() }
    val sourceFocusRequester = remember { FocusRequester() }

    // 当前显示的列表层（单列表模式）：源 / 分类 / 频道。
    // 开面板默认落在频道层（确定键最先弹出当前频道列表）。
    var level by remember(isVisible) { mutableStateOf(PanelLevel.CHANNEL) }

    // 三个列表的滚动状态提到顶层：用于检测「正在滚动」也算用户活动
    val sourceListState = rememberLazyListState()
    val categoryListState = rememberLazyListState()
    val channelListState = rememberLazyListState()

    // 节目栏宽度自适应「当前列表最宽项」：用 Paint 测量源/分类/频道三列表中最长名字的像素宽度
    // （与 Compose 文本渲染同用系统默认字体，结果一致），加上内/外边距与图标占位后作为面板宽度，
    // 并夹在 [220dp, 480dp] 之间，避免切层时宽度跳动。
    val density = LocalDensity.current
    val panelWidth = remember(data.sources, data.categories, data.channels) {
        // 列表项文字字号 13.sp → px（sp 需额外乘 fontScale）
        val textSizePx = 13f * density.density * density.fontScale
        val paint = android.graphics.Paint().apply { textSize = textSizePx }
        val names = data.sources.map { it.name } +
            data.categories.map { it.name } +
            data.channels.map { it.name }
        val maxPx = names.maxOfOrNull { name ->
            if (name.isBlank()) 0f else paint.measureText(name)
        } ?: 0f
        val contentDp = with(density) { maxPx.toDp() }
        // 32dp 面板外边距 + 24dp 列表项内边距 + 22dp 图标占位(14+6) 的冗余
        (contentDp + 78.dp).coerceIn(220.dp, 480.dp)
    }

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

    // 层切换 / 打开面板时：把焦点放到「当前层」的当前选中项并滚动到可视区中间。
    // 搜索模式不抢焦点（交给 SearchColumn 自身处理）。
    // 注意：列表为空时 lastIndex = -1，coerceIn(0, -1) 会抛异常，故先判空再取值。
    LaunchedEffect(
        isVisible, level, data.isSearchMode,
        data.selectedSourceIndex, data.selectedCategoryIndex, data.selectedChannelIndex,
        data.sources, data.categories, data.channels
    ) {
        if (!isVisible || data.isSearchMode) return@LaunchedEffect
        bumpActivity()
        when (level) {
            PanelLevel.SOURCE -> {
                if (data.sources.isEmpty()) return@LaunchedEffect
                val idx = data.selectedSourceIndex.coerceIn(0, data.sources.lastIndex)
                sourceListState.scrollCenteredTo(idx)
                delay(180)
                repeat(8) {
                    delay(100)
                    if (runCatching { sourceFocusRequester.requestFocus() }.isSuccess) return@LaunchedEffect
                }
            }
            PanelLevel.CATEGORY -> {
                if (data.categories.isEmpty()) return@LaunchedEffect
                val idx = data.selectedCategoryIndex.coerceIn(0, data.categories.lastIndex)
                categoryListState.scrollCenteredTo(idx)
                delay(180)
                repeat(8) {
                    delay(100)
                    if (runCatching { categoryFocusRequester.requestFocus() }.isSuccess) return@LaunchedEffect
                }
            }
            PanelLevel.CHANNEL -> {
                if (data.channels.isEmpty()) return@LaunchedEffect
                val idx = data.selectedChannelIndex.coerceIn(0, data.channels.lastIndex)
                channelListState.scrollCenteredTo(idx)
                delay(180)
                repeat(8) {
                    delay(100)
                    if (runCatching { channelFocusRequester.requestFocus() }.isSuccess) return@LaunchedEffect
                }
            }
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut()
    ) {
        // 外层：圆角 + 边框（玻璃边缘高光）；宽度自适应频道名，见 panelWidth
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(panelWidth)
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

            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(16.dp)
            ) {
                // 面包屑：源 › 分类 › 频道（当前层高亮，可点击直接跳转层级）
                Breadcrumb(
                    level = level,
                    onJump = { l ->
                        if (!data.isSearchMode) {
                            level = l
                            bumpActivity()
                        }
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 单列表内容区：左右键在三层间平移切换（仅切换显示的列表，不提交高亮项），
                // OK（clickable 触发）提交当前高亮项并下钻一层；频道层 OK = 切台并关闭。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when (event.key) {
                                Key.DirectionLeft -> {
                                    if (data.isSearchMode) {
                                        // 搜索模式下左键=退出搜索，回到分类层
                                        onExitSearch()
                                    } else {
                                        when (level) {
                                            PanelLevel.CHANNEL -> level = PanelLevel.CATEGORY
                                            PanelLevel.CATEGORY -> level = PanelLevel.SOURCE
                                            PanelLevel.SOURCE -> {}
                                        }
                                    }
                                    bumpActivity()
                                    true
                                }
                                Key.DirectionRight -> {
                                    if (!data.isSearchMode) {
                                        when (level) {
                                            PanelLevel.SOURCE -> level = PanelLevel.CATEGORY
                                            PanelLevel.CATEGORY -> level = PanelLevel.CHANNEL
                                            PanelLevel.CHANNEL -> {}
                                        }
                                        bumpActivity()
                                    }
                                    true
                                }
                                else -> false
                            }
                        }
                ) {
                    when {
                        data.isSearchMode -> {
                            SearchColumn(
                                modifier = Modifier.fillMaxSize(),
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
                                }
                            )
                        }
                        level == PanelLevel.SOURCE -> {
                            PanelColumn(
                                title = "直播源",
                                icon = Icons.Default.PlayArrow,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                if (data.sources.isEmpty()) {
                                    EmptyHint("无直播源")
                                } else {
                                    LazyColumn(
                                        state = sourceListState,
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                        contentPadding = PaddingValues(vertical = 4.dp)
                                    ) {
                                        items(data.sources) { source ->
                                            val index = data.sources.indexOf(source)
                                            PanelItem(
                                                focusRequester = if (index == data.selectedSourceIndex) {
                                                    sourceFocusRequester
                                                } else {
                                                    null
                                                },
                                                text = source.name,
                                                isSelected = index == data.selectedSourceIndex,
                                                onClick = {
                                                    bumpActivity()
                                                    onSourceSelected(index)
                                                    level = PanelLevel.CATEGORY
                                                },
                                                onFocused = { bumpActivity() }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        level == PanelLevel.CATEGORY -> {
                            PanelColumn(
                                title = "分类",
                                icon = Icons.Default.Star,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                if (data.isLoadingCategories && data.categories.isEmpty()) {
                                    LoadingSpinner()
                                } else if (data.categories.isEmpty()) {
                                    EmptyHint("无分类")
                                } else {
                                    LazyColumn(
                                        state = categoryListState,
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                        contentPadding = PaddingValues(vertical = 4.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        itemsIndexed(items = data.categories) { index, category ->
                                            PanelItem(
                                                focusRequester = if (index == data.selectedCategoryIndex) {
                                                    categoryFocusRequester
                                                } else {
                                                    null
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
                                                    level = PanelLevel.CHANNEL
                                                },
                                                onFocused = { bumpActivity() }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        else -> {
                            PanelColumn(
                                title = "频道",
                                icon = Icons.Default.PlayArrow,
                                modifier = Modifier.fillMaxSize()
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
                                } else if (data.channels.isEmpty()) {
                                    EmptyHint("无频道")
                                } else {
                                    LazyColumn(
                                        state = channelListState,
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                        contentPadding = PaddingValues(vertical = 4.dp)
                                    ) {
                                        itemsIndexed(data.channels) { index, channel ->
                                            PanelItem(
                                                focusRequester = if (index == data.selectedChannelIndex) {
                                                    channelFocusRequester
                                                } else {
                                                    null
                                                },
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
private fun Breadcrumb(
    level: PanelLevel,
    onJump: (PanelLevel) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        BreadcrumbChip("源", level == PanelLevel.SOURCE) { onJump(PanelLevel.SOURCE) }
        Text(
            text = "›",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        BreadcrumbChip("分类", level == PanelLevel.CATEGORY) { onJump(PanelLevel.CATEGORY) }
        Text(
            text = "›",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        BreadcrumbChip("频道", level == PanelLevel.CHANNEL) { onJump(PanelLevel.CHANNEL) }
    }
}

@Composable
private fun BreadcrumbChip(
    text: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun LoadingSpinner() {
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
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
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
    focusRequester: FocusRequester? = null,
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
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clickable(
                interactionSource = interactionSource,
                indication = androidx.compose.material.ripple.rememberRipple(),
                onClick = onClick
            )
            .onFocusChanged {
                if (it.isFocused) onFocused()
                focused = it.isFocused
            },
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
 * - 选中频道即触发 [onChannelSelected]；退出搜索由外层（返回键 / 选中其他分类）处理
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
    onChannelSelected: (ChannelEntry) -> Unit
) {
    val searchFieldFocus = remember { FocusRequester() }
    val firstResultFocus = remember { FocusRequester() }
    var shouldFocusFirstResult by remember { mutableStateOf(false) }
    // 用于强制弹出系统软键盘（TV 端部分设备不会因聚焦自动弹键盘）
    val searchView = LocalView.current
    val imm = searchView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    // Compose 推荐的软键盘控制：内部会正确关联当前焦点节点，比 imm.showSoftInput(view) 更可靠
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // 输入激活标记：进入搜索默认只聚焦输入框（高亮），用户按 OK/确认 后才弹软键盘
    var keyboardActivated by remember { mutableStateOf(false) }
    var shouldRestoreFocus by remember { mutableStateOf(false) }
    var isEditing by remember { mutableStateOf(true) }
    // 设备是否存在已启用的输入法：多数 TV 没有输入法，此时由应用自行把硬件按键
    // 转成编辑操作，保证任何设备都能输入（见 handleHardwareKey）
    val hasIme = remember { imm.enabledInputMethodList.isNotEmpty() }
    // 用 TextFieldValue 维护文本与光标位置：无输入法时也能显示光标并可编辑
    var fieldValue by remember { mutableStateOf(TextFieldValue(query)) }
    // 输入框是否「真正」拿到了焦点（由 onFocusChanged 更新）。
    // 关键：requestFocus() 未生效时也不会抛异常，所以绝不能用 isSuccess 判断成功，
    // 必须以这里记录的真实焦点状态作为重试依据。
    var focusConfirmed by remember { mutableStateOf(false) }
    val focusConfirmedRef by rememberUpdatedState(focusConfirmed)
    // 外部 query 变化（如清空搜索）时同步回输入框
    LaunchedEffect(query) {
        if (query != fieldValue.text) fieldValue = fieldValue.copy(text = query)
    }

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
        // 用 BasicTextField（与登录页 InputField 同款）。关键原因：
        // Material3 TextField 的 modifier 加在「外层装饰容器」上，真正持有焦点的是内层 BasicTextField，
        // 导致 focusRequester / onKeyEvent / onFocusChanged 全部落在外层而失效
        // （表现为聚焦失败、按键收不到、无法输入）。BasicTextField 的 modifier 与焦点目标同一节点，
        // 请求焦点与按键处理都能生效，且支持 cursorBrush（TextField 不支持该参数）。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    RoundedCornerShape(8.dp)
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    if (fieldValue.text.isEmpty()) {
                        Text(
                            text = "输入频道名…",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.align(Alignment.CenterStart)
                        )
                    }
                    BasicTextField(
                        value = fieldValue,
                        onValueChange = { newValue ->
                            fieldValue = newValue
                            onQueryChange(newValue.text)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.CenterStart)
                            .focusRequester(searchFieldFocus)
                            .focusable()
                            .onFocusChanged {
                                Log.d("SidePanel", "搜索框焦点变化 isFocused=${it.isFocused} keyboardActivated=$keyboardActivated isEditing=$isEditing")
                                focusConfirmed = it.isFocused
                                if (it.isFocused) {
                                    keyboardController?.show()
                                } else {
                                    if (keyboardActivated) {
                                        keyboardActivated = false
                                        isEditing = false
                                    }
                                }
                            }
                            .onKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                Log.d("SidePanel", "搜索框按键 key=${event.key} nativeCode=${event.nativeKeyEvent.keyCode} isEditing=$isEditing keyboardActivated=$keyboardActivated")
                                if ((event.key == Key.Enter || event.key == Key.DirectionCenter)) {
                                    Log.d("SidePanel", "检测到Enter/Center keyboardActivated=$keyboardActivated")
                                    if (keyboardActivated) {
                                        keyboardController?.hide()
                                        keyboardActivated = false
                                        isEditing = false
                                    } else {
                                        keyboardController?.show()
                                        keyboardActivated = true
                                        isEditing = true
                                    }
                                    return@onKeyEvent true
                                }
                                val isBackKey = event.key == Key.Escape || event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK
                                if (isBackKey) {
                                    Log.d("SidePanel", "检测到Back键 keyboardActivated=$keyboardActivated")
                                    if (keyboardActivated) {
                                        keyboardController?.hide()
                                        keyboardActivated = false
                                        isEditing = false
                                    }
                                    return@onKeyEvent true
                                }
                                if (!isEditing) {
                                    if (event.key == Key.DirectionDown) {
                                        if (results.isNotEmpty()) {
                                            shouldFocusFirstResult = true
                                        }
                                        return@onKeyEvent true
                                    }
                                    return@onKeyEvent false
                                }
                                if (isEditing) {
                                    if (event.key == Key.DirectionDown || event.key == Key.DirectionUp ||
                                        event.key == Key.DirectionLeft || event.key == Key.DirectionRight) {
                                        return@onKeyEvent false
                                    }
                                    return@onKeyEvent handleHardwareKey(event, fieldValue) { newValue ->
                                        fieldValue = newValue
                                        onQueryChange(newValue.text)
                                    }
                                }
                                return@onKeyEvent false
                            },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Search
                        ),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                Log.d("SidePanel", "软键盘Search动作")
                                keyboardController?.hide()
                                keyboardActivated = false
                                isEditing = false
                            }
                        )
                    )
                }
            }
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
                    itemsIndexed(results) { index, channel ->
                        PanelItem(
                            focusRequester = if (index == 0) firstResultFocus else null,
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

    // 进入搜索模式时聚焦搜索框。
    // 带重试：搜索列位于 AnimatedVisibility 滑入动画中，首次 requestFocus 常因视图尚未布局完成而失败
    // （runCatching 会静默吞掉），失败后焦点没落在输入框。
    LaunchedEffect(Unit) {
        focusConfirmed = false
        keyboardActivated = false
        var lastError: String? = null
        for (i in 0 until 20) {
            delay(if (i == 0) 200 else 100)
            if (focusConfirmedRef) break
            runCatching { searchFieldFocus.requestFocus() }
                .onFailure { lastError = "${it.javaClass.simpleName}: ${it.message}" }
        }
        Log.d("SidePanel", "搜索框聚焦结果 已确认焦点=$focusConfirmedRef 输入法可用=$hasIme 错误=$lastError")
        // 聚焦后主动拉起输入法：原设计需用户先按 OK 确认才弹键盘，交互不直观、易被认为"无法输入"。
        // 设备有输入法时直接拉起，进入搜索即可键入；无输入法则 keyboardController?.show() 静默失败，保持原行为。
        if (focusConfirmedRef && hasIme) {
            delay(200)
            keyboardActivated = true
            keyboardController?.show()
        }
    }

    // 结果变化时滚动到选中项
    LaunchedEffect(results, selectedIndex) {
        if (results.isNotEmpty()) {
            val idx = selectedIndex.coerceIn(0, results.lastIndex)
            listState.scrollCenteredTo(idx)
        }
    }

    // 当 shouldRestoreFocus 为 true 时，恢复搜索框焦点（键盘隐藏后）
    LaunchedEffect(shouldRestoreFocus) {
        if (shouldRestoreFocus) {
            shouldRestoreFocus = false
            delay(200)
            searchFieldFocus.requestFocus()
        }
    }

    // 当 shouldFocusFirstResult 为 true 时，聚焦到结果列表第一项
    LaunchedEffect(shouldFocusFirstResult) {
        if (shouldFocusFirstResult && results.isNotEmpty()) {
            shouldFocusFirstResult = false
            listState.scrollToItem(0)
            delay(500)
            for (i in 0 until 20) {
                val success = runCatching { firstResultFocus.requestFocus() }.isSuccess
                Log.d("SidePanel", "第一项结果聚焦 attempt=$i success=$success")
                if (success) break
                delay(100)
            }
        }
    }
}

/**
 * 无输入法兜底：把硬件按键直接转成文本编辑操作（插入/删除/移动光标）。
 *
 * Android 的文本输入必须经过 IME，设备没有输入法时任何 TextField 都收不到字符，
 * 这里自行处理按键，保证搜索框在任意设备上都能输入。
 *
 * @return 是否消费该按键事件
 */
private fun handleHardwareKey(
    event: androidx.compose.ui.input.key.KeyEvent,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit
): Boolean {
    val start = value.selection.start.coerceIn(0, value.text.length)
    val end = value.selection.end.coerceIn(start, value.text.length)
    when (event.key) {
        Key.Backspace -> {
            when {
                start != end -> {
                    val newText = value.text.removeRange(start, end)
                    onValueChange(TextFieldValue(newText, TextRange(start)))
                }
                start > 0 -> {
                    val newText = value.text.removeRange(start - 1, start)
                    onValueChange(TextFieldValue(newText, TextRange(start - 1)))
                }
            }
            return true
        }
        Key.Delete -> {
            when {
                start != end -> {
                    val newText = value.text.removeRange(start, end)
                    onValueChange(TextFieldValue(newText, TextRange(start)))
                }
                end < value.text.length -> {
                    val newText = value.text.removeRange(end, end + 1)
                    onValueChange(TextFieldValue(newText, TextRange(start)))
                }
            }
            return true
        }
        Key.DirectionLeft -> {
            onValueChange(value.copy(selection = TextRange((start - 1).coerceAtLeast(0))))
            return true
        }
        Key.DirectionRight -> {
            onValueChange(value.copy(selection = TextRange((end + 1).coerceAtMost(value.text.length))))
            return true
        }
        Key.DirectionCenter -> {
            return true
        }
        else -> {
            var ch = event.nativeKeyEvent.getUnicodeChar(event.nativeKeyEvent.metaState)
            if (ch <= 0) {
                val keyCode = event.nativeKeyEvent.keyCode
                ch = when {
                    keyCode in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 ->
                        ('0' + (keyCode - android.view.KeyEvent.KEYCODE_0)).code
                    keyCode == android.view.KeyEvent.KEYCODE_MINUS -> '-'.code
                    keyCode == android.view.KeyEvent.KEYCODE_EQUALS -> '='.code
                    keyCode == android.view.KeyEvent.KEYCODE_SPACE -> ' '.code
                    keyCode in android.view.KeyEvent.KEYCODE_A..android.view.KeyEvent.KEYCODE_Z -> {
                        val isShifted = (event.nativeKeyEvent.metaState and android.view.KeyEvent.META_SHIFT_ON) != 0
                        val base = if (isShifted) 'A' else 'a'
                        (base + (keyCode - android.view.KeyEvent.KEYCODE_A)).code
                    }
                    else -> 0
                }
            }
            if (ch > 0) {
                val c = ch.toChar()
                if (!c.isISOControl()) {
                    val newText = value.text.substring(0, start) + c + value.text.substring(end)
                    onValueChange(TextFieldValue(newText, TextRange(start + 1)))
                    return true
                }
            }
        }
    }
    return false
}