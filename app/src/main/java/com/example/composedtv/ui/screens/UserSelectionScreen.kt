@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.example.composedtv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.composedtv.data.remote.StoredUser

/**
 * 用户选择界面：显示已登录用户、游客、登录入口
 *
 * 关键：TvSurface 在触摸模式下不响应 click，手机/触屏设备上点击无反应。
 * 这里改用 Material3 Surface + clickable + 焦点放大，同时兼容 D-pad 和触摸。
 */
@Composable
fun UserSelectionScreen(
    storedUsers: List<StoredUser>,
    onSelectUser: (String) -> Unit,
    onSelectGuest: () -> Unit,
    onSelectLogin: () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0F1117), Color(0xFF1A1D27))
                )
            )
    ) {
        // 屏幕较矮（手机横屏）时启用紧凑布局，尽量一屏显示
        val compact = maxHeight < 520.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = if (compact) 20.dp else 32.dp,
                    vertical = if (compact) 16.dp else 32.dp
                ),
            verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 20.dp)
        ) {
            Text(
                text = "影直播",
                fontSize = if (compact) 26.sp else 36.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "选择用户以开始观看",
                fontSize = if (compact) 14.sp else 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!compact) Spacer(modifier = Modifier.height(8.dp))

            if (storedUsers.isNotEmpty()) {
                Text(
                    text = "已登录用户",
                    fontSize = if (compact) 14.sp else 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
                    contentPadding = PaddingValues(vertical = if (compact) 4.dp else 8.dp)
                ) {
                    items(storedUsers) { user ->
                        UserCard(
                            username = user.username,
                            compact = compact,
                            onClick = { onSelectUser(user.username) }
                        )
                    }
                }
            }

            if (!compact) Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ActionCard(
                    icon = Icons.Default.PlayArrow,
                    title = "游客模式",
                    subtitle = "直接观看默认频道",
                    compact = compact,
                    onClick = onSelectGuest
                )
                ActionCard(
                    icon = Icons.Default.Star,
                    title = "登录 / 注册",
                    subtitle = "登录后可管理收藏",
                    compact = compact,
                    onClick = onSelectLogin
                )
            }

            // 底部遥控器/键盘操作说明
            Spacer(modifier = Modifier.height(if (compact) 8.dp else 16.dp))
            RemoteHint(
                compact = compact,
                items = listOf(
                    "←→↑↓" to "移动选择",
                    "确定" to "进入",
                    "返回" to "退出"
                )
            )
        }
    }
}

@Composable
private fun RemoteHint(items: List<Pair<String, String>>, compact: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Color.Black.copy(alpha = 0.35f),
                RoundedCornerShape(12.dp)
            )
            .padding(
                horizontal = if (compact) 12.dp else 20.dp,
                vertical = if (compact) 6.dp else 12.dp
            ),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 28.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { (key, desc) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Text(
                        text = key,
                        modifier = Modifier.padding(
                            horizontal = if (compact) 7.dp else 10.dp,
                            vertical = if (compact) 2.dp else 4.dp
                        ),
                        fontSize = if (compact) 11.sp else 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = desc,
                    fontSize = if (compact) 11.sp else 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 兼容 D-pad 焦点 + 触摸点击的可点击卡片
 */
@Composable
private fun FocusableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(12.dp),
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale = if (isFocused) 1.05f else 1.0f

    Surface(
        modifier = modifier
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = androidx.compose.material.ripple.rememberRipple(),
                onClick = onClick
            ),
        shape = shape,
        color = if (isFocused) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        contentColor = if (isFocused) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurface
    ) {
        content()
    }
}

@Composable
private fun UserCard(username: String, compact: Boolean, onClick: () -> Unit) {
    FocusableCard(
        onClick = onClick,
        modifier = Modifier
            .width(if (compact) 150.dp else 200.dp)
            .height(if (compact) 84.dp else 120.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (compact) 10.dp else 16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.AccountCircle,
                contentDescription = null,
                modifier = Modifier.size(if (compact) 28.dp else 40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(if (compact) 4.dp else 8.dp))
            Text(
                text = username,
                fontSize = if (compact) 15.sp else 18.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    compact: Boolean,
    onClick: () -> Unit
) {
    FocusableCard(
        onClick = onClick,
        modifier = Modifier
            .width(if (compact) 190.dp else 260.dp)
            .height(if (compact) 84.dp else 120.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (compact) 10.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(if (compact) 26.dp else 36.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    fontSize = if (compact) 15.sp else 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(if (compact) 2.dp else 4.dp))
                Text(
                    text = subtitle,
                    fontSize = if (compact) 11.sp else 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
