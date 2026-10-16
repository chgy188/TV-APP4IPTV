package com.example.composedtv.ui.screens

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
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
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.example.composedtv.R
import com.example.composedtv.debug.DebugDiagnostics
import com.example.composedtv.debug.DebugHudHost
import com.example.composedtv.player.PlayerEngine
import com.example.composedtv.player.PlayerState
import com.example.composedtv.data.VodProgressStore
import com.example.composedtv.player.PlaylistItem
import com.example.composedtv.ui.components.SettingsDrawer
import com.example.composedtv.ui.components.SidePanel
import com.example.composedtv.viewmodel.ChannelEntry
import com.example.composedtv.viewmodel.PlayerViewModel
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.delay

/**
 * 监测系统软键盘（IME）是否可见。
 * 用于区分返回键是「收起键盘」还是「退出面板/搜索」，避免输入后按返回直接关闭面板。
 */
@Composable
private fun rememberImeState(): State<Boolean> {
    val imeState = remember { mutableStateOf(false) }
    val view = LocalView.current
    LaunchedEffect(view) {
        while (true) {
            // getRootWindowInsets() 仅 API23+ 可用；老设备（极米 API21/22）直接降级为不可见
            val visible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val root = view.rootWindowInsets
                WindowInsetsCompat.toWindowInsetsCompat(root)
                    .isVisible(WindowInsetsCompat.Type.ime())
            } else {
                false
            }
            if (imeState.value != visible) imeState.value = visible
            delay(100)
        }
    }
    return imeState
}

/**
 * 点播（VOD）遥控按键映射：外部视频文件播放时启用控制条 / 续播记忆。
 * 左右键快退/快进、确定/空格暂停播放，其余按键（返回/菜单）仍交给直播逻辑处理。
 */
private val VOD_SEEK_KEYS = setOf(
    android.view.KeyEvent.KEYCODE_DPAD_LEFT,
    android.view.KeyEvent.KEYCODE_MEDIA_REWIND,
    android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
    android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
)
private val VOD_SIDE_PANEL_KEYS = setOf(
    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
    android.view.KeyEvent.KEYCODE_ENTER,
    android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
)
private val VOD_TOGGLE_PAUSE_KEYS = setOf(
    android.view.KeyEvent.KEYCODE_SPACE,
    android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
)

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
    onExit: () -> Unit,
    onExitApp: () -> Unit,
    externalUri: Uri? = null
) {
    val context = LocalContext.current
    val uiState by vm.uiState.collectAsState()

    // 诊断中心初始化（无 ADB 环境下采集播放指标：解码器 / 丢帧 / Surface 注入等）
    LaunchedEffect(Unit) { DebugDiagnostics.init(context) }

    // 创建 PlayerEngine（随 Composable 生命周期管理）
    val engine = remember { PlayerEngine(context) }
    val playerState by engine.stateFlow.collectAsState()

    // ===== 诊断 HUD =====
    // 遥控器数字键 0：开关 HUD（无 ADB 时在画面上直接看解码器/丢帧/Surface 指标）
    // 采样状态由 DebugHudHost 自己持有（作用域隔离），避免 HUD 刷新引发整个
    // PlayerScreen 重组 → AndroidView.update → 重建 Surface → 重启解码器。
    // 开关状态持久化在 PlaybackSettings.diagHudEnabled（SharedPreferences），
    // 重启后保持上次选择；此处本地 state 仅用于即时响应，值由配置流同步。
    var diagVisible by remember { mutableStateOf(uiState.playbackSettings.diagHudEnabled) }

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

    // 收藏变更（添加 / 取消）→ 通知 VM 刷新侧边栏「收藏」分类列表，做到即时生效
    LaunchedEffect(Unit) {
        engine.favoritesChanged.collect { vm.onFavoritesChanged() }
    }

    // 播放参数设置变化（或初始加载）→ 同步给 engine（MENU 设置抽屉调整即时生效）
    LaunchedEffect(uiState.playbackSettings) {
        Log.d("PlayerScreen", "applyPlaybackSettings: ${uiState.playbackSettings}")
        engine.applyPlaybackSettings(uiState.playbackSettings)
    }
    val currentPlayer by engine.playerFlow.collectAsState()
    var engineInitialized by remember { mutableStateOf(false) }

    // ===== 字幕选择（外部本地文件播放时可用；CC 键唤出）=====
    var subtitleChooserVisible by remember { mutableStateOf(false) }
    var subtitleOptions by remember { mutableStateOf<List<PlayerEngine.SubtitleOption>>(emptyList()) }
    var subtitleSelectedIndex by remember { mutableStateOf(0) }

    // ===== VOD（点播）控制：由 engine 的 isLive（PlayerState）驱动，随播放源变化实时切换。
    // 另：通过其他 App 以 ACTION_VIEW 打开的本地/网络文件（externalUri != null）必然可 seek，
    // 故直接判定为点播，避免首帧误判为直播（如 .ts 录制文件）导致控制条/进度/字幕不可用。
    val isVod = externalUri != null || !playerState.isLive
    val vodId = externalUri?.toString() ?: ""
    var vodControlsVisible by remember { mutableStateOf(true) }
    var vodControlsAutoHideKey by remember { mutableStateOf(0) }
    /** 控制条按钮焦点索引：null=无按钮聚焦，0..5 对应 «10s / ⏸ / 10s» / 列表 / 收藏 / 字幕 */
    var vodButtonFocusIndex by remember { mutableStateOf<Int?>(null) }
    var vodPosition by remember { mutableStateOf(0L) }
    var vodDuration by remember { mutableStateOf(0L) }
    var vodResumeDone by remember { mutableStateOf(false) }
    val vodSavedPos = remember(vodId) { if (vodId.isNotBlank()) VodProgressStore.load(context, vodId) else 0L }

    // 把 VOD 模式同步给 ViewModel，供 MainActivity 在 dispatchKeyEvent 中决定是否拦截左右/确定键
    LaunchedEffect(isVod) { vm.setIsVod(isVod) }

    val openSubtitleChooser: () -> Unit = {
        subtitleOptions = engine.getSubtitleTracks()
        // UI 索引：0=关闭字幕, 1..N 对应 options[0..N-1]
        val selIdx = subtitleOptions.indexOfFirst { it.selected }
        subtitleSelectedIndex = if (selIdx >= 0) selIdx + 1 else 0
        subtitleChooserVisible = true
    }
    val confirmSubtitleSelection: () -> Unit = {
        // UI 索引 0=关闭, >=1 对应 options[idx-1]
        val opt = if (subtitleSelectedIndex <= 0) null
                  else subtitleOptions.getOrNull(subtitleSelectedIndex - 1)
        engine.selectSubtitle(opt)
        subtitleChooserVisible = false
        Toast.makeText(context, "字幕：${opt?.label ?: "关闭"}", Toast.LENGTH_SHORT).show()
    }
    fun handleSubtitleChooserKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val uiCount = subtitleOptions.size + 1 // +1 for "关闭字幕"
        when (event.key) {
            Key.DirectionUp -> {
                subtitleSelectedIndex = (subtitleSelectedIndex - 1).coerceAtLeast(0)
                return true
            }
            Key.DirectionDown -> {
                subtitleSelectedIndex = (subtitleSelectedIndex + 1).coerceAtMost(uiCount - 1)
                return true
            }
            Key.DirectionCenter, Key.Enter -> {
                confirmSubtitleSelection()
                return true
            }
            Key.Back, Key.Menu -> {
                subtitleChooserVisible = false
                return true
            }
            else -> return false
        }
    }

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

    // 初始化 VM（外部视频文件播放时跳过：无需登录/频道列表，直接单文件起播）
    LaunchedEffect(isGuest) {
        if (externalUri == null && !engineInitialized) {
            vm.initialize(isGuest)
            engineInitialized = true
        }
    }

    // 外部视频文件播放（来自其他 App 的 ACTION_VIEW）：绕过登录/频道列表，直接单文件播放，
    // 并设置字幕默认偏好顺序为中文→英文（无中文字幕时回退英文，均无则关闭）。
    LaunchedEffect(externalUri) {
        if (externalUri != null) {
            val name = resolveDisplayName(context, externalUri)
            engine.setPreferredTextLanguages(listOf("zh", "en"))
            engine.setPlaylist(
                listOf(PlaylistItem(name = name, url = externalUri.toString(), country = "")),
                0
            )
        }
    }

    // VOD：轮询进度，并做断点续播 + 周期保存播放位置
    LaunchedEffect(isVod) {
        if (!isVod) return@LaunchedEffect
        var saveCounter = 0
        while (true) {
            vodPosition = engine.getCurrentPosition()
            vodDuration = engine.getDuration()
            // 续播：拿到时长后跳到上次位置（仅一次）
            if (!vodResumeDone && vodDuration > 0 && vodSavedPos > 0) {
                engine.seekTo(vodSavedPos)
                vodResumeDone = true
                Log.d("PlayerScreen", "VOD 续播：跳至 ${vodSavedPos}ms")
            }
            // 播放到末尾：清除记录，下次从头开始；否则每 ~5s 保存一次
            if (vodDuration > 0 && vodPosition >= vodDuration - 1500) {
                VodProgressStore.clear(context, vodId)
            } else if (++saveCounter >= 10) {
                VodProgressStore.save(context, vodId, vodPosition)
                saveCounter = 0
            }
            delay(500)
        }
    }

    // VOD：播放中控制条自动隐藏（任意交互会重新显示）
    // 注意：isVod 必须在 key 中。开播时 isLive 默认为 true → isVod=false，
    // 控制条不渲染；等引擎识别为点播后 isVod 变 true，LaunchedEffect 必须重启才能启动 timer。
    LaunchedEffect(isVod, vodControlsVisible, playerState.isPlaying, vodControlsAutoHideKey) {
        if (isVod && vodControlsVisible && playerState.isPlaying) {
            delay(2500)
            vodControlsVisible = false
            vodButtonFocusIndex = null
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
                // 起始索引：续播上次退出频道时不为 0
                val startIdx = uiState.initialPlayIndex.coerceIn(0, playlist.lastIndex)
                engine.setPlaylist(playlist, startIdx)
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
            if (externalUri != null) {
                VodProgressStore.save(context, externalUri.toString(), engine.getCurrentPosition())
            }
            engine.release()
        }
    }

    // 软键盘可见性（轮询 WindowInsets），用于区分「返回键收起键盘」与「返回键退出面板/搜索」
    val imeVisible by rememberImeState()
    val rootView = LocalView.current
    // 侧边栏可见时拦截返回键：
    // - 软键盘打开时 → 先收起键盘，不直接退出（避免输入后按返回导致面板消失）
    // - 搜索模式下 → 退出搜索模式（回到首个真实分类）
    // - 否则 → 隐藏侧边面板
    androidx.activity.compose.BackHandler(enabled = uiState.sidePanelVisible) {
        if (imeVisible) {
            val imm = rootView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(rootView.windowToken, 0)
            return@BackHandler
        }
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
                when {
                    subtitleChooserVisible -> handleSubtitleChooserKey(event)
                    event.key.nativeKeyCode == KeyEvent.KEYCODE_CAPTIONS && event.type == KeyEventType.KeyDown -> {
                        openSubtitleChooser()
                        true
                    }
                    // VOD 控制条按钮焦点导航（遥控器方向键 / 确定键）
                    // 仅当侧边栏和设置抽屉都关闭时才拦截；否则让事件传给面板内焦点项
                    !uiState.sidePanelVisible && !uiState.settingsVisible &&
                    isVod && vodControlsVisible && vodButtonFocusIndex != null && event.type == KeyEventType.KeyDown -> {
                        val key = event.key.nativeKeyCode
                        val btnCount = 6 // «10s / ⏸ / 10s» / 列表 / 收藏 / 字幕
                        when (key) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                vodButtonFocusIndex = ((vodButtonFocusIndex!! - 1) + btnCount) % btnCount
                                vodControlsAutoHideKey++
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                vodButtonFocusIndex = (vodButtonFocusIndex!! + 1) % btnCount
                                vodControlsAutoHideKey++
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                vodButtonFocusIndex = null
                                vodControlsAutoHideKey++
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                when (vodButtonFocusIndex) {
                                    0 -> engine.seekBy(-10_000)
                                    1 -> engine.togglePause()
                                    2 -> engine.seekBy(10_000)
                                    3 -> {
                                        vodControlsVisible = false
                                        vodButtonFocusIndex = null
                                        val cur = engine.run { getCurrentPlaylist().getOrNull(getCurrentIndex()) }
                                        vm.toggleSidePanel(cur)
                                    }
                                    4 -> engine.toggleFavorite()
                                    5 -> openSubtitleChooser()
                                }
                                vodControlsVisible = true
                                vodControlsAutoHideKey++
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                // 下键在按钮已聚焦时不做导航（保持在当前按钮），但重置自动隐藏
                                vodControlsAutoHideKey++
                                true
                            }
                            KeyEvent.KEYCODE_BACK -> {
                                vodButtonFocusIndex = null
                                vodControlsAutoHideKey++
                                true
                            }
                            else -> false
                        }
                    }
                    !uiState.sidePanelVisible && !uiState.settingsVisible &&
                    isVod && event.type == KeyEventType.KeyDown && event.key.nativeKeyCode in VOD_SEEK_KEYS -> {
                        engine.seekBy(
                            if (event.key.nativeKeyCode == KeyEvent.KEYCODE_DPAD_LEFT
                                || event.key.nativeKeyCode == KeyEvent.KEYCODE_MEDIA_REWIND
                            ) -10_000 else 10_000
                        )
                        vodControlsVisible = true
                        vodControlsAutoHideKey++
                        true
                    }
                    !uiState.sidePanelVisible && !uiState.settingsVisible &&
                    isVod && event.type == KeyEventType.KeyDown && event.key.nativeKeyCode in VOD_SIDE_PANEL_KEYS -> {
                        // OK/Enter：打开节目栏，暂停/恢复由 sidePanelVisible 的 LaunchedEffect 处理
                        vodControlsVisible = true
                        vodControlsAutoHideKey++
                        val cur = engine.run { getCurrentPlaylist().getOrNull(getCurrentIndex()) }
                        vm.toggleSidePanel(cur)
                        true
                    }
                    // VOD 空格/媒体播放暂停键：仅切换暂停，不打开侧边栏
                    !uiState.sidePanelVisible && !uiState.settingsVisible &&
                    isVod && event.type == KeyEventType.KeyDown && event.key.nativeKeyCode in VOD_TOGGLE_PAUSE_KEYS -> {
                        engine.togglePause()
                        vodControlsVisible = true
                        vodControlsAutoHideKey++
                        true
                    }
                    // VOD 控制条可见但无按钮聚焦时：下键进入控制条、上键不做切台
                    // 仅当侧边栏和设置抽屉都关闭时才拦截
                    !uiState.sidePanelVisible && !uiState.settingsVisible &&
                    isVod && vodControlsVisible && event.type == KeyEventType.KeyDown -> {
                        when (event.key.nativeKeyCode) {
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                vodButtonFocusIndex = 0
                                vodControlsAutoHideKey++
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                // 上键不切台，仅重置自动隐藏计时
                                vodControlsAutoHideKey++
                                true
                            }
                            else -> false
                        }
                    }
                    else -> handlePlayerKeyEvent(
                        event = event,
                        _playerState = playerState,
                        sidePanelVisible = uiState.sidePanelVisible,
                        settingsVisible = uiState.settingsVisible,
                        isSearchMode = uiState.sidePanel.isSearchMode,
                        engine = engine,
                        vm = vm,
                        _onExit = onExit,
                        onExitApp = onExitApp,
                        onToggleDiag = toggleDiag,
                        currentPlaylistProvider = {
                            val playlist = engine.getCurrentPlaylist()
                            val idx = engine.getCurrentIndex()
                            if (idx in playlist.indices) playlist[idx] else null
                        },
                        isVod = isVod
                    )
                }
            }
            .onKeyEvent { event ->
                handlePlayerKeyEvent(
                    event = event,
                    _playerState = playerState,
                    sidePanelVisible = uiState.sidePanelVisible,
                    settingsVisible = uiState.settingsVisible,
                    isSearchMode = uiState.sidePanel.isSearchMode,
                    engine = engine,
                    vm = vm,
                    _onExit = onExit,
                    onExitApp = onExitApp,
                    onToggleDiag = toggleDiag,
                    currentPlaylistProvider = {
                        val playlist = engine.getCurrentPlaylist()
                        val idx = engine.getCurrentIndex()
                        if (idx in playlist.indices) playlist[idx] else null
                    },
                    isVod = isVod
                )
            }
            // 手势仅在侧边栏与设置抽屉「都关闭」时启用，避免与列表项点击 / 滚动冲突。
            // 关键：设置抽屉也必须禁用。抽屉固定在右半屏，而本手势把「右半屏 tap」
            // 解释为「弹出设置抽屉」——抽屉打开时若手势仍生效，点击抽屉内任意位置
            // （分类/选项）都会被 onTap 判定为右半屏 tap，再 toggle 一次就把抽屉关掉，
            // 表现为「点了分类、还没选右排选项就退出」。
            // 注意：本手势用 awaitFirstDown(requireUnconsumed = false)，指针事件即使
            // 已被抽屉内部消费，这里仍会收到，因此必须在抽屉打开时主动 return。
            .pointerInput(uiState.sidePanelVisible, uiState.settingsVisible, isVod) {
                if (uiState.sidePanelVisible || uiState.settingsVisible) return@pointerInput
                detectTapAndVerticalDragGestures(
                    onTap = { isRightSide ->
                        if (isVod) {
                            // 点播：点击画面切换控制条显隐
                            vodControlsVisible = !vodControlsVisible
                            if (vodControlsVisible) vodControlsAutoHideKey++
                            else vodButtonFocusIndex = null
                        } else if (isRightSide) {
                            // 右半屏 tap：弹出设置抽屉
                            vm.toggleSettingsDrawer()
                        } else {
                            // 左半屏 tap：弹出侧边栏（节目单），定位到当前播放的频道
                            val cur = engine.run { getCurrentPlaylist().getOrNull(getCurrentIndex()) }
                            vm.toggleSidePanel(cur)
                        }
                    },
                    onSwipeUp = { if (!isVod) engine.playNext() },
                    onSwipeDown = { if (!isVod) engine.playPrev() },
                    onLongPress = { if (!isVod) engine.toggleFavorite() },
                    onHorizontalDrag = { dx ->
                        // 点播：水平滑动快进/快退（手机手势）；整屏宽度对应整段时长
                        if (isVod && vodDuration > 0) {
                            val screenW = context.resources.displayMetrics.widthPixels
                                .toFloat().coerceAtLeast(1f)
                            engine.seekBy((dx * (vodDuration / screenW)).toLong())
                            vodControlsVisible = true
                            vodControlsAutoHideKey++
                        }
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

        // 加载中 spinner + 当前加载模式 / 超时（简洁展示）
        if (playerState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 3.dp
                    )
                    Text(
                        text = when {
                            playerState.loadMode == "proxy" -> "代理加载中…"
                            playerState.loadMode == "direct" -> "直连加载中…"
                            // 起播完成后 loadMode 置空，按实际代理状态区分播放期重新缓冲
                            playerState.usingProxy -> "代理缓冲中…"
                            else -> "直连缓冲中…"
                        },
                        color = Color.White,
                        fontSize = 15.sp
                    )
                    if (playerState.loadSpeedKbps > 0) {
                        val speedText = if (playerState.loadSpeedKbps >= 1000) {
                            String.format("%.1f Mb/s", playerState.loadSpeedKbps / 1000f)
                        } else {
                            "${playerState.loadSpeedKbps} Kb/s"
                        }
                        Text(
                            text = speedText,
                            color = Color(0xFF4FC3F7),
                            fontSize = 13.sp
                        )
                    }
                    if (playerState.loadRemainMs > 0) {
                        // 向上取整，避免最后一秒显示 0
                        val remainSec = (playerState.loadRemainMs + 999) / 1000
                        Text(
                            text = "${remainSec}s 后自动切换",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                }
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

        // 侧边栏面板（退出搜索由返回键 BackHandler 统一处理 → vm.exitSearchMode）
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

        // 字幕选择弹层（CC 键唤出）：列出当前视频的字幕轨，可手动选择或关闭
        if (subtitleChooserVisible) {
            SubtitleChooser(
                options = subtitleOptions,
                selectedIndex = subtitleSelectedIndex,
                onSelect = { idx ->
                    subtitleSelectedIndex = idx
                    confirmSubtitleSelection()
                },
                onDismiss = { subtitleChooserVisible = false }
            )
        }

        // VOD 控制条（外部本地文件播放时显示：进度/快退快进/字幕/暂停）
        if (isVod && vodControlsVisible) {
            VodControlBar(
                positionMs = vodPosition,
                durationMs = vodDuration,
                isPlaying = playerState.isPlaying,
                isFavorite = playerState.isFavorite,
                focusedIndex = vodButtonFocusIndex,
                onSeekDelta = { engine.seekBy(it); vodControlsVisible = true; vodControlsAutoHideKey++ },
                onSeekTo = { engine.seekTo(it); vodControlsVisible = true; vodControlsAutoHideKey++ },
                onPlayPause = { engine.togglePause(); vodControlsVisible = true; vodControlsAutoHideKey++ },
                onOpenPlaylist = {
                    vodControlsVisible = false
                    vodButtonFocusIndex = null
                    val cur = engine.run { getCurrentPlaylist().getOrNull(getCurrentIndex()) }
                    vm.toggleSidePanel(cur)
                },
                onFavorite = { engine.toggleFavorite(); vodControlsAutoHideKey++ },
                onSubtitle = { openSubtitleChooser() },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // 右侧配置抽屉（MENU 键唤出）：调整播放参数
        SettingsDrawer(
            visible = uiState.settingsVisible,
            settings = uiState.playbackSettings,
            onDirectTimeoutChange = { vm.updateDirectTimeout(it) },
            onProxyTimeoutChange = { vm.updateProxyTimeout(it) },
            onRendererChange = { vm.updateRendererMode(it) },
            onSmoothPriorityChange = { vm.updateSmoothPriority(it) },
            onAutoAvSyncChange = { vm.updateAutoAvSync(it) },
            isGuest = isGuest,
            onStartChannelModeChange = { vm.updateStartChannelMode(it) },
            diagEnabled = diagVisible,
            onToggleDiag = toggleDiag,
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
    isSearchMode: Boolean,
    engine: PlayerEngine,
    vm: PlayerViewModel,
    _onExit: () -> Unit,
    onExitApp: () -> Unit,
    onToggleDiag: () -> Unit = {},
    currentPlaylistProvider: () -> PlaylistItem? = { null },
    isVod: Boolean = false
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
        // 搜索模式下不拦截，让搜索框接收数字输入
        KeyEvent.KEYCODE_0 -> {
            if (isSearchMode) false
            else {
                onToggleDiag()
                true
            }
        }

        // 菜单键：唤出/收起右侧配置抽屉（与侧边栏互斥）
        KeyEvent.KEYCODE_MENU -> {
            vm.toggleSettingsDrawer()
            true
        }

        // 返回键：先关面板；主播放界面（无面板）首次返回→停播，停播状态再返回→完全退出 APP
        KeyEvent.KEYCODE_BACK -> {
            if (settingsVisible) {
                vm.hideSettingsDrawer()
                true
            } else if (sidePanelVisible) {
                vm.hideSidePanel()
                true
            } else if (_playerState.isPlaying) {
                // 正在播放（直播或点播）：立即停播（释放解码器/资源，停留在当前界面）
                engine.stopPlayback()
                true
            } else {
                // 已停播（或非播放态）：完全退出 APP
                onExitApp()
                true
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
    onLongPress: () -> Unit = {},
    onHorizontalDrag: (Float) -> Unit = {}
) {
    val slop = viewConfiguration.touchSlop
    // 长按阈值固定为 250ms（系统默认约 400ms+，用户反馈太长）
    val longPressTimeout = 250L
    val halfWidth = size.width / 2f
    awaitEachGesture {
        // requireUnconsumed = true：子组件（控制条按钮、抽屉）已消费的点击不再下发到本手势，
        // 避免「点节目栏按钮」时被手势同时把控制条重新置为可见，盖住节目栏。
        val down = awaitFirstDown(requireUnconsumed = true)
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
            // 水平滑动（点播 seek）：横向位移超过阈值且明显大于纵向时，逐帧上报横向位移
            if (abs(totalDx) > slop && abs(totalDx) > abs(totalDy)) {
                onHorizontalDrag(change.positionChange().x)
            }
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
private fun VodControlBar(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    isFavorite: Boolean,
    focusedIndex: Int?,
    onSeekDelta: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onOpenPlaylist: () -> Unit,
    onFavorite: () -> Unit,
    onSubtitle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color(0x99000000), Color(0xCC000000))
                )
            )
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .focusProperties { canFocus = false }
    ) {
        val ratio = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        Slider(
            value = ratio,
            onValueChange = { newRatio ->
                if (durationMs > 0) onSeekTo((newRatio * durationMs).toLong())
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color(0xFF4FC3F7),
                inactiveTrackColor = Color(0x33FFFFFF),
                disabledThumbColor = Color.Transparent,
                disabledActiveTrackColor = Color.Transparent,
                disabledInactiveTrackColor = Color.Transparent
            )
        )
        Spacer(Modifier.height(2.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CtlFocusButton("«10s", isFocused = focusedIndex == 0) { onSeekDelta(-10_000) }
            CtlFocusButton(if (isPlaying) "⏸" else "▶", isFocused = focusedIndex == 1) { onPlayPause() }
            CtlFocusButton("10s»", isFocused = focusedIndex == 2) { onSeekDelta(10_000) }
            CtlFocusButton("列表", isFocused = focusedIndex == 3) { onOpenPlaylist() }
            CtlFocusButton(if (isFavorite) "★" else "☆", isFocused = focusedIndex == 4) { onFavorite() }
            Spacer(Modifier.width(8.dp))
            Text(formatTime(positionMs), color = Color(0xCCFFFFFF), fontSize = 12.sp)
            Text("/", color = Color(0x66FFFFFF), fontSize = 12.sp)
            Text(formatTime(durationMs), color = Color(0xCCFFFFFF), fontSize = 12.sp)
            CtlFocusButton("字幕", isFocused = focusedIndex == 5) { onSubtitle() }
            Spacer(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun CtlFocusButton(text: String, isFocused: Boolean = false, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    isPressed -> Color(0x55FFFFFF)
                    isFocused -> Color(0x33FFFFFF)
                    else -> Color.Transparent
                }
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.DirectionCenter || event.key == Key.Enter)) {
                    onClick()
                    true
                } else false
            }
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            color = Color(0xEEFFFFFF),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format("%02d:%02d:%02d", h, m, s)
    else String.format("%02d:%02d", m, s)
}

@Composable
private fun SubtitleChooser(
    options: List<PlayerEngine.SubtitleOption>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val uiItems = listOf<String?>(null) + options.map { it.label }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = selectedIndex.coerceIn(0, uiItems.lastIndex.coerceAtLeast(0))
    )
    LaunchedEffect(selectedIndex) {
        listState.animateScrollToItem(selectedIndex)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xAA000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 340.dp)
                .heightIn(max = 500.dp),
            shape = RoundedCornerShape(10.dp),
            color = Color(0xEE222222)
        ) {
            Column {
                Text(
                    "选择字幕",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 8.dp)
                )
                LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                    itemsIndexed(uiItems, key = { idx, _ -> idx }) { idx, label ->
                        val isSel = idx == selectedIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (isSel) Modifier.background(Color(0x44FFEB3B))
                                    else Modifier
                                )
                                .clickable { onSelect(idx) }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = label ?: "关闭字幕",
                                color = if (isSel) Color.Yellow else Color(0xCCFFFFFF),
                                fontSize = 16.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                            )
                            if (isSel) {
                                Spacer(Modifier.weight(1f))
                                Text("✓", color = Color.Yellow, fontSize = 16.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 从 Uri 读取视频文件名（content:// 取 DISPLAY_NAME，否则取最后路径段） */
private fun resolveDisplayName(context: Context, uri: Uri): String {
    if (uri.scheme == "content") {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return c.getString(idx) ?: ""
                }
            }
        }
    }
    return uri.lastPathSegment ?: uri.toString()
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