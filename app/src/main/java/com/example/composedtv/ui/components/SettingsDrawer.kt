package com.example.composedtv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.composedtv.viewmodel.PlaybackSettings
import com.example.composedtv.viewmodel.PlaybackSettingOptions
import com.example.composedtv.viewmodel.RendererMode
import com.example.composedtv.viewmodel.StartChannelMode

/** 一个设置分组：左排一项 → 右排一组选项 */
private data class SettingGroupDef(
    val title: String,
    /** 右排选项：显示文案 → 点击动作 */
    val options: List<Pair<String, () -> Unit>>,
    /** 当前选中项索引（单选组）；动作组传 null */
    val selectedIndex: Int?
)

/**
 * 右侧配置抽屉：由遥控器 MENU 键唤出，调整播放参数。
 *
 * 两排模式（现代 TV 设置布局）：
 * - 左排：设置分组（首播频道 / 直连起播超时 / 卡顿判定 / 代理起播超时 / 渲染方式 / 诊断）
 * - 右排：当前分组的选项
 * 相比原先单列纵向堆叠，可聚焦项从 19 个降到「左排 6 + 右排 3」，
 * 上下键次数大幅减少；←→ 键在两排间切换。
 *
 * @param isGuest 游客模式：不显示「首播频道」分组（游客固定续播上次退出的频道）
 */
@Composable
fun SettingsDrawer(
    visible: Boolean,
    settings: PlaybackSettings,
    onDirectTimeoutChange: (Long) -> Unit,
    onStuckTimeoutChange: (Long) -> Unit,
    onProxyTimeoutChange: (Long) -> Unit,
    onRendererChange: (RendererMode) -> Unit,
    // ===== 首播频道（仅登录用户） =====
    isGuest: Boolean = false,
    onStartChannelModeChange: (StartChannelMode) -> Unit = {},
    // ===== 诊断（无 ADB 环境调试用） =====
    diagEnabled: Boolean = false,
    onToggleDiag: () -> Unit = {},
    onResetDiag: () -> Unit = {}
) {
    // 分组定义：随设置值与诊断状态变化重建（回调本身稳定，不列入 key 避免无谓重建）
    val groups = remember(
        settings.directTimeoutMs, settings.stuckTimeoutMs, settings.proxyTimeoutMs,
        settings.rendererMode, settings.startChannelMode, isGuest, diagEnabled
    ) {
        listOfNotNull(
            // 游客没有收藏，首播固定为「上次退出频道」，无需该分组
            if (isGuest) null else SettingGroupDef(
                title = "首播频道",
                options = PlaybackSettingOptions.startChannelOptions.map { (mode, label) ->
                    label to { onStartChannelModeChange(mode) }
                },
                selectedIndex = PlaybackSettingOptions.startChannelOptions
                    .indexOfFirst { it.first == settings.startChannelMode }.coerceAtLeast(0)
            ),
            SettingGroupDef(
                title = "直连起播超时",
                options = PlaybackSettingOptions.directTimeoutOptions.map { (v, label) ->
                    label to { onDirectTimeoutChange(v) }
                },
                selectedIndex = PlaybackSettingOptions.directTimeoutOptions
                    .indexOfFirst { it.first == settings.directTimeoutMs }.coerceAtLeast(0)
            ),
            SettingGroupDef(
                title = "卡顿判定",
                options = PlaybackSettingOptions.stuckOptions.map { (v, label) ->
                    label to { onStuckTimeoutChange(v) }
                },
                selectedIndex = PlaybackSettingOptions.stuckOptions
                    .indexOfFirst { it.first == settings.stuckTimeoutMs }.coerceAtLeast(0)
            ),
            SettingGroupDef(
                title = "代理起播超时",
                options = PlaybackSettingOptions.proxyTimeoutOptions.map { (v, label) ->
                    label to { onProxyTimeoutChange(v) }
                },
                selectedIndex = PlaybackSettingOptions.proxyTimeoutOptions
                    .indexOfFirst { it.first == settings.proxyTimeoutMs }.coerceAtLeast(0)
            ),
            SettingGroupDef(
                title = "渲染方式",
                options = PlaybackSettingOptions.rendererOptions.map { (mode, label) ->
                    label to { onRendererChange(mode) }
                },
                selectedIndex = PlaybackSettingOptions.rendererOptions
                    .indexOfFirst { it.first == settings.rendererMode }.coerceAtLeast(0)
            ),
            SettingGroupDef(
                title = "诊断",
                options = listOf(
                    (if (diagEnabled) "画面 HUD：开（点此关闭）" else "画面 HUD：关（点此开启）") to onToggleDiag,
                    "复位诊断计数器" to onResetDiag
                ),
                // 动作组：用「是否开启」作为选中态，仅作视觉提示
                selectedIndex = if (diagEnabled) 0 else -1
            )
        )
    }

    /** 当前选中的分组索引（左排高亮项） */
    var selectedGroup by remember { mutableIntStateOf(0) }

    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(initialOffsetX = { it }),
        exit = slideOutHorizontally(targetOffsetX = { it })
    ) {
        // 打开后把焦点交给左排第一项（内容已在 AnimatedVisibility 内 attach，请求更可靠）
        val leftFocusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(150)
            runCatching { leftFocusRequester.requestFocus() }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                // 遮罩不再响应点击关闭：抽屉只由「返回键 / MENU」关闭。
                // 原因：点击关闭极易误触——列表项间隙、右排空白、飞鼠轻微移动
                // 都会被判定为「点击抽屉外部」而立即收起，导致选完分组还没选值就消失。
                // 这里仅吞掉指针事件（onTap 空实现），避免点击穿透到下方播放器
                // 触发切台等手势；关闭统一交给按键。
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { /* 吞掉：关闭只由返回键 / MENU 负责 */ })
                },
            contentAlignment = Alignment.CenterEnd
        ) {
            Column(
                modifier = Modifier
                    .width(560.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                    .background(Color(0xFF1A1A1A))
                    .border(
                        width = 1.dp,
                        color = Color(0xFF444444),
                        shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
                    )
                    // 吃掉内容卡片区内的所有指针事件（含列表项间隙、右排空白、内边距）。
                    // 否则这些区域不消费点击，事件会冒泡到外层遮罩的 pointerInput，
                    // 被判定为「点击抽屉外部」→ 立即关闭（表现为选完分类还没选值就消失）。
                    // 用 pointerInput 而非 clickable，避免让内容容器变成可聚焦节点、
                    // 干扰 D-pad 在两排之间的焦点导航。
                    .pointerInput(Unit) {
                        // 吞掉内容区内的 tap（detectTapGestures 会消费事件），
                        // 使其不再冒泡到外层遮罩
                        detectTapGestures(onTap = { /* 内容区内点击：不关闭抽屉 */ })
                    }
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 标题
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = null, tint = Color.White)
                    Text("播放设置", color = Color.White, fontSize = 20.sp)
                }

                Text(
                    "返回键 / MENU 关闭 · ←→ 切换分组与选项 · 修改即时生效并自动记忆",
                    color = Color(0xFFAAAAAA),
                    fontSize = 12.sp
                )

                // ===== 两排主体 =====
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 左排：分组列表（上下键在此选择分组）
                    LazyColumn(
                        modifier = Modifier
                            .weight(0.40f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(groups) { idx, group ->
                            SettingOptionItem(
                                label = group.title,
                                isSelected = idx == selectedGroup,
                                onClick = { selectedGroup = idx },
                                focusRequester = if (idx == 0) leftFocusRequester else null
                            )
                        }
                    }

                    // 右排：当前分组的选项（上下键在此选择值）
                    val current = groups.getOrNull(selectedGroup)
                    LazyColumn(
                        modifier = Modifier
                            .weight(0.60f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (current != null) {
                            itemsIndexed(current.options) { idx, (label, action) ->
                                SettingOptionItem(
                                    label = label,
                                    isSelected = idx == current.selectedIndex,
                                    onClick = action
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单个可聚焦列表项（左排分组 / 右排选项共用），风格对齐 SidePanel 的 PanelItem。
 */
@Composable
private fun SettingOptionItem(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null
) {
    var focused by remember { mutableStateOf(false) }
    val scale = if (focused) 1.03f else 1.0f
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .onFocusChanged { focused = it.isFocused }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = rememberRipple(),
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isSelected && !focused) MaterialTheme.colorScheme.primary
                else Color.Unspecified,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
