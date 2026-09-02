@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.composedtv.player

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.flv.FlvExtractor
import com.example.composedtv.data.remote.ApiClient
import com.example.composedtv.debug.DebugDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 播放列表项 */
data class PlaylistItem(
    val name: String,
    val url: String,
    val sourceId: String? = null,
    val favId: String? = null,
    val isFavorite: Boolean = false,
    val country: String = ""   // 频道国家/地区，用于"国内IP不走代理"判定
)

/** 播放器 UI 状态 */
data class PlayerState(
    val currentIndex: Int = 0,
    val playlistSize: Int = 0,
    val currentName: String = "",
    val isFavorite: Boolean = false,
    val isLoading: Boolean = false,
    val isPlaying: Boolean = false,
    val error: String? = null,
    val showInfo: Boolean = false,
    /** 操作结果提示（如"已收藏"/"请先登录"等），显示在信息条上，3 秒后自动清空 */
    val transientHint: String? = null,
    /** 当前播放是否经由 HLS 代理（用于 UI 显示代理标识） */
    val usingProxy: Boolean = false
)

/**
 * 播放器引擎：封装 ExoPlayer 竞速/hedged 播放逻辑。
 *
 * 从 tv4iptv PlayerActivity 移植，适配 Compose 生命周期。
 * 通过 [stateFlow] 暴露 UI 状态，通过 [player] 暴露 ExoPlayer 实例供 PlayerView 绑定。
 *
 * 播放优化（可配置，见 PlayerViewModel.PlaybackSettings）：
 * - stuckTimeoutMs：直连胜出后持续缓冲判定卡顿的时长
 * - raceHedgeMs：副路径（代理）延迟启动毫秒数
 * - proxyReviveMax：直连胜出后卡顿复活代理的次数
 *
 * 国内频道（country 为 CN/SK/澳门等）：完全不走代理，仅直连。
 */
class PlayerEngine(private val context: Context) {

    companion object {
        private const val TAG = "PlayerEngine"
        /** 老设备（Android 6.0.1 及以下，如海信老电视）适配阈值：降级为单路顺序播放，避免双路解码卡顿 */
        private const val LEGACY_SDK_MAX = 23
        /** 竞速 watchdog：老设备起播/解码更慢，放宽超时避免误 reload */
        private val WATCHDOG_TIMEOUT_MS: Long
            get() = if (android.os.Build.VERSION.SDK_INT <= LEGACY_SDK_MAX) 15_000L else 8_000L
        private val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"
        // ===== 可被设置抽屉注入的播放参数（下方 var 为运行时值，此处为默认值） =====
        private const val DEFAULT_STUCK_TIMEOUT_MS = 8_000L   // 直连胜出后持续缓冲判定 stuck 的时长
        private const val DEFAULT_RACE_HEDGE_MS = 800L         // 副路径（代理）延迟启动
        private const val DEFAULT_PROXY_REVIVE_MAX = 1        // 直连胜出后 stuck 时最多复活代理的次数
        private const val REVIVE_COOLDOWN_MS = 3_000L         // 两次复活最小间隔，防疯抢

        // 判定为"国内"的渠道：完全不走代理（CN=中国, SK=韩国, MO/MACAU/澳门）
        private val DOMESTIC_COUNTRIES = setOf("CN", "SK", "MO", "MACAU", "澳门")
    }

    // ===== 可注入播放参数（由设置抽屉经 ViewModel 注入） =====
    /** 持续缓冲超过该时长且播放位置不前进 → 判定 stuck */
    var stuckTimeoutMs = DEFAULT_STUCK_TIMEOUT_MS
    /** 副路径（代理）延迟启动毫秒数 */
    var raceHedgeMs = DEFAULT_RACE_HEDGE_MS
    /** 直连胜出后 stuck 时最多复活代理候选的次数 */
    var proxyReviveMax = DEFAULT_PROXY_REVIVE_MAX

    /** 由 PlayerScreen 注入共享 Surface（PlayerView 就绪后其视频渲染 Surface 非空）。
     *  之后创建的所有竞速候选（含代理副路）都会绑定该 Surface，实现真正的双路竞速且切换无黑屏。
     *  media3 1.2.1：ExoPlayer 无 getVideoSurface()，故直接 setVideoSurface（幂等）。 */
    fun setSharedSurface(surface: android.view.Surface?) {
        // 诊断：统计注入调用次数与"幂等判断未拦住、真正执行 setVideoSurface"的次数。
        // 后者在 API<23 上每次都会触发解码器 release + re-init（无 setOutputSurface），
        // 是老设备"有声音但画面卡成图片轮播"的重点嫌疑。
        DebugDiagnostics.onSurfaceInjectCall()
        if (sharedSurface === surface) return
        sharedSurface = surface
        if (surface != null) {
            // API<23 无 setOutputSurface：每次 setVideoSurface 都会重建解码器。
            // 用 lastSetSurface 做二次节流，避免 TextureView 每次 new Surface 对象
            // （sharedSurface === surface 永远 false）导致的反复 setVideoSurface。
            if (lastSetSurface === surface) return
            lastSetSurface = surface
            _playerRef.value?.let { exo ->
                DebugDiagnostics.onSurfaceInjectApplied()
                exo.setVideoSurface(surface)
            }
            for (c in raceCandidates) {
                c.exo?.let { exo ->
                    DebugDiagnostics.onSurfaceInjectApplied()
                    exo.setVideoSurface(surface)
                }
            }
        } else {
            // Surface 置空时清除节流缓存，下次非空可正常注入
            lastSetSurface = null
        }
    }

    /** setVideoSurface 节流：记录上一次真正执行的 Surface 对象，相同则跳过。
     * 用于修复 TextureView 场景下 Surface(surfaceTexture) 每次 new 新对象、
     * 幂等判断（===）失效、导致 API<23 反复重建解码器的问题。 */
    private var lastSetSurface: android.view.Surface? = null

    /** 把设置抽屉里的值同步进 engine（PlaybackSettings 定义在 PlayerViewModel） */
    fun applyPlaybackSettings(s: com.example.composedtv.viewmodel.PlaybackSettings) {
        stuckTimeoutMs = s.stuckTimeoutMs
        raceHedgeMs = s.hedgeMs
        // 老设备（如海信 Android 6）：复活代理会并行双解码，直接拖垮弱解码器，故强制关闭
        proxyReviveMax = if (isLegacyDevice()) 0 else s.reviveMax
        DebugDiagnostics.setEnv(isLegacyDevice(), s.rendererMode.name)
        Log.d(TAG, "applyPlaybackSettings: stuck=${stuckTimeoutMs} hedge=${raceHedgeMs} revive=${proxyReviveMax} legacy=${isLegacyDevice()}")
    }

    /** 是否老设备：Android 6.0.1 (API 23) 及以下。老设备解码/渲染能力弱，需要降级策略 */
    private fun isLegacyDevice(): Boolean = android.os.Build.VERSION.SDK_INT <= LEGACY_SDK_MAX

    /** 判定频道是否国内（完全不走代理） */
    private fun isDomestic(channelCountry: String): Boolean {
        return channelCountry.isNotBlank() &&
                DOMESTIC_COUNTRIES.contains(channelCountry.trim().uppercase())
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(PlayerState())
    val stateFlow: kotlinx.coroutines.flow.StateFlow<PlayerState> = _state

    /** 当前绑定的主 player（供 PlayerView 使用） */
    private val _playerRef = kotlinx.coroutines.flow.MutableStateFlow<ExoPlayer?>(null)
    val playerFlow: kotlinx.coroutines.flow.StateFlow<ExoPlayer?> = _playerRef
    val player: ExoPlayer? get() = _playerRef.value

    /** 统一设置主 player 引用：同步给诊断中心，便于 HUD 读取 DecoderCounters */
    private fun setPlayerRef(exo: ExoPlayer?) {
        if (_playerRef.value === exo) return
        _playerRef.value = exo
        DebugDiagnostics.attachPlayer(exo)
    }

    private var playlist: List<PlaylistItem> = emptyList()
    private var currentIndex = 0
    /** 当前正在播放的 URL（供 stuck 复活竞速复用） */
    private var currentPlayingUrl: String = ""
    private var consecutiveErrors = 0
    private var flvRetryDone = false

    /** 重载前保存的播放位置（毫秒），用于点播内容恢复进度 */
    private var pendingResumePositionMs: Long = 0
    /** 当前播放是否为直播流 */
    private var isLiveStream = false

    // 竞速状态
    private data class RaceCandidate(
        val attempt: String,
        var exo: ExoPlayer?,
        var failed: Boolean = false
    )
    /** 竞速 watchdog 连续超时次数：达到 [RACE_TIMEOUT_MAX] 后不再原地重载，改为切台。
     * 用于解决「死源一直 BUFFERING 但不报错」时永不切台的问题——
     * 这类源不会触发 onPlayerError，因此 raceCandidateFailed / handlePlayFailure
     * （唯一切台入口）永不执行，watchdog 只能无限原地重载同一频道。 */
    private var raceTimeoutCount = 0

    /** 竞速连续超时上限：第 1 次超时原地重载（给一次机会），达到上限则切下一台。
     * 老设备 15s×2=30s、非老设备 8s×2=16s 后切台。 */
    private val RACE_TIMEOUT_MAX = 2

    private var raceActive = false
    private var raceDecided = false
    private var raceCandidates = listOf<RaceCandidate>()
    private var raceHedgeJob: Job? = null
    private var racePlayToken = 0
    private var watchdogJob: Job? = null

    // 闸门竞速：直连胜出后的 stuck 监测 + 代理复活
    /** 直连胜出后 stuck 时允许复活代理候选的剩余次数（上限见 proxyReviveMax） */
    private var proxyReviveBudget = 0
    /** 是否正处于"复活代理"竞速阶段（用于终局判定：双路皆败直接跳台） */
    private var wasReviving = false
    /** stuck 监测协程 */
    private var stuckMonitorJob: Job? = null
    /** 上一次报告的播放位置，用于判断"是否在前进" */
    private var lastReportedPositionMs = 0L
    /** 上次复活时间戳，用于冷却间隔 */
    private var lastReviveAt = 0L
    /** 由 PlayerScreen 注入的共享视频 Surface（PlayerView 的 videoSurface）。
     *  所有竞速候选共用一个 Surface：副路可真正出画参与竞速，胜出切换时不重建 Surface（修复老电视"有声音没画面"） */
    private var sharedSurface: android.view.Surface? = null
    /** 老设备专用：本频道直连失败后是否已顺序重试过代理（最多 1 次，避免双解码） */
    private var proxyRetryDone = false

    // 信息条自动隐藏
    private var infoHideJob: Job? = null
    // transientHint 自动清理
    private var hintHideJob: Job? = null

    /** 设置播放列表并播放指定 index */
    fun setPlaylist(items: List<PlaylistItem>, startIndex: Int = 0) {
        playlist = items
        currentIndex = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        consecutiveErrors = 0
        if (items.isEmpty()) {
            _state.value = PlayerState(error = "播放列表为空")
            return
        }
        playCurrent()
    }

    private fun playCurrent() {
        if (playlist.isEmpty()) return
        val item = playlist[currentIndex.coerceIn(0, playlist.lastIndex)]
        // 进入新一轮竞速：清除重载荷载锁，允许后续（若仍卡死）再次兜底重载
        isReloading = false
        resetRace()
        stopStuckMonitor()
        proxyReviveBudget = 0
        wasReviving = false
        player?.stop()
        player?.release()
        DebugDiagnostics.onPlayerReleased()
        setPlayerRef(null)
        flvRetryDone = false
        proxyRetryDone = false
        currentPlayingUrl = item.url
        DebugDiagnostics.setChannel(item.name, item.url, false)
        // 判断是否直播流：HLS (.m3u8) 和 FLV 视为直播，其余（如 .mp4/.mkv）视为点播
        isLiveStream = isFlvStream(item.url) || item.url.lowercase().let {
            it.contains(".m3u8") || it.contains(".m3u")
        }
        // 信息条：仅在「频道真正变化」时弹出。
        // 原地重载（watchdog / 播放期兜底）会反复调用 playCurrent，若每次都
        // showInfo=true + scheduleInfoHide()，频道名与底部提示就会周期性闪烁
        // （非老设备 watchdog 8 秒一轮，表现为亮 5 秒灭 3 秒）。
        // 同一频道重载时保持信息条当前状态：已隐藏则继续隐藏。
        val channelChanged = _state.value.currentName != item.name
        if (channelChanged) {
            updateState(
                currentName = item.name,
                currentIndex = currentIndex,
                playlistSize = playlist.size,
                isFavorite = item.isFavorite,
                isLoading = true,
                error = null,
                showInfo = true,
                usingProxy = false
            )
            scheduleInfoHide()
        } else {
            // 重载同一频道：只更新加载态，不动信息条显隐
            updateState(isLoading = true, error = null)
        }
        playUrl(item.url, item.country)
    }

    fun playNext() {
        if (playlist.isEmpty()) return
        currentIndex = (currentIndex + 1) % playlist.size
        wasReviving = false
        showInfoTransient()
        playCurrent()
    }

    fun playPrev() {
        if (playlist.isEmpty()) return
        currentIndex = (currentIndex - 1 + playlist.size) % playlist.size
        showInfoTransient()
        playCurrent()
    }

    /** 播放指定 index（从侧边栏选择频道时使用） */
    fun playAtIndex(index: Int) {
        if (playlist.isEmpty() || index !in playlist.indices) return
        if (index == currentIndex) return
        currentIndex = index
        playCurrent()
    }

    /** 获取当前播放列表（供侧边栏高亮当前频道） */
    fun getCurrentPlaylist(): List<PlaylistItem> = playlist
    fun getCurrentIndex(): Int = currentIndex

    fun togglePause() {
        val p = player ?: return
        p.playWhenReady = !p.playWhenReady
        _state.value = _state.value.copy(isPlaying = p.playWhenReady)
        showInfoTransient()
    }

    /** 是否因为侧边栏打开而手动暂停（点播） */
    private var userPausedForPanel = false

    /** 侧边栏打开时：非直播暂停播放，直播保持不变 */
    fun pauseIfNotLive() {
        if (isLiveStream) return
        val exo = _playerRef.value ?: return
        if (exo.playWhenReady && exo.isPlaying) {
            exo.playWhenReady = false
            userPausedForPanel = true
            Log.d(TAG, "侧边栏打开：暂停点播播放")
        }
    }

    /** 侧边栏关闭时：恢复被暂停的点播播放 */
    fun resumeIfPaused() {
        if (isLiveStream) return
        if (!userPausedForPanel) return
        val exo = _playerRef.value ?: return
        if (!exo.playWhenReady) {
            exo.playWhenReady = true
            Log.d(TAG, "侧边栏关闭：恢复点播播放")
        }
        userPausedForPanel = false
    }

    /** 手动重载当前频道（用户按键触发） */
    fun manualReload() {
        if (playlist.isEmpty()) {
            Log.w(TAG, "manualReload: 播放列表为空")
            showHint("暂无频道可重载")
            return
        }
        Log.d(TAG, "手动重载当前频道: $currentPlayingUrl")
        showHint("正在重载：${playlist[currentIndex].name}")
        reloadCurrentChannel(reason = "manual")
    }

    fun toggleFavorite() {
        if (playlist.isEmpty()) {
            Log.w(TAG, "toggleFavorite: 播放列表为空")
            showHint("暂无频道可收藏")
            return
        }
        val item = playlist[currentIndex]
        if (!ApiClient.isLoggedIn) {
            Log.w(TAG, "toggleFavorite: 未登录，无法收藏")
            showHint("请先登录后再收藏")
            return
        }
        scope.launch {
            try {
                if (item.isFavorite) {
                    // 取消收藏：hls4iptv 后端按 url 删除，favId 实际存的就是 url
                    val idToRemove = item.favId?.takeIf { it.isNotEmpty() } ?: item.url
                    ApiClient.removeFavorite(idToRemove)
                    playlist = playlist.toMutableList().apply {
                        this[currentIndex] = item.copy(isFavorite = false, favId = null)
                    }
                    showHint("已取消收藏：${item.name}")
                } else {
                    // 添加收藏：后端用 url 去重，返回 url 作为标识
                    ApiClient.addFavorite(item.url, item.name, null, item.sourceId ?: "")
                    playlist = playlist.toMutableList().apply {
                        this[currentIndex] = item.copy(isFavorite = true, favId = item.url)
                    }
                    showHint("已收藏：${item.name}")
                }
                updateState(isFavorite = playlist[currentIndex].isFavorite, showInfo = true)
                scheduleInfoHide()
            } catch (e: Exception) {
                Log.w(TAG, "toggleFavorite failed: ${e.message}")
                showHint("操作失败：${e.message ?: "未知错误"}")
            }
        }
    }

    // ===== FLV 探测 =====
    private fun isFlvStream(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains(".flv")) return true
        return lower.contains("huya") || lower.contains("douyu") ||
               lower.contains("bilibili") || lower.contains("/live/") ||
               lower.contains("live.") || lower.contains("/flv/")
    }

    // ===== 播放策略规划 =====
    private fun planPlay(url: String, country: String): List<String> {
        // 国内频道：完全不走代理，仅直连
        if (isDomestic(country)) {
            return listOf("direct")
        }
        val lower = url.lowercase()
        val isHls = lower.contains(".m3u8") || lower.contains(".m3u")
        if (!isHls) return listOf("direct")
        // 老设备（海信 Android 6 等）：不启动双路竞速（并行双解码会卡顿），仅直连；
        // 代理兜底由 handlePlayFailure 顺序重试（单路），避免弱解码器被拖垮
        if (isLegacyDevice()) return listOf("direct")
        // 一律直连优先、代理兜底：仅当直连慢（竞速）或失败时才启用代理，
        // 修复 http:// 源被无条件强制走代理导致"可直连频道误走代理"的问题
        return listOf("direct", "proxy")
    }

    private fun playUrl(url: String, country: String = "", forceProxy: Boolean = false) {
        if (url.isBlank()) {
            handlePlayFailure()
            return
        }
        currentPlayingUrl = url
        val attempts = if (forceProxy) listOf("proxy") else planPlay(url, country)
        Log.d(TAG, "planPlay($url) -> $attempts country=$country forceProxy=$forceProxy")
        startRace(url, attempts)
    }

    // ===== 竞速播放 =====
    private fun createCandidatePlayer(url: String, useProxy: Boolean, winner: Boolean): ExoPlayer {
        val lower = url.lowercase()
        val isHls = lower.contains(".m3u8") || lower.contains(".m3u")
        val isFlv = isFlvStream(url)
        val finalUrl = if (useProxy && isHls) ApiClient.hlsProxyUrl(url) else url
        val legacy = isLegacyDevice()

        // 调优后的缓冲策略：
        // - minBuffer 20s：稳定播放所需的最小缓冲
        // - maxBuffer 60s：网络好时多缓冲，应对后续波动
        // - initialBuffer 1s：起播快（仅需 1s 数据即开始播放）
        // - rebuffer 2.5s：卡顿后需更多缓冲才恢复，减少二次卡顿
        // 老设备内存/解码弱：缓冲减半，降低内存占用与起播压力
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (legacy) 10_000 else 20_000,
                if (legacy) 30_000 else 60_000,
                1_000, 2_500
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(BROWSER_UA)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)

        val mediaSourceFactory = if (isFlv) {
            val flvExtractorsFactory = ExtractorsFactory {
                arrayOf<androidx.media3.extractor.Extractor>(FlvExtractor())
            }
            DefaultMediaSourceFactory(dataSourceFactory, flvExtractorsFactory)
        } else {
            DefaultMediaSourceFactory(dataSourceFactory)
        }

        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
            .setEnableAudioFloatOutput(false)

        val p = ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()

        // 自适应码率（ABR）：信号差自动降分辨率，信号好自动升分辨率
        // - 新设备上限 1080p / 10Mbps；老设备上限 720p / 4Mbps（解码能力弱，降级保流畅）
        // - 允许降到任意低码率，保证流畅
        // - 不强制最低/最高，由 ExoPlayer 根据实时带宽自适应
        p.trackSelectionParameters = p.trackSelectionParameters
            .buildUpon()
            .setMaxVideoSize(if (legacy) 1280 else 1920, if (legacy) 720 else 1080)
            .setMaxVideoBitrate(if (legacy) 4_000_000 else 10_000_000)
            .setMinVideoBitrate(0)
            .setForceLowestBitrate(false)
            .setForceHighestSupportedBitrate(false)
            .build()

        if (!winner) p.volume = 0f

        // 共享 Surface：副路候选（如代理）也绑定 PlayerView 的 Surface，才能真正出画参与竞速；
        // 且胜出切换 player 时 Surface 不重建，避免老设备"有声音没画面"
        sharedSurface?.let { p.setVideoSurface(it) }

        val builder = MediaItem.Builder().setUri(finalUrl)
        when {
            isHls -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            isFlv -> builder.setMimeType(MimeTypes.VIDEO_FLV)
        }
        if (isHls || isFlv) {
            builder.setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(30_000L)
                    .setMinOffsetMs(10_000L)
                    .setMaxOffsetMs(60_000L)
                    .build()
            )
        }
        p.setMediaItem(builder.build())
        // 诊断：挂 AnalyticsListener 采集解码器名称/初始化耗时/视频格式/丢帧
        p.addAnalyticsListener(DebugDiagnostics.newAnalyticsListener())
        DebugDiagnostics.onPlayerCreated()
        DebugDiagnostics.log(
            "Engine",
            "createCandidate attempt=${if (useProxy) "proxy" else "direct"} winner=$winner " +
                "hls=$isHls flv=$isFlv legacy=$legacy url=$finalUrl"
        )
        p.prepare()
        p.playWhenReady = true
        p.repeatMode = Player.REPEAT_MODE_OFF
        return p
    }

    private fun startRace(url: String, attempts: List<String>) {
        val myToken = ++racePlayToken
        raceActive = true
        raceDecided = false
        raceCandidates = emptyList()
        updateState(isLoading = true)
        DebugDiagnostics.onRaceStart()
        Log.d(TAG, "竞速开始: $attempts")

        val firstAttempt = attempts[0]
        val c0 = RaceCandidate(firstAttempt, null, false)
        c0.exo = createCandidatePlayer(url, firstAttempt == "proxy", winner = true)
        setPlayerRef(c0.exo)
        raceCandidates = listOf(c0)
        attachCandidateListener(c0, url, myToken)
        startWatchdog()

        if (attempts.size > 1) {
            raceHedgeJob?.cancel()
            raceHedgeJob = scope.launch {
                delay(raceHedgeMs)
                if (raceDecided || myToken != racePlayToken) return@launch
                val c0State = c0.exo?.playbackState ?: Player.STATE_IDLE
                if (c0State == Player.STATE_READY) return@launch
                Log.d(TAG, "直连较慢，启动代理竞速…")
                val c1 = RaceCandidate(attempts[1], null, false)
                c1.exo = createCandidatePlayer(url, attempts[1] == "proxy", winner = false)
                raceCandidates = raceCandidates + c1
                attachCandidateListener(c1, url, myToken)
            }
        }
    }

    private fun attachCandidateListener(cand: RaceCandidate, url: String, token: Int) {
        val exo = cand.exo ?: return
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (token != racePlayToken || raceDecided) return
                when (state) {
                    Player.STATE_READY -> onRaceWin(cand, token)
                    Player.STATE_BUFFERING -> {
                        if (cand.attempt == raceCandidates.firstOrNull()?.attempt) {
                            updateState(isLoading = true)
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (token != racePlayToken || raceDecided) return
                val cause = error.cause
                if (cause is androidx.media3.exoplayer.source.BehindLiveWindowException) {
                    Log.w(TAG, "${cand.attempt} BehindLiveWindow, auto-recover")
                    exo.stop()
                    exo.clearMediaItems()
                    val finalUrl = if (cand.attempt == "proxy") ApiClient.hlsProxyUrl(url) else url
                    val builder = MediaItem.Builder().setUri(finalUrl).setMimeType(MimeTypes.APPLICATION_M3U8)
                    builder.setLiveConfiguration(
                        MediaItem.LiveConfiguration.Builder()
                            .setTargetOffsetMs(30_000L).setMinOffsetMs(10_000L).setMaxOffsetMs(60_000L).build()
                    )
                    exo.setMediaItem(builder.build())
                    exo.prepare()
                    exo.playWhenReady = true
                    return
                }
                Log.e(TAG, "${cand.attempt} onPlayerError: ${error.errorCodeName} url=$url", error)
                DebugDiagnostics.onError("race/${cand.attempt}", error)
                raceCandidateFailed(cand)
            }
        })
    }

    private fun onRaceWin(winner: RaceCandidate, token: Int) {
        if (raceDecided || token != racePlayToken) return
        raceDecided = true
        raceActive = false
        raceHedgeJob?.cancel()
        raceHedgeJob = null
        stopWatchdog()
        consecutiveErrors = 0
        // 竞速成功：清零 watchdog 连续超时计数，避免偶发超时累积后误切台
        raceTimeoutCount = 0

        for (c in raceCandidates) {
            if (c !== winner) {
                c.exo?.stop()
                c.exo?.release()
                DebugDiagnostics.onPlayerReleased()
            }
        }

        val winExo = winner.exo
        if (winExo != null) {
            winExo.volume = 1f
            setPlayerRef(winExo)
            DebugDiagnostics.setChannel(
                playlist.getOrNull(currentIndex)?.name ?: "",
                currentPlayingUrl,
                winner.attempt == "proxy"
            )
            winExo.playWhenReady = true
            // 点播内容：恢复到重载前的播放位置
            if (!isLiveStream && pendingResumePositionMs > 0) {
                Log.d(TAG, "恢复点播播放位置: ${pendingResumePositionMs}ms")
                winExo.seekTo(pendingResumePositionMs)
                pendingResumePositionMs = 0
            }
            attachPlaybackWatcher(winExo, winner.attempt)
        }
        raceCandidates = emptyList()
        updateState(isLoading = false, isPlaying = true, error = null, usingProxy = winner.attempt == "proxy")
        Log.d(TAG, "竞速胜出: ${winner.attempt}")

        // 闸门竞速：直连胜出后才需要监测 stuck 并允许复活代理；代理胜出则不再反向复活直连
        if (winner.attempt == "direct") {
            proxyReviveBudget = proxyReviveMax
            wasReviving = false
            startStuckMonitor(winExo)
        } else {
            proxyReviveBudget = 0
            stopStuckMonitor()
        }
    }

    /**
     * 直连胜出后的 stuck 监测：持续缓冲超过 [stuckTimeoutMs] 且播放位置不前进 → 判定 stuck。
     * stuck 且仍有复活额度 → 复活代理候选重新竞速（仅 1 次，避免重载循环）。
     * 注意：正常直播波动（缓冲一下又恢复）会在 STATE_READY 时重置计时，不会误触发。
     */
    private fun startStuckMonitor(exo: ExoPlayer?) {
        stopStuckMonitor()
        val target = exo ?: return
        lastReportedPositionMs = target.currentPosition
        stuckMonitorJob = scope.launch {
            delay(stuckTimeoutMs)
            if (raceDecided && proxyReviveBudget <= 0 && !wasReviving) {
                // 已是稳定播放且无复活需求，无需处理
            }
            val state = target.playbackState
            val pos = target.currentPosition
            val stuck = (state == Player.STATE_BUFFERING) && (pos <= lastReportedPositionMs + 500)
            if (stuck && proxyReviveBudget > 0 && !raceDecided) {
                val now = System.currentTimeMillis()
                if (now - lastReviveAt >= REVIVE_COOLDOWN_MS) {
                    Log.w(TAG, "直连胜出后 stuck(${stuckTimeoutMs}ms)，复活代理候选 (budget=$proxyReviveBudget)")
                    proxyReviveBudget--
                    lastReviveAt = now
                    reviveProxyCandidate(target)
                }
            }
        }
    }

    private fun stopStuckMonitor() {
        stuckMonitorJob?.cancel()
        stuckMonitorJob = null
    }

    // ===== 播放期兜底监测（代理胜出 / 直连胜出后均生效） =====
    // 竞速期只监测直连胜出场景；代理胜出后没有任何兜底，会出现永久 spinner / 黑屏。
    // 这里统一给"已稳定播放"的 winner 增加缓冲超时监测：超时仍未恢复则重载当前频道，
    // 连续重载 PLAYBACK_RELOAD_MAX 次仍卡则跳下一台（与"双路皆败跳台"语义一致）。

    /** 播放期连续原地重载次数；重载后若成功恢复(STATE_READY)则清零。 */
    private var playbackReviveCount = 0

    /** 播放期连续重载上限：达到后改为跳下一台。 */
    private val PLAYBACK_RELOAD_MAX = 1

    /** 重载荷载锁：防止播放期兜底与竞速 watchdog 同时/并发触发同一频道多次 reload */
    private var isReloading = false

    private var playbackStuckJob: Job? = null

    private fun startPlaybackStuckMonitor(exo: ExoPlayer?, attempt: String) {
        stopPlaybackStuckMonitor()
        val target = exo ?: return
        val basePos = target.currentPosition
        playbackStuckJob = scope.launch {
            delay(stuckTimeoutMs)
            val state = target.playbackState
            val pos = target.currentPosition
            // 仅在确实还在缓冲且位置未推进时判 stuck。
            // 注意：API<23 解码器重建/重载初期画面定格也会被观察到 BUFFERING，
            // 故要求"曾经播放过(playWhenReady 且非 IDLE)"才判 stuck，避免把
            // 重建/重载过程本身误判为 stuck 进而递归重载（图片轮播的放大器之一）。
            val everStarted = target.playbackState != Player.STATE_IDLE
            val stuck = everStarted && (state == Player.STATE_BUFFERING) && (pos <= basePos + 500)
            if (stuck) {
                Log.w(TAG, "播放期 stuck(${stuckTimeoutMs}ms) 未恢复，触发兜底: $currentPlayingUrl")
                onPlaybackFatal(exo, attempt, "stuck")
            }
        }
    }

    private fun stopPlaybackStuckMonitor() {
        playbackStuckJob?.cancel()
        playbackStuckJob = null
    }

    /**
     * 播放期致命/卡死兜底：先原地重载当前频道；若已连续重载 PLAYBACK_RELOAD_MAX 次仍失败则跳下一台。
     * 重载成功后(onPlaybackWatcher STATE_READY)会清零 playbackReviveCount，因此只有"连续"卡死才累计跳台。
     */
    @Suppress("UNUSED_PARAMETER")
    private fun onPlaybackFatal(_exo: ExoPlayer?, _attempt: String, reason: String) {
        if (playlist.isEmpty()) return
        playbackReviveCount++
        if (playbackReviveCount > PLAYBACK_RELOAD_MAX) {
            Log.w(TAG, "播放期连续重载 $playbackReviveCount 次仍失败，跳下一台: $currentPlayingUrl")
            playbackReviveCount = 0
            stopPlaybackStuckMonitor()
            playNext()
            return
        }
        Log.w(TAG, "播放期兜底重载(reason=$reason, count=$playbackReviveCount/$PLAYBACK_RELOAD_MAX): $currentPlayingUrl")
        stopPlaybackStuckMonitor()
        reloadCurrentChannel(reason = "playback-$reason")
    }

    /**
     * 复活代理候选重新竞速：保留当前直连（仍在播）作为候选之一，并行再拉一路代理。
     * 代理先出画 → onRaceWin 切过去；代理也败 → raceCandidateFailed 处理（双路皆败直接跳台）。
     */
    private fun reviveProxyCandidate(currentWinnerExo: ExoPlayer) {
        val url = currentPlayingUrl
        val myToken = ++racePlayToken
        raceDecided = false
        raceActive = true
        wasReviving = true
        stopWatchdog()
        // 现任直连作为候选，保持其播放
        val directCand = RaceCandidate("direct", currentWinnerExo, false)
        val proxyCand = RaceCandidate("proxy", null, false)
        raceCandidates = listOf(directCand, proxyCand)
        attachCandidateListener(directCand, url, myToken)
        proxyCand.exo = createCandidatePlayer(url, true, winner = false)
        // 共享 Surface 模式下代理候选已绑定 PlayerView 的 Surface，无需切换 _playerRef，
        // 避免 PlayerView 重建 Surface 导致老电视"有声音没画面"；仅当 Surface 尚未就绪时回退切换
        if (sharedSurface == null) {
            setPlayerRef(proxyCand.exo)
        }
        attachCandidateListener(proxyCand, url, myToken)
        Log.d(TAG, "reviveProxyCandidate: 直连继续播 + 代理并行竞速 (sharedSurface=${sharedSurface != null})")
    }

    /**
     * 胜出后的播放期监听器（仅观察状态，不自动重载）：
     * - STATE_BUFFERING：显示 spinner
     * - STATE_READY：恢复 spinner
     * - STATE_ENDED / onPlayerError：仅记录日志，不再自动重载。
     *   用户如需恢复，请按右键手动重载。
     */
    private fun attachPlaybackWatcher(exo: ExoPlayer, attempt: String) {
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        updateState(isLoading = true)
                        // 播放期卡顿监测：进入缓冲即开始计时，超时未恢复则重载
                        startPlaybackStuckMonitor(exo, attempt)
                    }
                    Player.STATE_READY -> {
                        updateState(isLoading = false, isPlaying = true, error = null)
                        // 恢复播放 → 重置 stuck 计时，避免正常波动误触发重载
                        stopPlaybackStuckMonitor()
                        // 重载后成功恢复 → 清零连续重载计数，只有"连续"卡死才累计跳台
                        if (playbackReviveCount > 0) {
                            Log.d(TAG, "播放期恢复稳定，清零 playbackReviveCount")
                            playbackReviveCount = 0
                        }
                    }
                    Player.STATE_ENDED -> {
                        Log.w(TAG, "播放期 STATE_ENDED，触发重载: $currentPlayingUrl")
                        onPlaybackFatal(exo, attempt, "ended")
                    }
                    Player.STATE_IDLE -> {
                        // no-op
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val cause = error.cause
                if (cause is androidx.media3.exoplayer.source.BehindLiveWindowException) {
                    // 直播滑出窗口：与竞速期一致，自动重新 seek 恢复，避免黑屏
                    Log.w(TAG, "播放期 BehindLiveWindow，auto-recover: $currentPlayingUrl")
                    exo.stop()
                    exo.clearMediaItems()
                    val finalUrl = if (attempt == "proxy") ApiClient.hlsProxyUrl(currentPlayingUrl) else currentPlayingUrl
                    val builder = MediaItem.Builder().setUri(finalUrl).setMimeType(MimeTypes.APPLICATION_M3U8)
                    builder.setLiveConfiguration(
                        MediaItem.LiveConfiguration.Builder()
                            .setTargetOffsetMs(30_000L).setMinOffsetMs(10_000L).setMaxOffsetMs(60_000L).build()
                    )
                    exo.setMediaItem(builder.build())
                    exo.prepare()
                    exo.playWhenReady = true
                    return
                }
                Log.e(TAG, "播放期 onPlayerError: ${error.errorCodeName} url=$currentPlayingUrl", error)
                DebugDiagnostics.onError("playback/$attempt", error)
                onPlaybackFatal(exo, attempt, error.errorCodeName)
            }
        })
    }

    /**
     * 重载当前频道：原地重新走一次 playCurrent（含竞速）。
     * 对于点播内容（非直播），保存当前播放位置，重载后 seekTo 恢复进度。
     * 与 handlePlayFailure 不同：不增加 consecutiveErrors、不跳下一台。
     */
    private fun reloadCurrentChannel(reason: String) {
        if (playlist.isEmpty()) return
        // 重载荷载锁：若已在 reload 流程中（竞速 watchdog / 播放期兜底可能同时触发），直接忽略，
        // 避免同一频道被并发多次 reload（表现为反复重新加载、台名反复闪烁）
        if (isReloading) {
            Log.d(TAG, "reloadCurrentChannel 忽略重复请求(reason=$reason)，已有 reload 在进行")
            return
        }
        isReloading = true
        // 立即终止旧的播放期与竞速层监测，避免旧 player 在其延迟/超时窗口内再次触发 reload
        stopPlaybackStuckMonitor()
        stopWatchdog()
        // 点播内容：保存当前播放位置以便重载后恢复
        if (!isLiveStream) {
            val pos = player?.currentPosition ?: 0
            if (pos > 0) {
                pendingResumePositionMs = pos
                Log.d(TAG, "reloadCurrentChannel: 保存点播播放位置 ${pos}ms")
            }
        }
        Log.d(TAG, "reloadCurrentChannel(reason=$reason) idx=$currentIndex url=$currentPlayingUrl isLive=$isLiveStream")
        DebugDiagnostics.onReload(reason)
        playCurrent()
    }

    private fun raceCandidateFailed(cand: RaceCandidate) {
        if (raceDecided || cand.failed) return
        cand.failed = true
        cand.exo?.stop()
        cand.exo?.release()
        DebugDiagnostics.onPlayerReleased()
        cand.exo = null
        val alive = raceCandidates.filter { !it.failed }
        if (alive.isEmpty()) {
            raceActive = false
            raceDecided = true
            stopStuckMonitor()
            // 闸门竞速终局：直连胜出后复活代理，代理也败（双路皆败）→ 直接跳台，不报错不循环
            if (wasReviving) {
                Log.w(TAG, "复活竞速双路皆败，直接跳台（不报错）")
                wasReviving = false
                playNext()
                return
            }
            handlePlayFailure()
        }
    }

    private fun resetRace() {
        raceActive = false
        raceDecided = false
        raceHedgeJob?.cancel()
        raceHedgeJob = null
        for (c in raceCandidates) {
            c.exo?.stop()
            c.exo?.release()
            DebugDiagnostics.onPlayerReleased()
        }
        raceCandidates = emptyList()
    }

    private fun handlePlayFailure() {
        stopWatchdog()
        if (playlist.isEmpty()) {
            updateState(error = "无法播放", isLoading = false)
            return
        }
        val item = playlist.getOrNull(currentIndex)
        val curUrl = item?.url ?: ""
        val country = item?.country ?: ""
        if (isFlvStream(curUrl) && !flvRetryDone) {
            flvRetryDone = true
            Log.d(TAG, "FLV 首次失败，原地重试: $curUrl")
            updateState(isLoading = true)
            resetRace()
            player?.stop()
            player?.release()
            DebugDiagnostics.onPlayerReleased()
            setPlayerRef(null)
            playUrl(curUrl, country)
            return
        }
        // 老设备（海信 Android 6 等）：直连失败后顺序重试一次代理（单路，不并行双解码）。
        // 代理由后端转发，某些源必须经代理才能播放；失败仍不代理则跳下一台
        if (isLegacyDevice() && !proxyRetryDone && !isDomestic(country) && !isFlvStream(curUrl)) {
            proxyRetryDone = true
            Log.d(TAG, "老设备直连失败，顺序重试代理: $curUrl")
            updateState(isLoading = true)
            resetRace()
            player?.stop()
            player?.release()
            DebugDiagnostics.onPlayerReleased()
            setPlayerRef(null)
            playUrl(curUrl, country, forceProxy = true)
            return
        }
        consecutiveErrors++
        if (consecutiveErrors >= playlist.size) {
            updateState(error = "所有频道均无法播放", isLoading = false, isPlaying = false)
            return
        }
        playNext()
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(WATCHDOG_TIMEOUT_MS)
            if (raceActive && !raceDecided) {
                stopWatchdog()
                raceTimeoutCount++
                if (raceTimeoutCount >= RACE_TIMEOUT_MAX) {
                    // 连续超时：该源八成已死（一直 BUFFERING 不报错），原地重载无意义，
                    // 直接切下一台。否则会卡在同一频道无限重载、永远不换台。
                    raceTimeoutCount = 0
                    Log.w(TAG, "Watchdog 连续超时 ${RACE_TIMEOUT_MAX} 次，切下一台: $currentPlayingUrl")
                    playNext()
                } else {
                    Log.d(TAG, "Watchdog: race timeout ($raceTimeoutCount/$RACE_TIMEOUT_MAX), reload: $currentPlayingUrl")
                    reloadCurrentChannel(reason = "race-timeout")
                }
            }
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    // ===== UI 状态 =====
    private fun updateState(
        currentIndex: Int = _state.value.currentIndex,
        playlistSize: Int = _state.value.playlistSize,
        currentName: String = _state.value.currentName,
        isFavorite: Boolean = _state.value.isFavorite,
        isLoading: Boolean = _state.value.isLoading,
        isPlaying: Boolean = _state.value.isPlaying,
        error: String? = _state.value.error,
        showInfo: Boolean = _state.value.showInfo,
        transientHint: String? = _state.value.transientHint,
        usingProxy: Boolean = _state.value.usingProxy
    ) {
        _state.value = PlayerState(
            currentIndex = currentIndex,
            playlistSize = playlistSize,
            currentName = currentName,
            isFavorite = isFavorite,
            isLoading = isLoading,
            isPlaying = isPlaying,
            error = error,
            showInfo = showInfo,
            transientHint = transientHint,
            usingProxy = usingProxy
        )
    }

    private fun showInfoTransient() {
        updateState(showInfo = true)
        scheduleInfoHide()
    }

    private fun scheduleInfoHide() {
        infoHideJob?.cancel()
        infoHideJob = scope.launch {
            delay(5000)
            updateState(showInfo = false)
        }
    }

    /** 显示操作结果提示（显示在信息条上，3 秒后自动清除） */
    private fun showHint(hint: String) {
        Log.d(TAG, "showHint: $hint")
        updateState(showInfo = true, transientHint = hint)
        scheduleInfoHide()
        scheduleHintHide()
    }

    private fun scheduleHintHide() {
        hintHideJob?.cancel()
        hintHideJob = scope.launch {
            delay(3000)
            updateState(transientHint = null)
        }
    }

    // ===== 生命周期 =====
    fun onResume() {
        val p = player ?: return
        p.playWhenReady = true
    }

    fun onPause() {
        player?.playWhenReady = false
    }

    fun release() {
        stopWatchdog()
        resetRace()
        stopStuckMonitor()
        infoHideJob?.cancel()
        hintHideJob?.cancel()
        player?.stop()
        player?.release()
        DebugDiagnostics.onPlayerReleased()
        setPlayerRef(null)
        sharedSurface = null
        playlist = emptyList()
    }
}
