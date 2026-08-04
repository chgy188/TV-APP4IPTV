@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.media3.common.util.UnstableApi::class
)

package com.example.composedtv.ui.screens

import android.util.Log
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface as TvSurface
import com.example.composedtv.R
import com.example.composedtv.player.PlayerEngine
import com.example.composedtv.player.PlayerState
import com.example.composedtv.player.PlaylistItem
import com.example.composedtv.ui.components.SidePanel
import com.example.composedtv.viewmodel.ChannelEntry
import com.example.composedtv.viewmodel.PlayerViewModel

/**
 * 全屏播放器界面
 *
 * 行为：
 * - 进入后立即开始播放初始频道（收藏第一个 / 游客默认）
 * - OK 键：切换左侧三排目录面板
 * - 上/下键：切换频道（面板关闭时）
 * - 返回键：先关闭面板，再双击退出
 */
@Composable
fun PlayerScreen(
    vm: PlayerViewModel,
    isGuest: Boolean,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val uiState by vm.uiState.collectAsState()

    // 创建 PlayerEngine（随 Composable 生命周期管理）
    val engine = remember { PlayerEngine(context) }
    val playerState by engine.stateFlow.collectAsState()

    // 收集 VM 发送的收藏信号 → 调用局部 engine 执行（不依赖 engine 引用传递）
    LaunchedEffect(Unit) {
        vm.toggleFavoriteSignal.collect {
            Log.d("PlayerScreen", "收到收藏信号，执行 toggleFavorite")
            engine.toggleFavorite()
        }
    }

    // 收集 VM 发送的重载信号 → 调用局部 engine 执行
    LaunchedEffect(Unit) {
        vm.manualReloadSignal.collect {
            Log.d("PlayerScreen", "收到重载信号，执行 manualReload")
            engine.manualReload()
        }
    }
    val currentPlayer by engine.playerFlow.collectAsState()
    var engineInitialized by remember { mutableStateOf(false) }

    // 焦点管理：确保播放器容器持有焦点，遥控器按键才能被正确接收
    val focusRequester = remember { FocusRequester() }

    // 侧边栏打开时暂停播放，关闭时恢复（仅对点播/非直播内容生效，直播不暂停）
    LaunchedEffect(uiState.sidePanelVisible) {
        if (uiState.initialChannelLoaded) {
            if (uiState.sidePanelVisible) {
                engine.pauseIfNotLive()
            } else {
                engine.resumeIfPaused()
            }
        }
    }

    // 初始化 VM
    LaunchedEffect(isGuest) {
        if (!engineInitialized) {
            vm.initialize(isGuest)
            engineInitialized = true
        }
    }

    // 初始频道加载完成后，启动播放
    LaunchedEffect(uiState.initialChannelLoaded) {
        if (uiState.initialChannelLoaded && !engineInitialized) {
            // 防止重复
        }
        if (uiState.initialChannelLoaded) {
            val playlist = vm.getInitialPlaylist()
            if (playlist.isNotEmpty()) {
                engine.setPlaylist(playlist, 0)
            }
        }
    }

    // 请求焦点：确保进入播放器界面后，容器持有焦点以接收遥控器按键
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(200)
        runCatching { focusRequester.requestFocus() }
    }

    // 侧边栏关闭时重新请求焦点（侧边栏内部的列表项会抢焦点，关闭后需归还）
    LaunchedEffect(uiState.sidePanelVisible) {
        if (!uiState.sidePanelVisible) {
            kotlinx.coroutines.delay(150)
            runCatching { focusRequester.requestFocus() }
        }
    }

    // 同步当前播放索引到 ViewModel（用于 Activity 层兜底的确定键弹出侧边栏）
    LaunchedEffect(playerState.currentIndex, playerState.playlistSize) {
        if (playerState.playlistSize > 0) {
            vm.syncCurrentPlayIndex(playerState.currentIndex)
        }
    }

    // 生命周期：onResume / onPause / onDestroy
    DisposableEffect(engine) {
        onDispose {
            engine.release()
        }
    }

    // 侧边栏可见时拦截返回键：
    // - 搜索模式下 → 退出搜索模式（回到首个真实分类）
    // - 否则 → 隐藏侧边面板
    androidx.activity.compose.BackHandler(enabled = uiState.sidePanelVisible) {
        if (uiState.sidePanel.isSearchMode) {
            vm.exitSearchMode()
        } else {
            vm.hideSidePanel()
        }
    }

    // 键盘事件处理 + 触摸手势（tap 切换侧边栏 / 上下划切频道）
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // 焦点管理：确保容器可聚焦并持有焦点
            .focusRequester(focusRequester)
            .focusTarget()
            // 双重按键监听：onPreviewKeyEvent（事件下沉阶段）+ onKeyEvent（事件上浮阶段）
            // 某些 Android TV 设备/PlayerView 可能在下沉阶段消费掉事件，此时上浮阶段仍能捕获
            .onPreviewKeyEvent { event ->
                handlePlayerKeyEvent(
                    event = event,
                    playerState = playerState,
                    sidePanelVisible = uiState.sidePanelVisible,
                    engine = engine,
                    vm = vm,
                    onExit = onExit,
                    currentPlaylistProvider = {
                        val playlist = engine.getCurrentPlaylist()
                        val idx = engine.getCurrentIndex()
                        if (idx in playlist.indices) playlist[idx] else null
                    }
                )
            }
            .onKeyEvent { event ->
                handlePlayerKeyEvent(
                    event = event,
                    playerState = playerState,
                    sidePanelVisible = uiState.sidePanelVisible,
                    engine = engine,
                    vm = vm,
                    onExit = onExit,
                    currentPlaylistProvider = {
                        val playlist = engine.getCurrentPlaylist()
                        val idx = engine.getCurrentIndex()
                        if (idx in playlist.indices) playlist[idx] else null
                    }
                )
            }
            // 手势仅在侧边栏关闭时启用，避免与列表项点击 / 滚动冲突
            .pointerInput(uiState.sidePanelVisible) {
                if (uiState.sidePanelVisible) return@pointerInput
                detectTapAndVerticalDragGestures(
                    onTap = {
                        // 面板关闭：tap 弹出侧边栏，顺带定位到当前播放的频道
                        val cur = engine.run { getCurrentPlaylist().getOrNull(getCurrentIndex()) }
                        vm.toggleSidePanel(cur)
                    },
                    onSwipeUp = {
                        // 上划：上一频道
                        engine.playPrev()
                    },
                    onSwipeDown = {
                        // 下划：下一频道
                        engine.playNext()
                    },
                    onLongPress = {
                        // 长按：切换当前频道收藏状态
                        engine.toggleFavorite()
                    }
                )
            }
    ) {
        // ExoPlayer PlayerView（全屏）
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    // 关键：禁用 PlayerView 的焦点和按键消费，避免它拦截遥控器确定键
                    // 使其只负责渲染视频画面，所有按键交还给外层 Compose 容器处理
                    isFocusable = false
                    isFocusableInTouchMode = false
                    isClickable = false
                    isLongClickable = false
                    // 兜底：如果有按键事件传递到 PlayerView，直接返回 false 不消费
                    setOnKeyListener { _, _, _ -> false }
                }
            },
            update = { view ->
                // currentPlayer 由 StateFlow 驱动，变化时触发 recomposition → update 重新执行
                if (view.player !== currentPlayer) {
                    view.player = currentPlayer
                }
                // 每次 update 时再次确保焦点属性（防止内部状态变化恢复默认值）
                view.isFocusable = false
                view.isFocusableInTouchMode = false
                view.isClickable = false
            },
            modifier = Modifier.fillMaxSize()
        )

        // 加载中 spinner
        if (playerState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp
                )
            }
        }

        // 错误信息
        if (playerState.error != null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = playerState.error!!,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 18.sp,
                    modifier = Modifier.background(
                        Color(0xAA000000), RoundedCornerShape(8.dp)
                    ).padding(16.dp)
                )
            }
        }

        // 信息条（频道名、序号、收藏标记）
        if (playerState.showInfo && playerState.playlistSize > 0) {
            InfoBar(
                state = playerState,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
            )
        }

        // 代理标识：当前频道经由 HLS 代理播放时显示
        if (playerState.usingProxy) {
            ProxyBadge(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            )
        }

        // 底部遥控器操作说明（与信息条同显隐，避免常驻遮挡画面）
        if (playerState.showInfo && playerState.playlistSize > 0) {
            RemoteHint(
                items = listOf(
                    "←" to "收藏",
                    "→" to "重载",
                    "↑↓" to "切台",
                    "确定" to "节目栏",
                    "返回" to "退出"
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            )
        }

        // 面板打开时的遮罩：点击面板外空白区域关闭面板（绘制在 SidePanel 之下）
        if (uiState.sidePanelVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x55000000))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { vm.hideSidePanel() }
                    )
            )
        }

        // 侧边栏面板
        SidePanel(
            data = uiState.sidePanel,
            isVisible = uiState.sidePanelVisible,
            onSourceSelected = { index -> vm.loadCategoriesForSource(index) },
            onCategorySelected = { index ->
                val state = uiState
                vm.loadChannelsForCategory(state.sidePanel.selectedSourceIndex, index)
            },
            onChannelSelected = { channel ->
                // 选择频道：构建播放列表并播放
                val playlist = vm.selectChannel(channel)
                if (playlist.isNotEmpty()) {
                    val list = if (uiState.sidePanel.isSearchMode) {
                        uiState.sidePanel.searchResults
                    } else {
                        uiState.sidePanel.channels
                    }
                    val idx = list.indexOfFirst {
                        it.id == channel.id && it.url == channel.url
                    }.coerceAtLeast(0)
                    engine.setPlaylist(playlist, idx)
                }
                vm.hideSidePanel()
            },
            onAutoHide = { vm.hideSidePanel() },
            onSearchQueryChange = { q -> vm.updateSearchQuery(q) },
            onExitSearch = { vm.exitSearchMode() }
        )

        // 初始加载中
        if (uiState.isLoading && !uiState.initialChannelLoaded) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 3.dp
                    )
                    if (uiState.errorMessage != null) {
                        Text(
                            text = uiState.errorMessage!!,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 16.sp
                        )
                    } else {
                        Text(
                            text = "正在加载频道…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 16.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoBar(state: PlayerState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color(0xAA000000), RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "${state.currentIndex + 1}/${state.playlistSize}",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = state.currentName,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            if (state.isFavorite) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = "已收藏",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.width(16.dp).height(16.dp)
                )
            }
        }
        // 操作结果提示（如"已收藏"/"请先登录"等），3 秒后自动隐藏
        if (!state.transientHint.isNullOrEmpty()) {
            Text(
                text = state.transientHint,
                color = Color(0xFFB3E5FC),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * 代理播放标识：当频道经由 HLS 代理播放时显示在右上角
 */
@Composable
private fun ProxyBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color(0xCC1E88E5), RoundedCornerShape(6.dp))
            .padding(6.dp)
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_proxy),
            contentDescription = "代理播放",
            tint = Color.White,
            modifier = Modifier.width(16.dp).height(16.dp)
        )
    }
}

/**
 * 处理播放器遥控器按键
 */
private fun handlePlayerKeyEvent(
    event: androidx.compose.ui.input.key.KeyEvent,
    playerState: PlayerState,
    sidePanelVisible: Boolean,
    engine: PlayerEngine,
    vm: PlayerViewModel,
    onExit: () -> Unit,
    currentPlaylistProvider: () -> PlaylistItem? = { null }
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false

    val keyCode = event.key.nativeKeyCode

    return when (keyCode) {
        // OK / 确定键
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER -> {
            if (sidePanelVisible) {
                // 侧边栏打开时：不拦截，让事件传递给有焦点的列表项
                false
            } else {
                // 侧边栏关闭时：确定键弹出侧边栏
                val cur = currentPlaylistProvider()
                vm.toggleSidePanel(cur)
                true
            }
        }

        // 上键：上一频道（面板关闭时）
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_CHANNEL_UP,
        KeyEvent.KEYCODE_MEDIA_NEXT -> {
            if (!sidePanelVisible) {
                engine.playPrev()
                true
            } else false
        }

        // 下键：下一频道（面板关闭时）
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_CHANNEL_DOWN,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
            if (!sidePanelVisible) {
                engine.playNext()
                true
            } else false
        }

        // 左键：收藏/取消收藏（面板关闭时）
        KeyEvent.KEYCODE_DPAD_LEFT -> {
            if (!sidePanelVisible) {
                engine.toggleFavorite()
                true
            } else false
        }

        // 右键：手动重载当前频道（面板关闭时）
        KeyEvent.KEYCODE_DPAD_RIGHT -> {
            if (!sidePanelVisible) {
                engine.manualReload()
                true
            } else false
        }

        // 菜单键：收藏/取消收藏（备用，部分遥控器左键被系统拦截时可用）
        KeyEvent.KEYCODE_MENU -> {
            if (!sidePanelVisible) {
                engine.toggleFavorite()
                true
            } else false
        }

        // 返回键：先关面板，再退出（退出逻辑由 Activity 处理双击）
        KeyEvent.KEYCODE_BACK -> {
            if (sidePanelVisible) {
                vm.hideSidePanel()
                true
            } else {
                false // 交给 Activity 处理双击退出
            }
        }

        else -> false
    }
}

/**
 * 自定义手势检测：区分 tap、长按与垂直滑动。
 *
 * 仅在侧边栏关闭时启用（由调用方控制 pointerInput 的 key），此时无子组件竞争事件。
 * - tap：按下后无明显位移即抬起 → [onTap]
 * - 长按：按下后保持不动超过 viewConfiguration.longPressTimeout → [onLongPress]
 * - 上划：向上滑动超过 touchSlop → [onSwipeUp]
 * - 下划：向下滑动超过 touchSlop → [onSwipeDown]
 */
private suspend fun PointerInputScope.detectTapAndVerticalDragGestures(
    onTap: () -> Unit,
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit,
    onLongPress: () -> Unit = {}
) {
    val slop = viewConfiguration.touchSlop
    // Compose 的 ViewConfiguration 未暴露 longPressTimeout，用系统默认值（约 400ms）
    val longPressTimeout = android.view.ViewConfiguration.getLongPressTimeout().toLong()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val downTime = System.currentTimeMillis()
        var totalDy = 0f
        var totalDx = 0f
        var longPressFired = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: break
            totalDy += change.positionChange().y
            totalDx += change.positionChange().x
            // 长按判定：未移动超过 slop 且按下时间超过阈值
            if (!longPressFired && change.pressed) {
                val held = System.currentTimeMillis() - downTime
                val moved = abs(totalDy) > slop || abs(totalDx) > slop
                if (!moved && held >= longPressTimeout) {
                    longPressFired = true
                    onLongPress()
                }
            }
            if (!change.pressed) {
                // 指针抬起：根据累积位移判定手势类型
                val absDy = abs(totalDy)
                val absDx = abs(totalDx)
                when {
                    longPressFired -> { /* 长按已处理，不再触发 tap */ }
                    absDy > slop && absDy >= absDx -> {
                        if (totalDy < 0) onSwipeUp() else onSwipeDown()
                    }
                    absDx > slop -> { /* 水平滑动，忽略 */ }
                    else -> onTap()
                }
                break
            }
        }
    }
}

@Composable
private fun RemoteHint(
    items: List<Pair<String, String>>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(
                Color.Black.copy(alpha = 0.45f),
                RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { (key, desc) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f),
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Text(
                        text = key,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = desc,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }
        }
    }
}
