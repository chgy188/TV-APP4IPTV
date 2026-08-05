package com.example.composedtv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.composedtv.viewmodel.PlaybackSettings
import com.example.composedtv.viewmodel.PlaybackSettingOptions

/**
 * 右侧配置抽屉：由遥控器 MENU 键唤出，调整播放参数。
 * 列表项聚焦体系与左侧选台面板（SidePanel）保持一致风格。
 */
@Composable
fun SettingsDrawer(
    visible: Boolean,
    settings: PlaybackSettings,
    onStuckTimeoutChange: (Long) -> Unit,
    onHedgeChange: (Long) -> Unit,
    onReviveChange: (Int) -> Unit,
    onClose: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(initialOffsetX = { it }),
        exit = slideOutHorizontally(targetOffsetX = { it })
    ) {
        // 抽屉内部：组合完成后立即请求焦点到第一个可聚焦项。
        // 放在 AnimatedVisibility 的 content 作用域内，确保元素已 attach 再 requestFocus，
        // 比在外层 LaunchedEffect(visible)+delay 更可靠（避免动画期间请求失败）。
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            // 等进入动画基本结束再抢焦点，避免动画过程中请求被打断
            kotlinx.coroutines.delay(150)
            runCatching { focusRequester.requestFocus() }
        }
        // 背景遮罩：拦截点击/焦点，防止焦点落到背后的播放器
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose
                ),
            contentAlignment = Alignment.CenterEnd
        ) {
            Column(
                modifier = Modifier
                    .width(380.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                    .background(Color(0xFF1A1A1A))
                    .border(
                        width = 1.dp,
                        color = Color(0xFF444444),
                        shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
                    )
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
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
                    "按 MENU 键关闭。修改即时生效，下次启动自动记忆。",
                    color = Color(0xFFAAAAAA),
                    fontSize = 12.sp
                )

                // 卡顿判定时间
                SettingGroup(
                    title = "卡顿判定时间（直连胜出后多久算卡死）",
                    options = PlaybackSettingOptions.stuckOptions.map { it.second },
                    selectedIndex = PlaybackSettingOptions.stuckOptions.indexOfFirst { it.first == settings.stuckTimeoutMs }
                        .coerceAtLeast(0),
                    onSelect = { idx -> onStuckTimeoutChange(PlaybackSettingOptions.stuckOptions[idx].first) },
                    focusRequester = focusRequester
                )

                // 竞速代理延迟
                SettingGroup(
                    title = "竞速代理启动延迟",
                    options = PlaybackSettingOptions.hedgeOptions.map { it.second },
                    selectedIndex = PlaybackSettingOptions.hedgeOptions.indexOfFirst { it.first == settings.hedgeMs }
                        .coerceAtLeast(0),
                    onSelect = { idx -> onHedgeChange(PlaybackSettingOptions.hedgeOptions[idx].first) }
                )

                // 代理复活次数
                SettingGroup(
                    title = "代理复活次数（直连卡顿后重试代理）",
                    options = PlaybackSettingOptions.reviveOptions.map { it.second },
                    selectedIndex = PlaybackSettingOptions.reviveOptions.indexOfFirst { it.first == settings.reviveMax }
                        .coerceAtLeast(0),
                    onSelect = { idx -> onReviveChange(PlaybackSettingOptions.reviveOptions[idx].first) }
                )
            }
        }
    }
}

/**
 * 单个设置分组：标题 + 一组单选列表项。
 */
@Composable
private fun SettingGroup(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    focusRequester: FocusRequester? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = Color(0xFFDDDDDD), fontSize = 14.sp)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEachIndexed { idx, label ->
                SettingOptionItem(
                    label = label,
                    isSelected = idx == selectedIndex,
                    onClick = { onSelect(idx) },
                    focusRequester = if (idx == 0) focusRequester else null
                )
            }
        }
    }
}

/**
 * 单个可聚焦单选列表项（风格对齐 SidePanel 的 PanelItem）。
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
                else Color.Unspecified
            )
        }
    }
}
