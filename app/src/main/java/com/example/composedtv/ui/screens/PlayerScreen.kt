@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class
)

package com.example.composedtv.ui.screens

import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import android.view.LayoutInflater
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusable
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
import com.example.composedtv.viewmodel.RendererMode
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface as TvSurface
import com.example.composedtv.R
import com.example.composedtv.debug.DebugDiagnostics
import com.example.composedtv.debug.DebugHudHost
import com.example.composedtv.debug.DebugLogServer
import com.example.composedtv.player.PlayerEngine
import com.example.composedtv.player.PlayerState
import com.example.composedtv.player.PlaylistItem
import com.example.composedtv.ui.components.SettingsDrawer
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

    // 诊断中心初始化（无 ADB 环境下采集播放指标：解码器 / 丢帧 / Surface 注入等）
    LaunchedEffect(Unit) { DebugDiagnostics.init(context) }

    // 创建 PlayerEngine（随 Composable 生命周期管理）
    val engine = remember { PlayerEngine(context) }
    val playerState by engine.stateFlow.collectAsState()

    // ===== 诊断 HUD =====
    // 遥控器数字键 0：开关 HUD；数字键 9：开关局域网 HTTP 导出服务（PC 浏览器直接看）
    // 采样状态由 DebugHudHost 自己持有（作用域隔离），避免 HUD 刷新引发整个
    // PlayerScreen 重组 → AndroidView.update → 重建 Surface → 重启解码器。
    // 开关状态持久化在 PlaybackSettings.diagHudEnabled（SharedPreferences），
    // 重启后保持上次选择；此处本地 state 仅用于即时响应，值由配置流同步。
    var diagVisible by remember { mutableStateOf(uiState.playbackSettings.diagHudEnabled) }
    var diagServerUrl by remember { mutableStateOf<String?>(null) }

    // 持久化配置 → 本地 state（首次进入 / 其他入口修改设置时同步）
    LaunchedEffect(uiState.playbackSettings.diagHudEnabled) {
        diagVisible = uiState.playbackSettings.diagHudEnabled
        DebugDiagnostics.enabled = diagVisible
    }

    // 诊断开关：遥控器数字键与设置抽屉共用同一套逻辑（切换后写入持久化配置）
    val toggleDiag: () -> Unit = {
        val next = !diagVisible
        diagVisible = next
        DebugDiagnostics.enabled = next
        vm.updateDiagHud(next)
        Toast.makeText(
            context,
            if (next) "诊断 HUD 开" else "诊断 HUD 关",
            Toast.LENGTH_SHORT
        ).show()
    }
    val toggleDiagServer: () -> Unit = {
        val url = if (DebugLogServer.isRunning()) {
            DebugLogServer.stop()
            null
        } else {
            DebugLogServer.start()
        }
        diagServerUrl = url
        Toast.makeText(
            context,
            url?.let { "诊断服务: $it" } ?: "诊断服务已停止",
            Toast.LENGTH_LONG
        ).show()
    }

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

    // 播放参数设置变化（或初始加载）→ 同步给 engine（MENU 设置抽屉调整即时生效）
    LaunchedEffect(uiState.playbackSettings) {
        Log.d("PlayerScreen", "applyPlaybackSettings: ${uiState.playbackSettings}")
        engine.applyPlaybackSettings(uiState.playbackSettings)
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

    // 侧边栏 / 设置抽屉关闭时重新请求焦点（内部列表项会抢焦点，关闭后需归还播放器容器）
    LaunchedEffect(uiState.sidePanelVisible, uiState.settingsVisible) {
        if (!uiState.sidePanelVisible && !uiState.settingsVisible) {
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
            DebugLogServer.stop()
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
            // 焦点管理：容器持有焦点；但面板/抽屉打开时禁止自己作为焦点目标(canFocus=false)，
            // 让焦点留在面板/抽屉内部列表项上。注意不能用 focusable()，它会拦截 D-pad 做焦点导航，
            // 导致 onPreviewKeyEvent 收不到上下键、切台失效。focusProperties 只控制焦点归属，不影响按键派发。
            .focusRequester(focusRequester)
            .focusTarget()
            .focusProperties {
                canFocus = !uiState.settingsVisible && !uiState.sidePanelVisible
            }
            // 双重按键监听：onPreviewKeyEvent（事件下沉阶段）+ onKeyEvent（事件上浮阶段）
            // 某些 Android TV 设备/PlayerView 可能在下沉阶段消费掉事件，此时上浮阶段仍能捕获
            .onPreviewKeyEvent { event ->
                handlePlayerKeyEvent(
                    event = event,
                    _playerState = playerState,
                    sidePanelVisible = uiState.sidePanelVisible,
                    settingsVisible = uiState.settingsVisible,
                    engine = engine,
                    vm = vm,
                    _onExit = onExit,
                    onToggleDiag = toggleDiag,
                    onToggleDiagServer = toggleDiagServer,
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
                    _playerState = playerState,
                    sidePanelVisible = uiState.sidePanelVisible,
                    settingsVisible = uiState.settingsVisible,
                    engine = engine,
                    vm = vm,
                    _onExit = onExit,
                    onToggleDiag = toggleDiag,
                    onToggleDiagServer = toggleDiagServer,
                    currentPlaylistProvider = {
                        val playlist = engine.getCurrentPlaylist()
                        val idx = engine.getCurrentIndex()
                        if (idx in playlist.indices) playlist[idx] else null
                    }
                )
            }
            // 手势仅在侧边栏与设置抽屉「都关闭」时启用，避免与列表项点击 / 滚动冲突。
            // 关键：设置抽屉也必须禁用。抽屉固定在右半屏，而本手势把「右半屏 tap」
            // 解释为「弹出设置抽屉」——抽屉打开时若手势仍生效，点击抽屉内任意位置
            // （分类/选项）都会被 onTap 判定为右半屏 tap，再 toggle 一次就把抽屉关掉，
            // 表现为「点了分类、还没选右排选项就退出」。
            // 注意：本手势用 awaitFirstDown(requireUnconsumed = false)，指针事件即使
            // 已被抽屉内部消费，这里仍会收到，因此必须在抽屉打开时主动 return。
            .pointerInput(uiState.sidePanelVisible, uiState.settingsVisible) {
                if (uiState.sidePanelVisible || uiState.settingsVisible) return@pointerInput
                detectTapAndVerticalDragGestures(
                    onTap = { isRightSide ->
                        if (isRightSide) {
                            // 右半屏 tap：弹出设置抽屉
                            vm.toggleSettingsDrawer()
                        } else {
                            // 左半屏 tap：弹出侧边栏（节目单），定位到当前播放的频道
                            val cur = engine.run { getCurrentPlaylist().getOrNull(getCurrentIndex()) }
                            vm.toggleSidePanel(cur)
                        }
                    },
                    onSwipeUp = {
                        // 上划：下一频道（与频道列表方向一致）
                        engine.playNext()
                    },
                    onSwipeDown = {
                        // 下划：上一频道
                        engine.playPrev()
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
                // 渲染方式：Auto（老设备 Android6 自动用 TextureView 规避 SurfaceView 黑屏）。
                // TextureView 通过 Canvas 绘制到视图层，与 Compose 叠加层（字幕/角标）Z 序一致，
                // 修复海信老电视上 SurfaceView 独立 Window 导致的"有声音没画面"
                val useTexture = uiState.playbackSettings.rendererMode.useTextureView()
                val view = if (useTexture) {
                    LayoutInflater.from(ctx).inflate(
                        com.example.composedtv.R.layout.player_view_texture, null
                    ) as PlayerView
                } else {
                    LayoutInflater.from(ctx).inflate(
                        com.example.composedtv.R.layout.player_view_surface, null
                    ) as PlayerView
                }
                view.apply {
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
                // 监听内部视频渲染视图的 Surface 就绪，注入 engine 供竞速副路共享
                val videoView = view.getVideoSurfaceView()
                when (videoView) {
                    is SurfaceView -> videoView.holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            engine.setSharedSurface(holder.surface)
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder, format: Int, width: Int, height: Int
                        ) {
                            engine.setSharedSurface(holder.surface)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            engine.setSharedSurface(null)
                        }
                    })
                    is TextureView -> {
                        // 关键修复（API<23 + TextureView）：
                        // Surface(surfaceTexture) 每次都会 new 新对象，而 API<23 没有
                        // setOutputSurface，每次 setVideoSurface 都会重建解码器。
                        // 因此 TextureView 场景下：缓存同一个 Surface 对象，只注入一次，
                        // 后续 update 重组不再重复 set（避免"图片轮播"式反复重建）。
                        val cachedSurface = kotlin.lazy { Surface(videoView.surfaceTexture) }
                        val injectSurface = {
                            val st = videoView.surfaceTexture
                            if (st != null) engine.setSharedSurface(cachedSurface.value)
                        }
                        videoView.addOnAttachStateChangeListener(object :
                            android.view.View.OnAttachStateChangeListener {
                            override fun onViewAttachedToWindow(v: android.view.View) {
                                v.post { injectSurface() }
                            }

                            override fun onViewDetachedFromWindow(v: android.view.View) {
                                engine.setSharedSurface(null)
                            }
                        })
                        // 兜底：view 已 attach 时直接尝试一次
                        videoView.post { injectSurface() }
                    }
                    else -> {}
                }
                view
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
                // 将 PlayerView 的视频 Surface 注入 engine，让竞速所有候选（含代理副路）共用同一 Surface：
                // 副路可真正出画参与竞速、胜出切换不重建 Surface（修复老电视黑屏/丢帧）
                // 注意：TextureView 场景已在 factory 注入并缓存 Surface 对象，此处不再重复 set，
                // 否则每次重组 new 新 Surface + API<23 无 setOutputSurface → 解码器反复重建。
                val videoView = view.getVideoSurfaceView()
                val surface = when (videoView) {
                    is SurfaceView -> videoView.holder.surface.takeIf { it.isValid }
                    else -> null
                }
                if (surface != null) {
                    engine.setSharedSurface(surface)
                }
            },
            // 设置抽屉打开时，连 Compose 焦点层也阻断播放器，确保焦点不会留在播放器上
            modifier = Modifier
                .fillMaxSize()
                .focusable(enabled = !uiState.settingsVisible)
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
                        "MENU" to "设置",
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
            onSourceSelected = { index -> vm.loadCategoriesForSource(index, force = true) },
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

        // 右侧配置抽屉（MENU 键唤出）：调整播放参数
        SettingsDrawer(
            visible = uiState.settingsVisible,
            settings = uiState.playbackSettings,
            onStuckTimeoutChange = { vm.updateStuckTimeout(it) },
            onHedgeChange = { vm.updateHedge(it) },
            onReviveChange = { vm.updateReviveMax(it) },
            onRendererChange = { vm.updateRendererMode(it) },
            diagEnabled = diagVisible,
            diagServerUrl = diagServerUrl,
            onToggleDiag = toggleDiag,
            onToggleDiagServer = toggleDiagServer,
            onResetDiag = { DebugDiagnostics.resetCounters() }
        )

        // 诊断 HUD（绘制在最上层；遥控器数字键 0 开关）
        // 设置抽屉 / 节目栏打开时自动隐藏：HUD 固定在右上角，与右侧 560dp 宽的
        // 设置抽屉、左侧节目栏会重叠遮挡，此时显示 HUD 也无意义。
        val hudVisible = diagVisible && !uiState.settingsVisible && !uiState.sidePanelVisible
        if (hudVisible) {
            DebugHudHost(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .width(380.dp)
            )
        }

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
@Suppress("UNUSED_PARAMETER")
private fun handlePlayerKeyEvent(
    event: androidx.compose.ui.input.key.KeyEvent,
    _playerState: PlayerState,
    sidePanelVisible: Boolean,
    settingsVisible: Boolean,
    engine: PlayerEngine,
    vm: PlayerViewModel,
    _onExit: () -> Unit,
    onToggleDiag: () -> Unit = {},
    onToggleDiagServer: () -> Unit = {},
    currentPlaylistProvider: () -> PlaylistItem? = { null }
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false

    val keyCode = event.key.nativeKeyCode

    return when (keyCode) {
        // OK / 确定键
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER -> {
            if (settingsVisible) {
                // 配置抽屉打开时：不拦截，让焦点项处理选择
                false
            } else if (sidePanelVisible) {
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
            if (settingsVisible) false
            else if (!sidePanelVisible) {
                engine.playPrev()
                true
            } else false
        }

        // 下键：下一频道（面板关闭时）
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_CHANNEL_DOWN,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
            if (settingsVisible) false
            else if (!sidePanelVisible) {
                engine.playNext()
                true
            } else false
        }

        // 左键：收藏/取消收藏（面板关闭时）
        KeyEvent.KEYCODE_DPAD_LEFT -> {
            if (settingsVisible) false
            else if (!sidePanelVisible) {
                engine.toggleFavorite()
                true
            } else false
        }

        // 右键：手动重载当前频道（面板关闭时）
        KeyEvent.KEYCODE_DPAD_RIGHT -> {
            if (settingsVisible) false
            else if (!sidePanelVisible) {
                engine.manualReload()
                true
            } else false
        }

        // 数字键 0：开关诊断 HUD（无 ADB 时在画面上直接看解码器/丢帧/Surface 指标）
        KeyEvent.KEYCODE_0 -> {
            onToggleDiag()
            true
        }

        // 数字键 9：开关局域网诊断 HTTP 服务（PC 浏览器访问投影 IP 查看/导出日志）
        KeyEvent.KEYCODE_9 -> {
            onToggleDiagServer()
            true
        }

        // 菜单键：唤出/收起右侧配置抽屉（与侧边栏互斥）
        KeyEvent.KEYCODE_MENU -> {
            vm.toggleSettingsDrawer()
            true
        }

        // 返回键：先关面板，再退出（退出逻辑由 Activity 处理双击）
        KeyEvent.KEYCODE_BACK -> {
            if (settingsVisible) {
                vm.hideSettingsDrawer()
                true
            } else if (sidePanelVisible) {
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
    onTap: (isRightSide: Boolean) -> Unit,
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit,
    onLongPress: () -> Unit = {}
) {
    val slop = viewConfiguration.touchSlop
    // 长按阈值固定为 250ms（系统默认约 400ms+，用户反馈太长）
    val longPressTimeout = 250L
    val halfWidth = size.width / 2f
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val downTime = System.currentTimeMillis()
        val startX = down.position.x
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
                    // tap：按起始点判定左/右半屏
                    else -> onTap(startX >= halfWidth)
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
