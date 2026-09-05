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
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.flv.FlvExtractor
import com.example.composedtv.data.remote.ApiClient
import com.example.composedtv.debug.DebugDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** 播放列表项 */
data class PlaylistItem(
    val name: String,
    val url: String,
    val sourceId: String? = null,
    val favId: String? = null,
    val isFavorite: Boolean = false,
    val country: String = "",  // 频道国家/地区，用于"国内IP不走代理"判定
    /** 源站要求的自定义请求头：User-Agent（防盗链常用） */
    val ua: String = "",
    /** 源站要求的自定义请求头：Referer（服务端字段名 rf，防盗链常用） */
    val rf: String = ""
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
    val usingProxy: Boolean = false,
    /** 当前正在尝试的加载模式（"direct"/"proxy"），仅加载中有效；胜出或失败时置空，供 UI 简洁展示 */
    val loadMode: String? = null,
    /** 起播超时（毫秒）：超过该时长仍未 READY 则自动切换下一个候选；0 表示不展示 */
    val loadTimeoutMs: Long = 0L,
    /** 当前尝试的剩余超时（毫秒），每秒刷新，供 UI 倒计时显示；0 表示不展示 */
    val loadRemainMs: Long = 0L
)

/**
 * 播放器引擎：封装 ExoPlayer 竞速/hedged 播放逻辑。
 *
 * 从 tv4iptv PlayerActivity 移植，适配 Compose 生命周期。
 * 通过 [stateFlow] 暴露 UI 状态，通过 [player] 暴露 ExoPlayer 实例供 PlayerView 绑定。
 *
 * 播放策略（串行尝试，见 [startRace]）：
 * 按 planPlay 给出的顺序逐个尝试（直连 → 代理），同一时刻只存在 1 个 ExoPlayer，
 * 避免并行多路解码导致小内存设备 OOM；失败或超时（直连 [directTimeoutMs] / 代理 [proxyTimeoutMs]）
 * 即切换下一个尝试，全部用尽才走失败处理（切台）。
 *
 * 可配置（见 PlayerViewModel.PlaybackSettings）：
 * - stuckTimeoutMs：持续缓冲超过该时长且播放位置不前进 → 判定卡顿并原地重载
 *
 * 国内频道（country 为 CN/SK/澳门等）：完全不走代理，仅直连。
 */
class PlayerEngine(private val context: Context) {

    companion object {
        private const val TAG = "PlayerEngine"
        /** 老设备（Android 6.0.1 及以下，如海信老电视）适配阈值：降级为单路顺序播放，避免双路解码卡顿 */
        private const val LEGACY_SDK_MAX = 23
        private val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

        /**
         * 播放专用 OkHttpClient：与 ApiClient 的 client 隔离（后者挂了 logging-interceptor，
         * 播放是海量小分片请求，日志拦截器会带来严重性能与内存开销）。
         * 进程内单例共享以复用连接池：HLS 每几秒请求一个分片，连接复用可显著减少 TCP/TLS 重连开销。
         */
        private val playHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .connectionPool(ConnectionPool(8, 30, TimeUnit.SECONDS))
                .retryOnConnectionFailure(true)
                .build()
        }

        /** 记忆"上次成功播放模式"的 SP 文件与 key 前缀 */
        private const val PREF_PLAY_MODE = "play_mode_cache"
        private const val KEY_LAST_MODE = "last_mode_"
        /** 记忆条目上限，超出则整体清空，避免长期运行无界增长 */
        private const val MAX_PLAY_MODE_ENTRIES = 500
        // ===== 可被设置抽屉注入的播放参数（下方 var 为运行时值，此处为默认值） =====
        private const val DEFAULT_STUCK_TIMEOUT_MS = 8_000L   // 持续缓冲判定 stuck 的时长
        private const val DEFAULT_DIRECT_TIMEOUT_MS = 6_000L  // 直连候选起播超时（可由设置抽屉注入）
        private const val DEFAULT_PROXY_TIMEOUT_MS = 10_000L  // 代理候选起播超时（可由设置抽屉注入）

        // 判定为"国内"的渠道：完全不走代理（CN=中国, SK=韩国, MO/MACAU/澳门）
        private val DOMESTIC_COUNTRIES = setOf("CN", "SK", "MO", "MACAU", "澳门")
    }

    // ===== 可注入播放参数（由设置抽屉经 ViewModel 注入） =====
    /** 持续缓冲超过该时长且播放位置不前进 → 判定 stuck（触发原地重载） */
    var stuckTimeoutMs = DEFAULT_STUCK_TIMEOUT_MS

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
        directTimeoutMs = s.directTimeoutMs
        proxyTimeoutMs = s.proxyTimeoutMs
        DebugDiagnostics.setEnv(isLegacyDevice(), s.rendererMode.name)
        Log.d(TAG, "applyPlaybackSettings: stuck=${stuckTimeoutMs} direct=${directTimeoutMs} proxy=${proxyTimeoutMs} legacy=${isLegacyDevice()}")
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

    /** 收藏变更信号（添加 / 取消成功）：由 PlayerScreen 收集后通知 ViewModel 刷新侧边栏收藏列表 */
    private val _favoritesChanged = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
        replay = 0, extraBufferCapacity = 1
    )
    val favoritesChanged: kotlinx.coroutines.flow.Flow<Unit> = _favoritesChanged

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

    /** 安全释放单个 ExoPlayer：吞掉异常，避免销毁/异常态下 release 抛错中断后续逻辑 */
    private fun safeRelease(exo: ExoPlayer?) {
        if (exo == null) return
        try {
            DebugDiagnostics.onPlayerReleased()
            exo.stop()
            exo.release()
        } catch (t: Throwable) {
            Log.w(TAG, "safeRelease 异常: ${t.message}")
        }
    }

    /** 释放当前主播放器（仅存于 _playerRef、未进入 raceCandidates 的胜出 player），杜绝切台泄漏 */
    private fun releaseCurrentPlayer() {
        safeRelease(_playerRef.value)
        setPlayerRef(null)
    }

    private var playlist: List<PlaylistItem> = emptyList()
    private var currentIndex = 0
    /** 当前正在播放的 URL（供 stuck 复活竞速复用） */
    private var currentPlayingUrl: String = ""
    /** 当前频道的自定义请求头（UA/Referer），仅在直连源站时携带 */
    private var currentChannelHeaders: Map<String, String> = emptyMap()
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
    private var raceActive = false
    private var raceDecided = false
    private var raceCandidates = listOf<RaceCandidate>()
    private var racePlayToken = 0

    // ===== 串行起播状态（同时刻只存在 1 个 ExoPlayer） =====
    /** 当前起播的尝试序列（如 [direct, proxy]），按序串行尝试 */
    private var currentAttempts = listOf<String>()
    /** 当前正在尝试 [currentAttempts] 的下标（-1 表示尚未开始） */
    private var currentAttemptIndex = -1
    /** 单个尝试的起播超时协程：超时未 READY 即切下一个尝试 */
    private var attemptTimeoutJob: Job? = null
    /** 直连候选的起播超时：超时即切换下一个（连接类错误会立即切换，不走这里）。
     *  默认 6s：既能覆盖正常起播，又不会让"慢但不报错"的源拖太久；
     *  网络差 / 源响应慢时可在设置抽屉调到 10s，避免被误判为不可播。
     *  可由设置抽屉注入（applyPlaybackSettings），默认 [DEFAULT_DIRECT_TIMEOUT_MS] */
    var directTimeoutMs = DEFAULT_DIRECT_TIMEOUT_MS
    /** 代理候选专属起播超时：代理路径多一跳、握手更慢，给更长容忍，避免被误判为不可播。
     *  可由设置抽屉注入（applyPlaybackSettings），默认 [DEFAULT_PROXY_TIMEOUT_MS] */
    var proxyTimeoutMs = DEFAULT_PROXY_TIMEOUT_MS

    /** 由 PlayerScreen 注入的共享视频 Surface（PlayerView 的 videoSurface）。
     *  串行切换尝试时复用同一 Surface，避免 Surface 重建导致老电视"有声音没画面" */
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

    /** 从频道项提取 UA/Referer 作为请求头（防盗链用）。仅直连源站时携带，代理场景由代理端处理。 */
    private fun buildChannelHeaders(item: PlaylistItem): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        if (item.ua.isNotBlank()) headers["User-Agent"] = item.ua
        if (item.rf.isNotBlank()) headers["Referer"] = item.rf
        return headers
    }

    private fun playCurrent(ignoreLastMode: Boolean = false) {
        if (playlist.isEmpty()) return
        val item = playlist[currentIndex.coerceIn(0, playlist.lastIndex)]
        // 进入新一轮起播：清除重载荷载锁，允许后续（若仍卡死）再次兜底重载
        isReloading = false
        resetRace()
        safeRelease(player)
        setPlayerRef(null)
        flvRetryDone = false
        proxyRetryDone = false
        currentPlayingUrl = item.url
        // 频道自定义请求头（UA/Referer，防盗链用），仅直连源站时携带
        currentChannelHeaders = buildChannelHeaders(item)
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
        playUrl(item.url, item.country, ignoreLastMode = ignoreLastMode)
    }

    fun playNext() {
        if (playlist.isEmpty()) return
        currentIndex = (currentIndex + 1) % playlist.size
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
        reloadCurrentChannel(reason = "manual", ignoreLastMode = true)
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
                    // 通知 UI 层刷新收藏列表（侧边栏「收藏」分类），即时移除该频道
                    _favoritesChanged.tryEmit(Unit)
                } else {
                    // 添加收藏：后端用 url 去重，返回 url 作为标识
                    ApiClient.addFavorite(item.url, item.name, null, item.sourceId ?: "")
                    playlist = playlist.toMutableList().apply {
                        this[currentIndex] = item.copy(isFavorite = true, favId = item.url)
                    }
                    // 通知 UI 层刷新收藏列表（侧边栏「收藏」分类），即时加入该频道
                    _favoritesChanged.tryEmit(Unit)
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

    /**
     * 记忆上次成功模式：把上次胜出的模式排到尝试列表最前，
     * 使"必须走代理的源"无需每次先失败一次直连（省一次等待）。
     *
     * 串行兜底仍在（记忆模式失败会继续试其余候选），
     * 因此最坏情况退化为固定顺序 [direct, proxy]，不会比现状更差。
     */
    private fun reorderByLastMode(url: String, attempts: List<String>): List<String> {
        if (attempts.size <= 1) return attempts
        val last = loadLastMode(url) ?: return attempts
        if (last !in attempts) return attempts
        val reordered = listOf(last) + attempts.filter { it != last }
        if (reordered != attempts) Log.d(TAG, "记忆模式生效: $url -> $reordered")
        return reordered
    }

    /** url → 短 key（MD5），避免超长 url 直接作为 SP key */
    private fun modeKey(url: String): String = try {
        java.security.MessageDigest.getInstance("MD5")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    } catch (e: Exception) {
        url.hashCode().toString()
    }

    private fun loadLastMode(url: String): String? = try {
        context.getSharedPreferences(PREF_PLAY_MODE, Context.MODE_PRIVATE)
            .getString(KEY_LAST_MODE + modeKey(url), null)
    } catch (e: Exception) {
        null
    }

    private fun saveLastMode(url: String, mode: String) {
        try {
            val sp = context.getSharedPreferences(PREF_PLAY_MODE, Context.MODE_PRIVATE)
            // 条目超限：整体清空后重新累积
            if (sp.all.size >= MAX_PLAY_MODE_ENTRIES) {
                sp.edit().clear().apply()
            }
            sp.edit().putString(KEY_LAST_MODE + modeKey(url), mode).apply()
        } catch (e: Exception) {
            Log.w(TAG, "保存上次成功模式失败: ${e.message}")
        }
    }

    private fun playUrl(
        url: String,
        country: String = "",
        forceProxy: Boolean = false,
        ignoreLastMode: Boolean = false
    ) {
        if (url.isBlank()) {
            handlePlayFailure()
            return
        }
        currentPlayingUrl = url
        // 手动重载时忽略"记忆上次成功模式"，强制按 planPlay 原序（直连→代理）完整串行，
        // 避免上次代理胜出后重载直接跳到代理、漏掉直连这一遍。
        val attempts = if (forceProxy) listOf("proxy")
        else if (ignoreLastMode) planPlay(url, country)
        else reorderByLastMode(url, planPlay(url, country))
        Log.d(TAG, "planPlay($url) -> $attempts country=$country forceProxy=$forceProxy ignoreLastMode=$ignoreLastMode")
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
        // 缓冲策略（兼顾流畅与内存）：峰值内存 ≈ bitrate × maxBuffer。
        // 非老设备 maxBuffer 由 60s 降到 40s、老设备保持 30s，避免高码率 HLS 在小内存电视上撑爆内存 OOM。
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (legacy) 10_000 else 20_000,
                if (legacy) 30_000 else 40_000,
                1_000, 2_500
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // 网络栈：OkHttp（连接复用、HTTP/2、可定制 DNS/超时），替代 HttpURLConnection 版 DefaultHttpDataSource。
        // 注意：传入 client 后超时由 client 控制（connectTimeout / readTimeout），
        // OkHttpDataSource 自带的 setConnectTimeoutMs / setReadTimeoutMs 会被忽略，故不要在此设置。
        // 跨协议重定向（http→https）由 OkHttp 的 followSslRedirects 控制（默认 true），
        // 与原 setAllowCrossProtocolRedirects(true) 行为一致。
        val httpFactory = OkHttpDataSource.Factory(playHttpClient)
            .setUserAgent(BROWSER_UA)
        if (!useProxy && currentChannelHeaders.isNotEmpty()) {
            // 直连源站才携带频道自定义请求头；代理走自有 worker，无需转发 UA/Referer
            httpFactory.setDefaultRequestProperties(currentChannelHeaders)
        }
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
            // PREFER：优先硬件 MediaCodec 解码（省内存/CPU），仅当硬件不支持时才回退到扩展(ffmpeg)软解。
            // 原 ON 会强制优先软解，在电视盒子上极易因解码帧缓冲堆积触发 OOM。
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
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
            .setMaxVideoBitrate(if (legacy) 4_000_000 else 8_000_000)
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

    /**
     * 串行起播：按 [attempts] 顺序逐个尝试（直连 → 代理），同一时刻只存在 1 个 ExoPlayer。
     *
     * 相比旧的"并行竞速"（直连与代理同时拉流、先 READY 者胜）：
     * - 内存恒定单路：不再有 2~3 套解码器与大缓冲并存，根治小内存 TV 设备 OOM 闪退；
     * - 弱网更稳：不再把本已紧张的带宽翻倍（旧方案在弱网下是负反馈）；
     * - 逻辑线性：失败/超时即切下一个，无需 raceCandidates 多路状态机。
     *
     * 起播延迟由 [directTimeoutMs]（直连）/ [proxyTimeoutMs]（代理）控制：单个尝试超时即切换，
     * 而连接类错误（onPlayerError）会立即切换，几乎不增加等待。
     */
    private fun startRace(url: String, attempts: List<String>) {
        raceActive = true
        raceDecided = false
        // 防御性释放：任何残留的候选或上一频道 player（仅存 _playerRef）都先释放，
        // 杜绝切台/重载时旧 ExoPlayer + 缓冲未回收导致的内存泄漏
        for (c in raceCandidates) safeRelease(c.exo)
        releaseCurrentPlayer()
        raceCandidates = emptyList()
        currentAttempts = attempts
        currentAttemptIndex = -1
        updateState(isLoading = true)
        DebugDiagnostics.onRaceStart()
        Log.d(TAG, "串行起播: $attempts")
        tryNextAttempt(url)
    }

    /** 尝试 [currentAttempts] 中的下一个；全部用尽则走 [handlePlayFailure]（切台） */
    private fun tryNextAttempt(url: String) {
        val nextIndex = currentAttemptIndex + 1
        if (nextIndex >= currentAttempts.size) {
            Log.w(TAG, "串行起播：所有尝试均失败，走失败处理 url=$url")
            raceActive = false
            raceDecided = true
            stopAttemptTimeout()
            updateState(loadMode = null, loadTimeoutMs = 0, loadRemainMs = 0)
            handlePlayFailure()
            return
        }
        // 递增 token：使上一个尝试的 listener 立即失效，避免已释放 player 的回调串扰
        val myToken = ++racePlayToken
        currentAttemptIndex = nextIndex
        val attempt = currentAttempts[nextIndex]
        Log.d(TAG, "串行尝试[$nextIndex/${currentAttempts.size}]: $attempt")
        // 简洁展示：UI 加载区显示当前正在尝试的模式（直连/代理）与超时阈值
        val loadTimeout = if (attempt == "proxy") proxyTimeoutMs else directTimeoutMs
        updateState(loadMode = attempt, loadTimeoutMs = loadTimeout, loadRemainMs = loadTimeout)

        // 先释放上一个尝试的 player，确保同一时刻只有 1 个 ExoPlayer（内存恒定单路）
        for (c in raceCandidates) safeRelease(c.exo)

        val cand = RaceCandidate(attempt, null, false)
        cand.exo = createCandidatePlayer(url, attempt == "proxy", winner = true)
        setPlayerRef(cand.exo)
        raceCandidates = listOf(cand)
        attachCandidateListener(cand, url, myToken)
        startAttemptTimeout(url, myToken)
    }

    /** 单个尝试的起播超时：超过阈值仍未 READY → 切换下一个尝试；代理候选用更长超时 */
    private fun startAttemptTimeout(url: String, token: Int) {
        val attempt = currentAttempts.getOrNull(currentAttemptIndex) ?: return
        val timeout = if (attempt == "proxy") proxyTimeoutMs else directTimeoutMs
        attemptTimeoutJob?.cancel()
        attemptTimeoutJob = scope.launch {
            // 每秒刷新剩余时间供 UI 倒计时；倒计时归零即切换下一个候选。
            // 最后一步按真实剩余时间 delay，保证总时长精确等于 timeout（不被整秒步进拉长）
            var remainMs = timeout
            while (remainMs > 0) {
                if (token != racePlayToken || raceDecided) return@launch
                updateState(loadRemainMs = remainMs)
                val step = minOf(1_000L, remainMs)
                delay(step)
                remainMs -= step
            }
            if (token != racePlayToken || raceDecided) return@launch
            Log.w(TAG, "串行尝试超时(${timeout}ms)未起播，切换下一个: $attempt")
            tryNextAttempt(url)
        }
    }

    private fun stopAttemptTimeout() {
        attemptTimeoutJob?.cancel()
        attemptTimeoutJob = null
    }

    private fun attachCandidateListener(cand: RaceCandidate, url: String, token: Int) {
        val exo = cand.exo ?: return
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (token != racePlayToken || raceDecided) return
                when (state) {
                    Player.STATE_READY -> {
                        stopAttemptTimeout()
                        onRaceWin(cand, token)
                    }
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
                // 串行：当前尝试失败 → 立即切换下一个（连接类错误无需等待超时）
                stopAttemptTimeout()
                tryNextAttempt(url)
            }
        })
    }

    private fun onRaceWin(winner: RaceCandidate, token: Int) {
        if (raceDecided || token != racePlayToken) return
        raceDecided = true
        raceActive = false
        stopAttemptTimeout()
        consecutiveErrors = 0

        // 串行下同时刻只存在 1 个 player；此处释放仅作兜底
        for (c in raceCandidates) {
            if (c !== winner) safeRelease(c.exo)
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
        updateState(isLoading = false, isPlaying = true, error = null, usingProxy = winner.attempt == "proxy", loadMode = null, loadTimeoutMs = 0, loadRemainMs = 0)
        Log.d(TAG, "起播成功: ${winner.attempt}")
        // 记忆本次成功模式：下次起播直接优先该模式，省去先试错一次
        saveLastMode(currentPlayingUrl, winner.attempt)

        // 串行模式：不再并行复活代理（那会引入第二路解码，正是小内存设备 OOM 的主因）。
        // 播放期卡顿统一由 attachPlaybackWatcher → startPlaybackStuckMonitor 兜底：
        // 原地重载当前频道 → 仍失败则切下一台。
    }

    // ===== 播放期兜底监测（直连 / 代理胜出后均生效） =====
    // 串行模式下不再有"复活代理"（会引入第二路并行解码）。统一给已稳定播放的
    // player 增加缓冲超时监测：超时仍未恢复则重载当前频道，
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
    private fun reloadCurrentChannel(reason: String, ignoreLastMode: Boolean = false) {
        if (playlist.isEmpty()) return
        // 重载荷载锁：若已在 reload 流程中（竞速 watchdog / 播放期兜底可能同时触发），直接忽略，
        // 避免同一频道被并发多次 reload（表现为反复重新加载、台名反复闪烁）
        if (isReloading) {
            Log.d(TAG, "reloadCurrentChannel 忽略重复请求(reason=$reason)，已有 reload 在进行")
            return
        }
        isReloading = true
        // 立即终止旧的播放期与起播层监测，避免旧 player 在其延迟/超时窗口内再次触发 reload
        stopPlaybackStuckMonitor()
        stopAttemptTimeout()
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
        playCurrent(ignoreLastMode = ignoreLastMode)
    }

    private fun resetRace() {
        raceActive = false
        raceDecided = false
        stopAttemptTimeout()
        for (c in raceCandidates) safeRelease(c.exo)
        raceCandidates = emptyList()
        // 兜底释放可能仅存于 _playerRef 的上一频道 player（胜出但未进入 raceCandidates）
        releaseCurrentPlayer()
        currentAttempts = emptyList()
        currentAttemptIndex = -1
    }

    private fun handlePlayFailure() {
        stopAttemptTimeout()
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
            safeRelease(player)
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
            safeRelease(player)
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
        usingProxy: Boolean = _state.value.usingProxy,
        loadMode: String? = _state.value.loadMode,
        loadTimeoutMs: Long = _state.value.loadTimeoutMs,
        loadRemainMs: Long = _state.value.loadRemainMs
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
            usingProxy = usingProxy,
            loadMode = loadMode,
            loadTimeoutMs = loadTimeoutMs,
            loadRemainMs = loadRemainMs
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

    /** 停播：停止当前播放并释放解码器/渲染资源，保留播放列表，停留在当前界面（不退出 APP）。 */
    fun stopPlayback() {
        stopAttemptTimeout()
        resetRace()
        stopPlaybackStuckMonitor()
        safeRelease(player)
        setPlayerRef(null)
        sharedSurface = null
        updateState(
            isLoading = false,
            isPlaying = false,
            error = null,
            usingProxy = false,
            loadMode = null
        )
        showHint("已停止播放")
    }

    fun release() {
        stopAttemptTimeout()
        resetRace()
        infoHideJob?.cancel()
        hintHideJob?.cancel()
        safeRelease(player)
        setPlayerRef(null)
        sharedSurface = null
        playlist = emptyList()
        // 彻底取消内部协程作用域，避免离开播放界面后仍有挂起任务持有资源
        scope.cancel()
    }
}
