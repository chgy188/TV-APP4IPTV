package com.example.composedtv.player

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.common.C
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.example.composedtv.data.remote.ApiClient
import com.example.composedtv.data.local.LocalFavoritesStore
import com.example.composedtv.data.local.LocalFavorite
import com.example.composedtv.data.VodProgressStore
import com.example.composedtv.debug.DebugDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
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
    val rf: String = "",
    /** 强制直连（不走代理）：外部 App 以 ACTION_VIEW 唤起播放的本地/网络文件属于用户自有内容，
     *  不应走 IPTV 代理（代理仅用于解锁 geo 受限的供应方源），故强制直连优先。 */
    val forceDirect: Boolean = false
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
    val loadRemainMs: Long = 0L,
    /** 当前播放是否为直播（false 表示点播，可 seek / 显示控制条） */
    val isLive: Boolean = true,
    /** 当前加载速度估算（Kbps），0 表示暂无数据；加载中/播放中都可用 */
    val loadSpeedKbps: Int = 0,
    /** 真实下载速度（Kbps，按真实字节数测量）：停滞时为 0，用于区分"假速率"与真实传输 */
    val loadSpeedRealKbps: Int = 0,
    /** 当前尝试已下载总字节（MB，真实测量） */
    val totalBytesLoadedMB: Float = 0f,
    /** 当前尝试实际请求的 URL（直连为源地址；代理为 /api/hls?url= 包裹地址），供排查 */
    val currentLoadUrl: String = ""
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
            // 强制 TLS 1.2 / 1.3：老 Android 盒子（API≤22）平台默认禁用 TLS1.2，
            // 导致直连 https 流握手失败/挂起 → 表现为"一直缓冲"。OkHttp 的 ConnectionSpec
            // 会显式 setEnabledProtocols，覆盖平台默认，使 TLS1.2 在老设备也能启用；
            // TLS1.3 在 API<29 无平台实现，此处列出仅当设备支持时才协商，不影响回退到 1.2。
            val tlsSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
                .build()
            OkHttpClient.Builder()
                .connectionSpecs(listOf(tlsSpec, ConnectionSpec.CLEARTEXT))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)   // 切片下载半路卡住时给更长的续传窗口，抗抖动
                .connectionPool(ConnectionPool(8, 30, TimeUnit.SECONDS))
                // 快速失败：关闭 OkHttp 自动重连，死连接 / 握手失败立即抛错（分段重试交给 Media3
                // 的 LenientLoadErrorHandlingPolicy），避免"永久缓冲"掩盖真实错误让用户以为在加载。
                .retryOnConnectionFailure(false)
                .build()
        }

        /** 记忆"上次成功播放模式"的 SP 文件与 key 前缀 */
        private const val PREF_PLAY_MODE = "play_mode_cache"
        private const val KEY_LAST_MODE = "last_mode_"
        /** 记忆条目上限，超出则整体清空，避免长期运行无界增长 */
        private const val MAX_PLAY_MODE_ENTRIES = 500
    /**
     * 宽松加载错误策略：公共直播源切片常抖动/缺失/偶发 404。
     * - 提高切片与播放列表的重试次数（HLS 默认仅 3 次）；
     * - 放宽"子流排除"时长：默认对 403/404/410/416/500/503 排除 60s，
     *   这里缩短到 15s(track)/30s(location)，让偶发故障的子流更早被重试，
     *   避免误伤可用线路导致频繁转圈/重启。
     */
    private class LenientLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy() {
        override fun getMinimumLoadableRetryCount(dataType: Int): Int =
            if (dataType == C.DATA_TYPE_MEDIA
                || dataType == C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE
                || dataType == C.DATA_TYPE_MANIFEST) 6 else 3

        override fun getFallbackSelectionFor(
            fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
            loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo
        ): LoadErrorHandlingPolicy.FallbackSelection? {
            // 复用父类对 403/404/410/416/500/503 的判定
            if (!isEligibleForFallback(loadErrorInfo.exception)) return null
            if (fallbackOptions.isFallbackAvailable(LoadErrorHandlingPolicy.FALLBACK_TYPE_LOCATION)) {
                return LoadErrorHandlingPolicy.FallbackSelection(
                    LoadErrorHandlingPolicy.FALLBACK_TYPE_LOCATION, 30_000L
                )
            } else if (fallbackOptions.isFallbackAvailable(LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK)) {
                return LoadErrorHandlingPolicy.FallbackSelection(
                    LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK, 15_000L
                )
            }
            return null
        }
    }

        // ===== 可被设置抽屉注入的播放参数（下方 var 为运行时值，此处为默认值） =====
        private const val DEFAULT_DIRECT_TIMEOUT_MS = 20_000L  // 直连候选起播超时（可由设置抽屉注入）；默认取最长可选项 20s
        private const val DEFAULT_PROXY_TIMEOUT_MS = 10_000L  // 代理候选起播超时（可由设置抽屉注入）
        private const val DEFAULT_SMOOTH_PRIORITY = false      // 流畅优先（降分辨率）默认关
        private const val DEFAULT_AUTO_AV_SYNC = true          // 音画同步自动恢复默认开
        // 音画同步自动检测参数
        private const val AV_SYNC_CHECK_MS = 2_000L             // 采样周期(ms)
        private const val AV_SYNC_DROP_THRESHOLD = 25          // 窗口内掉帧数 ≥ 该值判定音画漂移
        private const val AV_SYNC_COOLDOWN_MS = 8_000L         // 两次自动恢复最小间隔(ms)
        private const val AV_SYNC_MAX_AUTO = 2                 // 单频道自动恢复上限（第2次起临时降分辨率）
        // 起播/重载后热身窗口：解码器爬坡期掉帧属正常，期间不判定音画漂移，避免误触发重载
        private const val AV_SYNC_WARMUP_MS = 6_000L
        // 直播滑窗(BLW)恢复节流 / 升级参数
        private const val BLW_THROTTLE_MS = 3_000L             // 两次重建解码器最小间隔，避免高频重建
        private const val BLW_WINDOW_MS = 30_000L              // 滑窗计数窗口
        private const val BLW_MAX_PER_WINDOW = 4               // 窗口内超过该次数 → 升级降码率 / 停自动恢复

        // 判定为"国内"的渠道：完全不走代理（CN=中国, SK=韩国, MO/MACAU/澳门）
        private val DOMESTIC_COUNTRIES = setOf("CN", "SK", "MO", "MACAU", "澳门")
    }

    // ===== 可注入播放参数（由设置抽屉经 ViewModel 注入） =====

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

    /** 字幕偏好语言（外部本地文件播放时设置，如 ["zh","en"]）；null 表示不强制 */
    private var preferredTextLanguages: List<String>? = null

    /** 把设置抽屉里的值同步进 engine（PlaybackSettings 定义在 PlayerViewModel） */
    fun applyPlaybackSettings(s: com.example.composedtv.viewmodel.PlaybackSettings) {
        directTimeoutMs = s.directTimeoutMs
        proxyTimeoutMs = s.proxyTimeoutMs
        smoothPriority = s.smoothPriority
        autoAvSync = s.autoAvSync
        DebugDiagnostics.setEnv(isLegacyDevice(), s.rendererMode.name)
        Log.d(TAG, "applyPlaybackSettings: direct=${directTimeoutMs} proxy=${proxyTimeoutMs} legacy=${isLegacyDevice()}")
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

    /**
     * 首帧是否已渲染：UI 据此撤下「渐变占位蒙版」，避免起播/切台时的黑屏。
     * 每次新一轮起播置 false，播放器回调 onRenderedFirstFrame 时置 true。
     */
    private val _firstFrameRendered = kotlinx.coroutines.flow.MutableStateFlow(false)
    val firstFrameFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = _firstFrameRendered

    /** 缓冲水位 0..1：供「水杯注水」动画展示；起播归零、起播成功补满 */
    private val _bufferProgress = kotlinx.coroutines.flow.MutableStateFlow(0f)
    val bufferProgressFlow: kotlinx.coroutines.flow.StateFlow<Float> = _bufferProgress
    private var bufferSamplerJob: Job? = null
    /** 视为「满杯」的可播放缓冲量（毫秒），略高于起播阈值 500ms */
    private val bufferFullMs = 2_000L

    /**
     * 起播缓冲采样：以 bufferedPosition - currentPosition（还能播多久）作为真实水位。
     * 建连/无数据阶段用极缓时间底水位兜底；单调不回退 + 平滑步进，形成缓缓注水的观感。
     */
    private fun startBufferSampling() {
        bufferSamplerJob?.cancel()
        _bufferProgress.value = 0f
        val startedAt = android.os.SystemClock.elapsedRealtime()
        bufferSamplerJob = scope.launch {
            while (true) {
                val p = player
                val aheadMs = if (p != null) {
                    val bp = p.bufferedPosition
                    val cp = p.currentPosition
                    if (bp == C.TIME_UNSET || cp == C.TIME_UNSET) 0L
                    else (bp - cp).coerceAtLeast(0L)
                } else 0L
                val fromData = (aheadMs.toFloat() / bufferFullMs).coerceIn(0f, 1f)
                val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
                val fromTime = (elapsed / 9000f).coerceIn(0f, 0.22f)
                val target = maxOf(fromData, fromTime)
                val cur = _bufferProgress.value
                if (target > cur) {
                    _bufferProgress.value = (cur + (target - cur) * 0.22f).coerceAtMost(1f)
                }
                delay(120)
            }
        }
    }

    /** 起播成功 / 首帧到达：补满水杯并停止采样 */
    private fun finishBufferSampling() {
        bufferSamplerJob?.cancel()
        bufferSamplerJob = null
        _bufferProgress.value = 1f
    }

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
    /** 当前正在播放的 URL（供原地重载复用） */
    private var currentPlayingUrl: String = ""
    /** 当前频道的自定义请求头（UA/Referer），仅在直连源站时携带 */
    private var currentChannelHeaders: Map<String, String> = emptyMap()
    private var consecutiveErrors = 0

    /** 点播进度周期保存协程（每 5s 写入 VodProgressStore，直到切台/重载/播完） */
    private var vodProgressJob: Job? = null
    /** 当前播放是否为直播流（默认按直播处理，避免电视直播误显示点播控制条） */
    private var isLiveStream = true
    /** 当前点播已播放完毕：禁止再把片尾位置写回，否则下次进入会直接跳到结尾 */
    private var vodFinished = false
    /** 本轮起播已展示过续播提示的 URL，避免 STATE_READY 重入导致提示重复 */
    private var resumeHintShownUrl: String? = null
    /** 本轮起播的续播目标位置（ms）：prepare 前注入 MediaItem，并由兜底协程持续补 seek */
    private var pendingResumeMs = 0L
    /** 续播是否已真正落到播放位置上；只有为 true 才提示「已为您续播」并允许覆盖进度记录 */
    private var resumeSettled = false

    /** 开启本轮续播守护：有目标时，定位成功前不写更小的进度、不显示续播提示 */
    private fun armResumeGuard() {
        resumeSettled = pendingResumeMs <= 0
    }

    /** 续播是否「尚未生效」：目标未落地且当前位置明显落后于目标。
     *  不设时间窗口——只要位置还没追上原记录，就不能用更小的位置覆盖它，
     *  否则一次续播失败会把 27s 的记录改写成 3s，续播永久失效。 */
    private fun resumeNotSettled(pos: Long): Boolean =
        pendingResumeMs > 0 && !resumeSettled && pos < pendingResumeMs - 3_000

    /** 续播位置确认生效：清掉守护状态，并在此刻才提示用户，避免「提示已续播、实际从头播」 */
    private fun onResumeSettled(targetMs: Long, pos: Long) {
        if (resumeSettled) return
        resumeSettled = true
        pendingResumeMs = 0L
        Log.d(TAG, "续播已生效: pos=${pos}ms target=${targetMs}ms")
        showResumeHint(targetMs)
    }

    /**
     * prepare 前注入的起始位置在少数容器（SeekMap/Cues 位于文件尾的 MKV over HTTP）会被
     * clamp 回 0。此时 duration 长期为 C.TIME_UNSET、isCurrentMediaItemSeekable() 为 false，
     * 若把它们当作 seek 前置条件，兜底协程会一次 seekTo 都不发（旧实现即如此）。
     *
     * ExoPlayer 在 SeekMap 未就绪时会把 seek 记为 pending，待 Timeline/SeekMap 就绪后应用，
     * 因此这里首轮即无条件 seekTo，并在窗口内持续复查 / 重试，直到位置真正达到目标。
     */
    private fun scheduleResumeFallback(exo: ExoPlayer, targetMs: Long) {
        if (targetMs <= 0) return
        scope.launch {
            repeat(20) { round ->
                delay(if (round == 0) 500L else 1_500L)
                if (_playerRef.value !== exo || isLiveStream || vodFinished) return@launch
                if (resumeSettled || pendingResumeMs <= 0) return@launch
                val pos = exo.currentPosition
                if (pos >= targetMs - 3_000) {
                    onResumeSettled(targetMs, pos)
                    return@launch
                }
                val duration = exo.duration
                val seekable = exo.isCurrentMediaItemSeekable()
                if (round == 0 || round % 3 == 2) {
                    Log.w(
                        TAG,
                        "续播兜底: 第${round + 1}轮 pos=${pos}ms target=${targetMs}ms " +
                            "duration=${duration}ms seekable=$seekable state=${exo.playbackState}"
                    )
                }
                // 前 8 轮每轮补一次，之后降频，避免 SeekMap 长期未就绪时反复打断缓冲。
                if (round < 8 || round % 3 == 2) {
                    exo.seekTo(targetMs)
                    Log.d(TAG, "续播兜底: 发出 seekTo(${targetMs}ms)")
                }
            }
            if (_playerRef.value !== exo || isLiveStream || vodFinished || resumeSettled) return@launch
            val pos = exo.currentPosition
            if (pos >= targetMs - 3_000) {
                onResumeSettled(targetMs, pos)
            } else {
                Log.w(TAG, "续播兜底窗口结束(未定位成功): pos=${pos}ms target=${targetMs}ms，保留原进度记录")
            }
        }
    }

    init {
        // 每次播放器创建时清理隔天的点播进度记录，落实「当天有效」语义
        VodProgressStore.pruneStale(context)
    }

    /** 续播提示：同一轮起播只提示一次，让用户明确感知已从当天记录的位置继续播放 */
    private fun showResumeHint(resumeMs: Long) {
        if (resumeHintShownUrl == currentPlayingUrl) return
        resumeHintShownUrl = currentPlayingUrl
        showHint("已为您续播 ${VodProgressStore.formatPosition(resumeMs)}")
    }

    /** 立即持久化当前点播进度（切台 / 重载 / 进入后台 / 释放前调用）。直播与已播完的内容不记录。 */
    private fun saveVodProgressNow() {
        if (isLiveStream || vodFinished) return
        val p = player ?: return
        val pos = p.currentPosition
        if (resumeNotSettled(pos)) {
            Log.w(TAG, "跳过保存(续播未生效): pos=${pos}ms < 目标=${pendingResumeMs}ms，保留原记录")
            return
        }
        if (pos > 0) {
            VodProgressStore.save(context, currentPlayingUrl, pos)
            Log.d(TAG, "保存点播进度: ${pos}ms key=$currentPlayingUrl")
        }
    }

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
    /** 真实速率/已下载采样协程 */
    private var connStatsJob: Job? = null
    /** 真实已下载总字节（由 AnalyticsListener 的 PlaybackStats 写入），用于计算真实速率 */
    @Volatile private var lastTotalBytes = 0L
    /** 当前尝试开始时间(ms)，用于停滞看门狗宽限期 */
    private var attemptStartedAt = 0L
    /** 连续"实际速率为 0 且仍在加载"的秒数，超阈值判停滞并切下一候选 */
    private var stallSeconds = 0
    /** 停滞看门狗阈值(秒)：加载中且真实速率持续为 0 超过该值即切下一候选（解决点播直连无限卡住） */
    private val STALL_FAIL_SEC = 12
    /** 直连候选的起播超时：超时即切换下一个（连接类错误会立即切换，不走这里）。
     *  默认 20s（设置里的最长可选项）：弱网 / 慢源也能稳定起播，不被过早切走；
     *  点播场景直连不设时限（见 [startAttemptTimeout]），此处仅用于直播直连。
     *  可由设置抽屉注入（applyPlaybackSettings），默认 [DEFAULT_DIRECT_TIMEOUT_MS] */
    var directTimeoutMs = DEFAULT_DIRECT_TIMEOUT_MS
    /** 代理候选专属起播超时：代理路径多一跳、握手更慢，给更长容忍，避免被误判为不可播。
     *  可由设置抽屉注入（applyPlaybackSettings），默认 [DEFAULT_PROXY_TIMEOUT_MS] */
    var proxyTimeoutMs = DEFAULT_PROXY_TIMEOUT_MS
    /** 流畅优先：强制降到 720p/4Mbps，降低解码负载，缓解弱机/部分频道音画不同步 */
    var smoothPriority = DEFAULT_SMOOTH_PRIORITY
    /** 音画同步自动恢复：持续掉帧判定为音视频漂移时自动重载当前频道重新同步 */
    var autoAvSync = DEFAULT_AUTO_AV_SYNC
    /** 右键重载顺序：本次是否优先代理（每次重载 toggle，方便排查"哪条路径能播"） */
    private var manualReloadProxyFirst = false

    /** 由 PlayerScreen 注入的共享视频 Surface（PlayerView 的 videoSurface）。
     *  串行切换尝试时复用同一 Surface，避免 Surface 重建导致老电视"有声音没画面" */
    private var sharedSurface: android.view.Surface? = null

    // 信息条自动隐藏
    private var infoHideJob: Job? = null
    // transientHint 自动清理
    private var hintHideJob: Job? = null

    /** 字幕选项（供外部播放时 UI 展示与选择） */
    data class SubtitleOption(
        val id: String,
        val label: String,
        val language: String?,
        val selected: Boolean
    )

    /** 设置字幕偏好语言顺序（如 ["zh","en"]）；对当前与后续创建的播放器生效 */
    fun setPreferredTextLanguages(langs: List<String>) {
        preferredTextLanguages = langs
        applyPreferredTextLanguages()
    }

    /** 读取当前播放器的字幕轨列表（仅 text 类型） */
    fun getSubtitleTracks(): List<SubtitleOption> {
        val player = playerFlow.value ?: return emptyList()
        val result = mutableListOf<SubtitleOption>()
        player.currentTracks.groups.forEachIndexed { gi, group ->
            if (group.type != C.TRACK_TYPE_TEXT) return@forEachIndexed
            for (i in 0 until group.length) {
                val fmt = group.getTrackFormat(i)
                val label = fmt.label ?: fmt.language ?: "字幕 ${i + 1}"
                result.add(
                    SubtitleOption(
                        id = "$gi:$i",
                        label = label,
                        language = fmt.language,
                        selected = group.isTrackSelected(i)
                    )
                )
            }
        }
        return result
    }

    /** 选择字幕：传 null 关闭字幕；否则按该轨语言启用，并记忆为后续播放器的偏好 */
    fun selectSubtitle(option: SubtitleOption?) {
        val player = playerFlow.value ?: return
        val params = player.trackSelectionParameters.buildUpon()
        if (option == null) {
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            preferredTextLanguages = null
        } else {
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            if (option.language != null) {
                preferredTextLanguages = listOf(option.language)
                params.setPreferredTextLanguages(option.language)
            }
        }
        player.trackSelectionParameters = params.build()
    }

    /** 将已设置的字幕偏好语言应用到当前播放器 */
    private fun applyPreferredTextLanguages() {
        val langs = preferredTextLanguages ?: return
        playerFlow.value?.let { p ->
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setPreferredTextLanguages(*langs.toTypedArray())
                .build()
        }
    }

    /** 设置播放列表并播放指定 index */
    fun setPlaylist(items: List<PlaylistItem>, startIndex: Int = 0) {
        playlist = items
        currentIndex = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        consecutiveErrors = 0
        resetAvSyncForNewChannel()
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

    private fun playCurrent(ignoreLastMode: Boolean = false, proxyFirst: Boolean = false) {
        if (playlist.isEmpty()) return
        // 切台/重载前先保存上一个点播源进度：仅靠 5s 周期最多会丢 5 秒；
        // 此处 currentPlayingUrl 尚未被新频道覆盖，保存的正是旧内容。
        saveVodProgressNow()
        vodFinished = false
        resumeHintShownUrl = null
        val item = playlist[currentIndex.coerceIn(0, playlist.lastIndex)]
        // 外部媒体：强制直连，跳过 IPTV 代理（见 PlaylistItem.forceDirect）
        // 新一轮起播：重新显示渐变占位蒙版，避免切台瞬间露出黑屏
        _firstFrameRendered.value = false
        // 水杯注水动画从空杯重新开始
        startBufferSampling()
        // 进入新一轮起播：清除重载荷载锁，允许后续（若仍卡死）再次兜底重载
        isReloading = false
        resetRace()
        safeRelease(player)
        setPlayerRef(null)
        currentPlayingUrl = item.url
        // 频道自定义请求头（UA/Referer，防盗链用），仅直连源站时携带
        currentChannelHeaders = buildChannelHeaders(item)
        DebugDiagnostics.setChannel(item.name, item.url, false)
            // 直播/点播判定（初始猜测；起播后由 refineLiveFromPlayer 用 ExoPlayer 二次校正）：
        // 本地文件 / 点播容器扩展名 → 点播；直播协议 / .m3u/.m3u8/.ts/.flv / 直播平台关键字 → 直播。
        // .m3u8 含 #EXT-X-ENDLIST 的「点播型」与 FLV 直播由播放器解析后校正。详见 isLiveStreamUrl。
        isLiveStream = isLiveStreamUrl(item.url)
        // 本轮续播目标：初始即判定为点播时，在 createCandidatePlayer 里通过
        // setMediaItem(item, startPositionMs) 于 prepare 前注入，避开 SeekMap 未就绪导致的 seek 失败。
        pendingResumeMs = if (!isLiveStream) VodProgressStore.load(context, currentPlayingUrl) else 0L
        armResumeGuard()
        // 点播内容：默认优先中文字幕（zh→en 回退），用户手动选择后以用户选择为准
        if (!isLiveStream && preferredTextLanguages == null) {
            setPreferredTextLanguages(listOf("zh", "en"))
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
        playUrl(item.url, item.country, forceDirect = item.forceDirect,
            ignoreLastMode = ignoreLastMode, proxyFirst = proxyFirst)
    }

    fun playNext() {
        if (playlist.isEmpty()) return
        currentIndex = (currentIndex + 1) % playlist.size
        showInfoTransient()
        resetAvSyncForNewChannel()
        playCurrent()
    }

    fun playPrev() {
        if (playlist.isEmpty()) return
        currentIndex = (currentIndex - 1 + playlist.size) % playlist.size
        showInfoTransient()
        resetAvSyncForNewChannel()
        playCurrent()
    }

    /** 播放指定 index（从侧边栏选择频道时使用） */
    fun playAtIndex(index: Int) {
        if (playlist.isEmpty() || index !in playlist.indices) return
        if (index == currentIndex) return
        currentIndex = index
        resetAvSyncForNewChannel()
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

    /** 是否为直播（false 表示点播，可 seek） */
    val isLive: Boolean get() = isLiveStream

    /**
     * 根据 URL 初步判断是否为直播流（初始猜测；HLS (.m3u8/.m3u/.ts/.flv) 是否含 #EXT-X-ENDLIST
     * 最终以播放器解析为准，见 [refineLiveFromPlayer]）。优先级从高到低：
     *  1) 本地/外部文件（file://、content://）→ 点播
     *  2) 明确的点播容器扩展名（mp4/mkv/mov/webm/avi/wmv/3gp/mpeg/mpg/vob/m2ts/m4v）→ 点播
     *  3) 明确的直播协议（rtmp/rtsp/udp/rtp/srt）→ 直播
     *  4) 明确的直播格式（.m3u/.m3u8/.ts/.flv）→ 直播
     *  5) 直播平台关键字（live/flv/rtmp/hls/huya/douyu 等，无扩展名兜底）→ 直播
     *  6) 其余（无扩展名、无关键字的普通 http(s) 直链）→ 默认点播，交给播放器校正
     */
    private fun isLiveStreamUrl(rawUrl: String): Boolean {
        val u = rawUrl.lowercase()
        if (u.startsWith("file://") || u.startsWith("content://")) return false
        if (u.contains(".mp4") || u.contains(".mkv") || u.contains(".mov") ||
            u.contains(".webm") || u.contains(".avi") || u.contains(".wmv") ||
            u.contains(".3gp") || u.contains(".mpeg") || u.contains(".mpg") ||
            u.contains(".vob") || u.contains(".m2ts") || u.contains(".m4v")
        ) return false
        if (u.startsWith("rtmp://") || u.startsWith("rtsp://") ||
            u.startsWith("udp://") || u.startsWith("rtp://") || u.startsWith("srt://")
        ) return true
        if (u.contains(".m3u8") || u.contains(".m3u") || u.contains(".ts") || u.contains(".flv")) return true
        if (u.contains("live") || u.contains("flv") || u.contains("rtmp") ||
            u.contains("hls") || u.contains("huya") || u.contains("douyu")
        ) return true
        return false
    }

    /**
     * 起播后由 ExoPlayer 二次校正直播/点播判定：
     * - HLS（.m3u/.m3u8/.ts/.flv）：以播放器解析结果为准——含 #EXT-X-ENDLIST 的「点播型 m3u8」会识别为点播；
     * - 无扩展名直播关键字：ExoPlayer 无法判别是否直播，信任 URL 启发式（保持直播）；
     * - 其余（容器格式 / 本地文件）：URL 启发式已判为点播，保持不变。
     */
    private fun refineLiveFromPlayer(exo: ExoPlayer, rawUrl: String) {
        val url = rawUrl.lowercase()
        val isHlsOrTs = url.contains(".m3u8") || url.contains(".m3u") || url.contains(".ts") || url.contains(".flv")
        val exoLive = exo.isCurrentMediaItemLive()
        val finalLive = if (isHlsOrTs) exoLive else isLiveStream
        if (finalLive != isLiveStream) {
            isLiveStream = finalLive
            updateState(isLive = finalLive)
            if (!finalLive) {
                // 校正为点播：补做断点续播 + 周期保存。
                // （HLS 点播型在 onRaceWin 时 isLiveStream 仍为 true，只能在此处识别为点播）
                setPreferredTextLanguages(listOf("zh", "en"))
                val resume = VodProgressStore.load(context, currentPlayingUrl)
                pendingResumeMs = resume
                armResumeGuard()
                if (resume > 0) {
                    Log.d(TAG, "恢复点播播放位置(HLS校正): ${resume}ms，等待定位生效后提示")
                    // 不在此立即 seekTo：STATE_READY 时 Timeline 可能未就绪，seek 会被 clamp 回 0；
                    // 由兜底协程首轮开始持续补 seekTo，并在真正生效后才提示。
                    scheduleResumeFallback(exo, resume)
                }
                startVodProgressSaver(exo)
            } else {
                stopVodProgressSaver()
            }
            Log.d(TAG, "refineLiveFromPlayer: $rawUrl -> isLive=$finalLive (exoLive=$exoLive)")
        }
    }

    /** 当前播放位置（ms）；直播返回 0 */
    fun getCurrentPosition(): Long = player?.currentPosition ?: 0L

    /** 总时长（ms）；未知/直播返回 0（ExoPlayer 用 C.TIME_UNSET 表示未知） */
    fun getDuration(): Long {
        val p = player ?: return 0L
        val d = p.duration
        return if (d == C.TIME_UNSET) 0L else d
    }

    /** 点播跳转（绝对位置，ms） */
    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs.coerceAtLeast(0L))
    }

    /** 点播相对跳转（ms，可正可负） */
    fun seekBy(deltaMs: Long) {
        val p = player ?: return
        seekTo(p.currentPosition + deltaMs)
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
        // 每次右键重载 toggle 直连/代理优先顺序，方便排查"哪条路径能播"。
        // 首次按为直连优先（与默认一致），再次按切到代理优先，如此交替。
        // 外部媒体（forceDirect）始终直连，不进入代理优先分支。
        val forceDirectItem = playlist[currentIndex].forceDirect
        val proxyFirst = if (forceDirectItem) false else manualReloadProxyFirst
        if (!forceDirectItem) manualReloadProxyFirst = !manualReloadProxyFirst
        Log.d(TAG, "手动重载当前频道(代理优先=$proxyFirst): $currentPlayingUrl")
        showHint(
            "正在重载：${playlist[currentIndex].name}（${if (proxyFirst) "代理优先" else "直连优先"}）"
        )
        // 手动重载视为用户主动介入：清零自动恢复计数，使其重新拥有自动自愈机会
        avSyncAutoCount = 0
        forceSmoothThisReload = false
        avSyncCooldownUntil = 0
        // 清空直播滑窗升级/节流状态，给用户一个干净的重新起播机会
        blwEscalated = false
        blwRecoverCount = 0
        reloadCurrentChannel(reason = "manual", ignoreLastMode = true, proxyFirst = proxyFirst)
    }

    fun toggleFavorite() {
        if (playlist.isEmpty()) {
            Log.w(TAG, "toggleFavorite: 播放列表为空")
            showHint("暂无频道可收藏")
            return
        }
        val item = playlist[currentIndex]
        scope.launch {
            try {
                if (item.isFavorite) {
                    // 取消收藏：本地收藏用 local:// 前缀，否则走后端（按 url 删除）
                    val idToRemove = item.favId?.takeIf { it.isNotEmpty() } ?: item.url
                    if (idToRemove.startsWith("local://")) {
                        LocalFavoritesStore.remove(context, item.url)
                    } else {
                        ApiClient.removeFavorite(idToRemove)
                    }
                    playlist = playlist.toMutableList().apply {
                        this[currentIndex] = item.copy(isFavorite = false, favId = null)
                    }
                    showHint("已取消收藏：${item.name}")
                    // 通知 UI 层刷新收藏列表（侧边栏「收藏」分类），即时移除该频道
                    _favoritesChanged.tryEmit(Unit)
                } else {
                    if (!ApiClient.isLoggedIn) {
                        // 未登录（如外部 App 唤起播放）回退到本地收藏，无需后端鉴权
                        LocalFavoritesStore.add(
                            context,
                            LocalFavorite(
                                name = item.name,
                                url = item.url,
                                country = item.country,
                                ua = item.ua,
                                rf = item.rf
                            )
                        )
                        playlist = playlist.toMutableList().apply {
                            this[currentIndex] = item.copy(isFavorite = true, favId = "local://${item.url}")
                        }
                        showHint("已收藏(本地)：${item.name}")
                    } else {
                        // 添加收藏：后端用 url 去重，返回 url 作为标识
                        ApiClient.addFavorite(item.url, item.name, null, item.sourceId ?: "")
                        playlist = playlist.toMutableList().apply {
                            this[currentIndex] = item.copy(isFavorite = true, favId = item.url)
                        }
                        showHint("已收藏：${item.name}")
                    }
                    // 通知 UI 层刷新收藏列表（侧边栏「收藏」分类），即时加入该频道
                    _favoritesChanged.tryEmit(Unit)
                }
                updateState(isFavorite = playlist[currentIndex].isFavorite, showInfo = true)
                scheduleInfoHide()
            } catch (e: Exception) {
                Log.w(TAG, "toggleFavorite failed: ${e.message}")
                showHint("操作失败：${e.message ?: "未知错误"}")
            }
        }
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
        // 所有设备（含 legacy / 极米）统一串行 [direct, proxy]：
        // 串行模式下同时刻仅存在 1 个 ExoPlayer，不会双解码 OOM，
        // legacy 也安全享受"直连失败→代理兜底"，无需 handlePlayFailure 里的旧代理补试分支。
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
        // 播放模式记忆按用户隔离（不同账号的频道可达性不同）
        context.getSharedPreferences(ApiClient.userSpName(PREF_PLAY_MODE), Context.MODE_PRIVATE)
            .getString(KEY_LAST_MODE + modeKey(url), null)
    } catch (e: Exception) {
        null
    }

    private fun saveLastMode(url: String, mode: String) {
        try {
            val sp = context.getSharedPreferences(ApiClient.userSpName(PREF_PLAY_MODE), Context.MODE_PRIVATE)
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
        ignoreLastMode: Boolean = false,
        proxyFirst: Boolean = false,
        forceDirect: Boolean = false
    ) {
        if (url.isBlank()) {
            handlePlayFailure()
            return
        }
        currentPlayingUrl = url
        // 外部媒体（forceDirect）：用户自有内容，强制直连、完全不走代理
        val attempts = if (forceDirect) {
            listOf("direct")
        } else if (forceProxy) {
            listOf("proxy")
        } else if (proxyFirst) {
            val base = planPlay(url, country)
            if (base == listOf("direct")) base else listOf("proxy") + base
        } else if (ignoreLastMode) {
            planPlay(url, country)
        } else {
            reorderByLastMode(url, planPlay(url, country))
        }
        Log.d(TAG, "planPlay($url) -> $attempts country=$country forceProxy=$forceProxy ignoreLastMode=$ignoreLastMode proxyFirst=$proxyFirst")
        startRace(url, attempts)
    }

    // ===== 竞速播放 =====
    private fun createCandidatePlayer(url: String, useProxy: Boolean, winner: Boolean): ExoPlayer {
        val lower = url.lowercase()
        val isHls = lower.contains(".m3u8") || lower.contains(".m3u")
        // 代理只支持 HLS（hlsProxyUrl 仅包裹 .m3u8）；非 HLS 仍走原 url
        val finalUrl = if (useProxy && isHls) ApiClient.hlsProxyUrl(url) else url
        val legacy = isLegacyDevice()

        // 缓冲策略（兼顾出画速度 / 抗抖动 / 内存）：
        // - initialBuffer 500ms：起播快（仅攒 0.5s 数据即出画）
        // - minBuffer 30s（老设备 15s）：起播后维持的最小缓冲，调大以吸收网络抖动、少转圈
        // - maxBuffer 40s（老设备 30s）：网络好时多缓冲应对后续波动；峰值内存 ≈ bitrate × maxBuffer，
        //   非老设备由 60s 降到 40s、老设备保持 30s，避免高码率 HLS 在小内存电视上撑爆内存 OOM
        // - rebuffer 后缓冲 5s：卡顿恢复后需更多缓冲，减少二次卡顿
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (legacy) 15_000 else 30_000,   // minBuffer 调大：起播后吸收抖动，少转圈
                if (legacy) 30_000 else 40_000,
                500, 5_000                         // 初缓冲仍 500ms（起播快）；rebuffer 后缓冲 2.5s→5s，减少二次卡顿
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
        val baseDataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        // 直连 MKV 点播：修补头部 SeekHead 的 Cues 条目，让 MatroskaExtractor 能建立可 seek 的
        // SeekMap。这类源把 Cues 位置只写在文件尾 SeekHead，ExoPlayer 取不到就恒为
        // seekable=false，断点续播的 startPositionMs / seekTo 都会被 clamp 回 0。
        // 直播、HLS、代理地址与其它容器一律走原始数据源；探测失败时补丁数据源自动透传。
        val isDirectMkv = !useProxy && !isHls &&
            finalUrl.substringBefore('?').endsWith(".mkv", ignoreCase = true)
        val dataSourceFactory: DataSource.Factory = if (isDirectMkv) {
            DataSource.Factory {
                MkvCuesPatchDataSource(
                    baseDataSourceFactory.createDataSource(),
                    finalUrl,
                    currentChannelHeaders,
                    BROWSER_UA,
                    playHttpClient
                )
            }
        } else baseDataSourceFactory

        // 不强制单一 Extractor：DefaultMediaSourceFactory 默认会从流首字节嗅探容器
        // （FLV 的 "FLV" magic、MP4 的 ftyp、TS 的 0x47…），按实际协议选择解析器，
        // 从根本上识别 FLV，而非依赖 URL 关键字。
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            .setLoadErrorHandlingPolicy(LenientLoadErrorHandlingPolicy())

        // 本项目已引入 FFmpeg 软解扩展（build.gradle.kts: media3-ffmpeg-decoder），
        // ON = 系统 MediaCodec 优先、FFmpeg 扩展兜底；PREFER = 扩展软解优先（老设备易卡）。
        // 保持 ON，只让「系统解码器不支持/不可用」的格式落到 FFmpeg，避免全局软解增加 CPU 负担。
        //
        // 老设备闪退防护：Android 5/6 的 ACodec 在配置 MP3 解码器时会执行
        // CHECK_EQ(mOMX->getParameter(..., OMX_IndexParamAudioMp3, ...), OK)；部分厂商 OMX 组件
        // 不支持该参数，返回 -1010 后直接触发 __android_log_assert -> native SIGABRT
        // （MediaCodec_loop 线程），Java 层 try/catch 完全挡不住。切台遇到 MP3 音轨（或异常流
        // 被嗅探为 MP3）时表现为「播放失败后整个 App 闪退」，见 logcat 的 Fatal signal 6。
        // 规避：老设备上让 MediaCodecSelector 对 MP3 家族返回空列表，MediaCodecAudioRenderer
        // 便不会创建该解码器，TrackSelector 会改选已打包的 FfmpegAudioRenderer 软解；
        // 即便扩展加载失败也只是跳过音频轨（无声），不会再拖垮进程。
        val mediaCodecSelector = if (legacy) {
            MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                if (mimeType.startsWith("audio/mpeg")) {
                    emptyList()
                } else {
                    MediaCodecSelector.DEFAULT.getDecoderInfos(
                        mimeType, requiresSecureDecoder, requiresTunnelingDecoder
                    )
                }
            }
        } else MediaCodecSelector.DEFAULT

        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setMediaCodecSelector(mediaCodecSelector)
            // 首选解码器初始化失败时，继续尝试设备上其它 MediaCodec 实现（老设备常有多个）
            .setEnableDecoderFallback(true)
            // API 21/22 的 AudioTrack 对 ENCODING_PCM_FLOAT 支持不完整，强制 16-bit PCM 输出
            .setEnableAudioFloatOutput(false)

        val p = ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setBandwidthMeter(
                DefaultBandwidthMeter.Builder(context).build().also { bwm ->
                    bwm.addEventListener(Handler(Looper.getMainLooper())) { _, _, bitrateEstimate ->
                        if (bitrateEstimate > 0) {
                            updateState(loadSpeedKbps = (bitrateEstimate / 1000).toInt())
                        }
                    }
                }
            )
            .build()

        // 自适应码率（ABR）：信号差自动降分辨率，信号好自动升分辨率
        // - 新设备上限 1080p / 10Mbps；老设备上限 720p / 4Mbps（解码能力弱，降级保流畅）
        // - 允许降到任意低码率，保证流畅
        // - 不强制最低/最高，由 ExoPlayer 根据实时带宽自适应
        // 分辨率/码率上限：流畅优先或自动恢复临时降级时强制 720p/4Mbps，降低解码负载缓解不同步
        val cap1080 = !legacy && !smoothPriority && !forceSmoothThisReload
        forceSmoothThisReload = false
        p.trackSelectionParameters = p.trackSelectionParameters
            .buildUpon()
            .setMaxVideoSize(if (cap1080) 1920 else 1280, if (cap1080) 1080 else 720)
            .setMaxVideoBitrate(if (cap1080) 8_000_000 else 4_000_000)
            .setMinVideoBitrate(0)
            .setForceLowestBitrate(false)
            .setForceHighestSupportedBitrate(false)
            .build()

            // 外部本地文件播放时设置的字幕偏好语言（中文→英文），应用到本播放器
            preferredTextLanguages?.let { langs ->
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguages(*langs.toTypedArray())
            .build()
            }

        if (!winner) p.volume = 0f

        // 共享 Surface：副路候选（如代理）也绑定 PlayerView 的 Surface，才能真正出画参与竞速；
        // 且胜出切换 player 时 Surface 不重建，避免老设备"有声音没画面"
        sharedSurface?.let { p.setVideoSurface(it) }

        val builder = MediaItem.Builder().setUri(finalUrl)
        when {
            isHls -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            // 非 HLS（FLV/MP4/TS 等）不设置 mime，交给容器嗅探
        }
        if (isHls) {
            builder.setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(25_000L)
                    .setMinOffsetMs(10_000L)
                    .setMaxOffsetMs(60_000L)
                    .build()
            )
        }
        // 续播：prepare 前把起始位置注入 MediaItem。MKV/MP4 等容器在 STATE_READY 瞬间
        // SeekMap(Cues) 可能尚未解析完，此时 seekTo 会被 clamp 回 0（表现为"续播不生效"）；
        // 由 ExoPlayer 在 prepare 过程中应用 startPositionMs 则不受该时序影响。
        val resumeMs = pendingResumeMs
        if (resumeMs > 0) {
            p.setMediaItem(builder.build(), resumeMs)
            Log.d(TAG, "prepare 前注入续播位置: ${resumeMs}ms url=$finalUrl")
        } else {
            p.setMediaItem(builder.build())
        }
        // 首帧渲染信号：一旦真正出画，UI 立即撤下渐变占位蒙版（切台/起播不再有黑屏）。
        // 仅当前绑定的主 player 的首帧有效，避免竞速中被淘汰候选的回调误触发。
        p.addListener(object : Player.Listener {
            // 每个 player 只兜底改选一次，避免 trackSelectionParameters 变更再次触发
            // onTracksChanged 形成回调循环
            private var audioFallbackDone = false

            override fun onRenderedFirstFrame() {
                if (player === p) {
                    _firstFrameRendered.value = true
                    finishBufferSampling()
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                // 记录音轨 codec / 声道 / 采样率 / 是否被系统解码器支持，用于定位「有画面无声音」
                DebugDiagnostics.onAudioTracks(tracks)
                if (!audioFallbackDone && ensureSupportedAudioTrack(p, tracks)) {
                    audioFallbackDone = true
                }
            }
        })
        // 诊断：挂 AnalyticsListener 采集解码器名称/初始化耗时/视频格式/丢帧
        p.addAnalyticsListener(DebugDiagnostics.newAnalyticsListener())
        DebugDiagnostics.onPlayerCreated()
        DebugDiagnostics.log(
            "Engine",
            "createCandidate attempt=${if (useProxy) "proxy" else "direct"} winner=$winner " +
                "hls=$isHls legacy=$legacy mp3Guard=$legacy url=$finalUrl"
        )
        p.prepare()
        p.playWhenReady = true
        p.repeatMode = Player.REPEAT_MODE_OFF
        return p
    }

    /**
     * 音轨兜底：若当前没有「已选中且受系统解码器支持」的音频轨，显式改选一条受支持的轨。
     *
     * 背景：MKV 常带多音轨（如 AC-3 5.1 主轨 + AAC 2.0 备用轨）。DefaultTrackSelector 可能按
     * 语言/默认标记选中了老设备无法解码的那条，ExoPlayer 会直接跳过该轨继续播视频，
     * 表现为「有画面无声音」。此兜底把选择切到设备真正能解的轨。
     *
     * 若所有音频轨都不受支持（系统 MediaCodec 被屏蔽/缺失且 FFmpeg 扩展也解不了），
     * 则不做任何改动：ExoPlayer 会跳过音频轨，画面照常播放，只是无声。
     *
     * @return 是否实际应用了一次改选
     */
    private fun ensureSupportedAudioTrack(exo: ExoPlayer, tracks: Tracks): Boolean {
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i) && group.isTrackSupported(i)) return false
            }
        }
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val fmt = group.getTrackFormat(i)
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                    .addOverride(TrackSelectionOverride(group.mediaTrackGroup, i))
                    .build()
                Log.i(TAG, "音轨兜底：改选受支持音轨 ${fmt.sampleMimeType}/${fmt.codecs} " +
                    "ch=${fmt.channelCount} hz=${fmt.sampleRate}")
                DebugDiagnostics.onAudioFallback(
                    "${fmt.sampleMimeType}/${fmt.codecs} ch=${fmt.channelCount} hz=${fmt.sampleRate}"
                )
                return true
            }
        }
        // 没有任何受支持的音轨：记录明确结论，供 HUD / logcat 判断
        if (tracks.containsType(C.TRACK_TYPE_AUDIO)) {
            Log.w(TAG, "所有音频轨都不受本设备 MediaCodec 支持：视频可播但将无声音（需软解扩展或换源）")
        }
        return false
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
        // 换候选期间重新显示渐变占位蒙版，直到新候选真正出画
        _firstFrameRendered.value = false
        currentAttemptIndex = nextIndex
        val attempt = currentAttempts[nextIndex]
        Log.d(TAG, "串行尝试[$nextIndex/${currentAttempts.size}]: $attempt")
        // 简洁展示：UI 加载区显示当前正在尝试的模式（直连/代理）与超时阈值
        // 点播直连不设时限：VOD 采用无限等待，直至出画或连接类错误（不会因超时被切走）；
        // 但为防"连接停滞（无数据）无限卡住"，由 startConnStatsSampler 的停滞看门狗兜底。
        val loadTimeout = when {
            attempt == "proxy" -> proxyTimeoutMs
            isLiveStream -> directTimeoutMs
            else -> 0L
        }
        // 记录本次尝试实际请求的 URL（代理为 /api/hls?url= 包裹地址），供前端展示与排查
        val finalUrl = if (attempt == "proxy") ApiClient.hlsProxyUrl(url) else url
        updateState(
            loadMode = attempt,
            loadTimeoutMs = loadTimeout,
            loadRemainMs = loadTimeout,
            currentLoadUrl = finalUrl
        )
        // 重置真实字节统计并启动采样（含停滞看门狗）
        lastTotalBytes = 0L
        attemptStartedAt = System.currentTimeMillis()
        stallSeconds = 0
        startConnStatsSampler()

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
        // 点播直连不设时限：不启动超时协程，直至出画或连接类错误（不会因超时被切走）
        val timeout = when {
            attempt == "proxy" -> proxyTimeoutMs
            isLiveStream -> directTimeoutMs
            else -> return
        }
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

    /**
     * 真实速率/已下载采样：每秒采样一次，把真实下载速率与累计字节写入状态供 UI 展示；
     * 并内置停滞看门狗——加载中且真实速率持续为 0（排除前 4s 连接建立宽限期）超过阈值，
     * 判定连接已停滞（无数据），自动切到下一个候选（点播直连无超时，靠它避免无限卡住）。
     */
    private fun startConnStatsSampler() {
        connStatsJob?.cancel()
        lastTotalBytes = 0L
        var prevBytes = 0L
        attemptStartedAt = System.currentTimeMillis()
        stallSeconds = 0
        connStatsJob = scope.launch {
            while (true) {
                delay(1_000)
                if (raceDecided) break
                val total = lastTotalBytes
                // 真实速率(Kbps) = 1s 窗口内字节增量 × 8 / 1000
                val kbps = ((total - prevBytes) * 8 / 1000).toInt()
                prevBytes = total
                DebugDiagnostics.setConnInfo(kbps, total / 1_000_000f, _state.value.currentLoadUrl)
                val loadingNow = _state.value.isLoading
                val elapsed = System.currentTimeMillis() - attemptStartedAt
                if (loadingNow && kbps <= 0 && elapsed > 4_000L) {
                    stallSeconds++
                    if (stallSeconds >= STALL_FAIL_SEC) {
                        Log.w(TAG, "连接停滞看门狗：加载中长时间无数据传输，切下一候选 url=${_state.value.currentLoadUrl}")
                        stallSeconds = 0
                        tryNextAttempt(currentPlayingUrl)
                        break
                    }
                } else {
                    stallSeconds = 0
                }
                updateState(loadSpeedRealKbps = kbps, totalBytesLoadedMB = total / 1_000_000f)
            }
        }
    }

    private fun stopAttemptTimeout() {
        attemptTimeoutJob?.cancel()
        attemptTimeoutJob = null
    }

    private fun attachCandidateListener(cand: RaceCandidate, url: String, token: Int) {
        val exo = cand.exo ?: return
        // 真实下载字节统计：每次分片/区间加载完成累加 bytesLoaded，供真实速率与停滞看门狗使用
        // 真实下载字节统计：每次分片/区间加载完成累加 bytesLoaded，供真实速率计算与停滞看门狗使用
        exo.addAnalyticsListener(object : AnalyticsListener {
            override fun onLoadCompleted(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData
            ) {
                lastTotalBytes += loadEventInfo.bytesLoaded
            }
        })
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (token != racePlayToken || raceDecided) return
                when (state) {
                    Player.STATE_READY -> {
                        stopAttemptTimeout()
                        refineLiveFromPlayer(exo, url)
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
                            .setTargetOffsetMs(25_000L).setMinOffsetMs(10_000L).setMaxOffsetMs(60_000L).build()
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
            // 点播内容：续播位置已在 createCandidatePlayer 通过 setMediaItem(item, startPositionMs)
            // 于 prepare 前注入。MKV over HTTP 可能在 prepare 时把该位置 clamp 回 0，
            // 由 scheduleResumeFallback 持续补 seekTo，并在位置真正落地后才提示用户。
            // HLS 点播型在 refineLiveFromPlayer 校正后才识别为点播，那里补做。
            if (!isLiveStream) {
                val resume = pendingResumeMs
                if (resume > 0) {
                    Log.d(TAG, "续播位置已注入: ${resume}ms，等待定位生效后提示")
                    scheduleResumeFallback(winExo, resume)
                }
                startVodProgressSaver(winExo)
            } else {
                stopVodProgressSaver()
            }
            attachPlaybackWatcher(winExo, winner.attempt)
            startAvSyncWatcher(winExo)
            // 显式触发一次直播/点播二次校正：候选在 READY 后才 onRaceWin，新监听器不会重放
            // STATE_READY，故 HLS 点播型必须在此主动校正，否则断点续播/保存会被漏掉。
            refineLiveFromPlayer(winExo, currentPlayingUrl)
        }
        raceCandidates = emptyList()
        updateState(isLoading = false, isPlaying = true, error = null, usingProxy = winner.attempt == "proxy", loadMode = null, loadTimeoutMs = 0, loadRemainMs = 0)
        // 起播成功即补满水杯（水位以 tween 平滑注满后随首帧淡出）
        finishBufferSampling()
        // 兜底：纯音频流 / 个别设备不回调 onRenderedFirstFrame 时，延时强制撤下渐变占位蒙版，
        // 避免真实画面被永久遮挡。校验 URL 防止旧任务的延时回调串到新一轮起播。
        val fallbackUrl = currentPlayingUrl
        scope.launch {
            delay(1500)
            if (currentPlayingUrl == fallbackUrl && !_firstFrameRendered.value) {
                _firstFrameRendered.value = true
                finishBufferSampling()
            }
        }
        Log.d(TAG, "起播成功: ${winner.attempt}")
        // 记忆本次成功模式：下次起播直接优先该模式，省去先试错一次
        saveLastMode(currentPlayingUrl, winner.attempt)

        // 串行模式：不再并行复活代理（那会引入第二路解码，正是小内存设备 OOM 的主因）。
        // 播放期卡顿不再自动重载，交由用户手动处理（音画同步自动恢复除外）。
    }

    // ===== 重载荷载锁 =====
    // 防止"手动重载 / 音画同步自动恢复"与竞速流程并发触发同一频道多次 reload
    private var isReloading = false

    // ===== 音画同步自动恢复 =====
    private var avSyncJob: Job? = null
    private var avSyncPrimed = false
    private var avSyncLastDropped = 0
    private var avSyncLastCheck = 0L
    private var avSyncCooldownUntil = 0L
    private var avSyncAutoCount = 0
    /** 自动恢复二次触发时临时强制 720p，降低解码负载以真正跟住音画 */
    private var forceSmoothThisReload = false
    // 起播/重载后热身截止时间(ms)：热身期内忽略掉帧，避免解码器爬坡误判音画漂移
    private var avSyncWarmupUntil = 0L
    // 直播滑窗(BLW)恢复节流状态
    private var lastBehindLiveRecoverMs = 0L
    private var blwRecoverCount = 0
    private var blwRecoverWindowStart = 0L
    private var blwEscalated = false                           // 本频道是否已升级到降码率重载

    /** 新频道起播：复位音画同步检测计数（避免上一频道的漂移计数污染新频道） */
    private fun resetAvSyncForNewChannel() {
        stopAvSyncWatcher()
        avSyncPrimed = false
        avSyncLastDropped = 0
        avSyncLastCheck = 0L
        avSyncCooldownUntil = 0L
        avSyncAutoCount = 0
        forceSmoothThisReload = false
        // 重置热身与直播滑窗节流状态（避免上一频道的漂移/重连计数污染新频道）
        avSyncWarmupUntil = 0L
        lastBehindLiveRecoverMs = 0L
        blwRecoverCount = 0
        blwRecoverWindowStart = 0L
        blwEscalated = false
    }

    /**
     * 音画同步看门狗：正常播放期间周期性采样视频掉帧计数。
     * 若 2s 窗口内持续掉帧 ≥ 阈值，说明视频解码跟不上音频（典型音画漂移），
     * 则自动重载当前频道以重新建立音视频同步（最多 [AV_SYNC_MAX_AUTO] 次，
     * 首次即临时降到 720p 降低解码负载；多档源才能真正跟住，单档源降码率无效时停止自动恢复）。
     */
    private fun startAvSyncWatcher(exo: ExoPlayer) {
        stopAvSyncWatcher()
        avSyncPrimed = false
        avSyncLastDropped = exo.videoDecoderCounters?.droppedBufferCount ?: 0
        avSyncLastCheck = System.currentTimeMillis()
        // 起播/重载后的热身窗口：解码器爬坡期会大量掉帧，属正常，期间不判定漂移
        avSyncWarmupUntil = System.currentTimeMillis() + AV_SYNC_WARMUP_MS
        avSyncJob = scope.launch {
            while (true) {
                delay(AV_SYNC_CHECK_MS)
                if (!autoAvSync) continue
                val p = _playerRef.value ?: continue
                if (p !== exo) break                                 // 已切台/重载，退出
                if (p.playbackState != Player.STATE_READY || p.isLoading) continue
                val dropped = p.videoDecoderCounters?.droppedBufferCount ?: avSyncLastDropped
                val now = System.currentTimeMillis()
                // 热身窗口内：仅更新基线，不判定（避免解码器爬坡掉帧误触发重载）
                if (now < avSyncWarmupUntil) {
                    avSyncLastDropped = dropped
                    avSyncLastCheck = now
                    continue
                }
                if (!avSyncPrimed) {                                // 首个 READY 采样仅作基线
                    avSyncLastDropped = dropped
                    avSyncLastCheck = now
                    avSyncPrimed = true
                    continue
                }
                val dt = now - avSyncLastCheck
                if (dt < AV_SYNC_CHECK_MS) continue
                val delta = dropped - avSyncLastDropped
                avSyncLastDropped = dropped
                avSyncLastCheck = now
                if (delta >= AV_SYNC_DROP_THRESHOLD) {
                    Log.w(TAG, "音画漂移检测: ${AV_SYNC_CHECK_MS}ms 内掉帧 $delta，触发自动重新同步")
                    triggerAvSyncRecover()
                }
            }
        }
    }

    private fun stopAvSyncWatcher() {
        avSyncJob?.cancel()
        avSyncJob = null
    }

    /**
     * 音画同步自动恢复：检测到漂移后降级重载当前频道。
     * 优化点（P0 防"频繁解码器重启"）：
     * - 第 1 次漂移即强制降码率重载（降分辨率才是真正解药，避免"同分辨率重载→仍卡→再重载"的空转）；
     * - 已降到 720p 仍漂移（弱机/单档源）：停止自动恢复并提示用户手动处理，不再反复重建解码器。
     */
    private fun triggerAvSyncRecover() {
        val now = System.currentTimeMillis()
        if (isReloading || now < avSyncCooldownUntil || avSyncAutoCount >= AV_SYNC_MAX_AUTO) return
        avSyncCooldownUntil = now + AV_SYNC_COOLDOWN_MS
        avSyncAutoCount++
        // 首次漂移直接强制降码率重载：多档源降到 720p 真正缓解，单档源无效则第 2 次停止
        forceSmoothThisReload = true
        if (avSyncAutoCount >= AV_SYNC_MAX_AUTO) {
            // 已尝试降码率仍漂移：停止自动恢复，避免无限重建解码器，交用户手动处理
            Log.w(TAG, "音画同步自动恢复已达上限(${AV_SYNC_MAX_AUTO})，停止自动重载，请手动处理")
            showHint("已降码率仍卡顿，请按右键手动重载")
            return
        }
        Log.w(TAG, "音画同步自动恢复[${avSyncAutoCount}/$AV_SYNC_MAX_AUTO] 降码率重载当前频道")
        reloadCurrentChannel(reason = "av-sync-drift")
    }

    /**
     * 直播滑窗(BLW)恢复：弱网下播放位置滑出直播窗口时 ExoPlayer 抛 BehindLiveWindowException。
     * 朴素地"每次都重建解码器"会在弱网高清频道形成死循环（落后→重建→重缓冲→再落后→再重建），
     * 表现为"频繁解码器重启 + 1~2s 频繁缓冲"。这里加入节流与升级：
     * - [BLW_THROTTLE_MS] 内不重复重建解码器（让其自然追赶/缓冲）；
     * - [BLW_WINDOW_MS] 窗口内超过 [BLW_MAX_PER_WINDOW] 次 → 判定带宽不足，强制降码率重载；
     * - 已降过码率仍反复 → 彻底停止自动恢复，避免无限重建，交用户手动处理。
     */
    private fun recoverBehindLiveWindow(exo: ExoPlayer, attempt: String) {
        val now = System.currentTimeMillis()
        // 节流：距上次重建不足阈值，先跳过本次，避免高频重建解码器
        if (now - lastBehindLiveRecoverMs < BLW_THROTTLE_MS) {
            Log.d(TAG, "BehindLiveWindow 重建节流中(${now - lastBehindLiveRecoverMs}ms)，本次跳过")
            return
        }
        lastBehindLiveRecoverMs = now
        // 滑动窗口计数：超出窗口则重置
        if (now - blwRecoverWindowStart > BLW_WINDOW_MS) {
            blwRecoverWindowStart = now
            blwRecoverCount = 0
        }
        blwRecoverCount++
        if (blwRecoverCount >= BLW_MAX_PER_WINDOW) {
            if (!blwEscalated) {
                // 首次升级：强制降码率重载（多档源降到 720p，根因是带宽不足）
                blwEscalated = true
                Log.w(TAG, "BehindLiveWindow 反复触发(${blwRecoverCount}次/窗口)，升级为降码率重载")
                forceSmoothThisReload = true
                reloadCurrentChannel(reason = "blw-escalate")
            } else {
                // 已降过码率仍反复：停止自动恢复，避免无限重建解码器，交用户手动处理
                Log.w(TAG, "BehindLiveWindow 升级降码率后仍反复，停止自动恢复: $currentPlayingUrl")
                showHint("直播流频繁重连，已停止自动恢复，请按右键重载或换源")
            }
            return
        }
        // 常规轻量恢复：同一 ExoPlayer 内重建 MediaItem 并重 prepare（追直播边缘），不新建解码器实例
        Log.w(TAG, "直播滑窗补偿(${blwRecoverCount}/${BLW_MAX_PER_WINDOW}): $currentPlayingUrl")
        exo.stop()
        exo.clearMediaItems()
        val finalUrl = if (attempt == "proxy") ApiClient.hlsProxyUrl(currentPlayingUrl) else currentPlayingUrl
        val builder = MediaItem.Builder().setUri(finalUrl).setMimeType(MimeTypes.APPLICATION_M3U8)
        builder.setLiveConfiguration(
            MediaItem.LiveConfiguration.Builder()
                .setTargetOffsetMs(25_000L).setMinOffsetMs(10_000L).setMaxOffsetMs(60_000L).build()
        )
        exo.setMediaItem(builder.build())
        exo.prepare()
        exo.playWhenReady = true
    }

    /**
     * 胜出后的播放期监听器（仅观察状态，不自动重载）：
     * - STATE_BUFFERING：显示 spinner
     * - STATE_READY：恢复 spinner
     * - STATE_ENDED / onPlayerError：仅记录日志（直播滑窗仍自动 seek 恢复），不再自动重载/跳台。
     *   用户如需恢复，请按右键手动重载，或交由音画同步自动恢复处理。
     */
    /**
     * 把 Media3 的 PlaybackException 错误码映射为中文可读含义，供前端错误提示展示。
     * 未知码回退到「未知错误（码=xxx）」，避免只显示一串英文常量名。
     */
    private fun playbackErrorMeaning(error: PlaybackException): String {
        // DNS 解析失败：当前 Media3 版本未暴露 ERROR_CODE_IO_DNS_FAILED 常量，
        // 改为检查 cause（跨版本通用）——DNS 失败时 cause 为 UnknownHostException。
        if (error.cause is java.net.UnknownHostException) {
            return "DNS 解析失败：域名无法解析，检查网络 / DNS 或换源。"
        }
        return when (val code = error.errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                "HTTP 返回错误状态码（多为 403 鉴权失败 / 404 源失效）。点播播放中突然出现，通常是分片或字节区间被重新请求时源站拒绝 / 地址失效（常见 Token 过期）；重试通常无效，需修正频道请求头或换源。"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                "网络连接失败（多为 5xx / 服务器无响应），可能是瞬时故障，可手动重载或切代理。"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "网络连接超时：弱网或源站响应慢，可手动重载或切代理。"
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                "地址不存在（404 类）：源地址失效，需换源。"
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ->
                "HTTP Content-Type 异常：返回的不是音视频流（可能是错误页）。"
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
                "无 INTERNET 网络权限，请检查权限。"
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED ->
                "明文 HTTP 被禁止（高版本 Android 默认不允许 http），需源站支持 https 或放开配置。"
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ->
                "读取位置越界（点播 seek 超出范围）。"
            PlaybackException.ERROR_CODE_TIMEOUT ->
                "操作超时。"
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ->
                "直播滑窗落后：已自动恢复（一般不会看到此提示）。"
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
                "媒体索引 / 容器解析失败：源格式损坏或不是预期格式。"
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
                "不支持的媒体容器格式。"
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                "解码器初始化失败：设备不支持该编码，可尝试降码率 / 换源。"
            PlaybackException.ERROR_CODE_DECODING_FAILED ->
                "解码失败：片源损坏或设备解码异常，可降码率重试。"
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
                "视频规格超出设备解码能力（分辨率 / 码率过高），建议降码率。"
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                "设备不支持该视频编码格式。"
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ->
                "音频输出失败：音频轨道异常，可重试或检查音频设置。"
            PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED ->
                "DRM 许可证过期。"
            PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED ->
                "DRM 许可证获取失败。"
            PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED ->
                "设备不支持该 DRM 方案。"
            else -> "未知错误（码=$code），详见 logcat。"
        }
    }

    /** 停止点播进度周期保存协程 */
    private fun stopVodProgressSaver() {
        vodProgressJob?.cancel()
        vodProgressJob = null
    }

    /**
     * 启动点播进度周期保存：每 5s 将当前位置写入 VodProgressStore（key=currentPlayingUrl，
     * 外部文件与应用内点播通用）。player 被替换（切台/重载）或播放结束时自动退出。
     */
    private fun startVodProgressSaver(exo: ExoPlayer) {
        stopVodProgressSaver()
        vodProgressJob = scope.launch {
            while (true) {
                delay(5_000)
                if (_playerRef.value !== exo) break   // 已切台/重载，退出
                if (vodFinished) break                 // 已播完，禁止把片尾位置写回
                val pos = exo.currentPosition
                // 续播尚未生效时不写入，避免把旧记录覆盖成片头位置
                if (resumeNotSettled(pos)) continue
                if (pos > 0) VodProgressStore.save(context, currentPlayingUrl, pos)
            }
        }
    }

    private fun attachPlaybackWatcher(exo: ExoPlayer, attempt: String) {
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> updateState(isLoading = true)
                    Player.STATE_READY -> {
                        refineLiveFromPlayer(exo, currentPlayingUrl)
                        updateState(isLoading = false, isPlaying = true, error = null)
                    }
                    Player.STATE_ENDED ->
                        if (!isLiveStream) {
                            vodFinished = true
                            Log.i(TAG, "点播播放完成，清除续播记录: $currentPlayingUrl")
                            VodProgressStore.clear(context, currentPlayingUrl)
                            stopVodProgressSaver()
                        } else {
                            Log.w(TAG, "播放期 STATE_ENDED: $currentPlayingUrl")
                        }
                    Player.STATE_IDLE -> { /* no-op */ }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val cause = error.cause
                if (cause is androidx.media3.exoplayer.source.BehindLiveWindowException) {
                    // 直播滑出窗口：自动恢复（带节流/升级，避免弱网高清频道"频繁解码器重启"死循环）
                    Log.w(TAG, "播放期 BehindLiveWindow，auto-recover: $currentPlayingUrl")
                    recoverBehindLiveWindow(exo, attempt)
                    return
                }
                Log.e(TAG, "播放期 onPlayerError: ${error.errorCodeName} url=$currentPlayingUrl", error)
                DebugDiagnostics.onError("playback/$attempt", error)
                // 不再自动重载/跳台：显示「错误码 + 中文含义」，交由用户手动处理
                updateState(
                    isLoading = false,
                    error = "播放出错：${error.errorCodeName}\n${playbackErrorMeaning(error)}"
                )
            }
        })
    }

    /**
     * 重载当前频道：原地重新走一次 playCurrent（含竞速）。
     * 对于点播内容（非直播），保存当前播放位置，重载后 seekTo 恢复进度。
     * 与 handlePlayFailure 不同：不增加 consecutiveErrors、不跳下一台。
     */
    private fun reloadCurrentChannel(reason: String, ignoreLastMode: Boolean = false, proxyFirst: Boolean = false) {
        if (playlist.isEmpty()) return
        // 重载荷载锁：若已在 reload 流程中（竞速 watchdog / 播放期兜底可能同时触发），直接忽略，
        // 避免同一频道被并发多次 reload（表现为反复重新加载、台名反复闪烁）
        if (isReloading) {
            Log.d(TAG, "reloadCurrentChannel 忽略重复请求(reason=$reason)，已有 reload 在进行")
            return
        }
        isReloading = true
        // 立即终止旧的起播层监测，避免旧 player 在其延迟/超时窗口内再次触发 reload
        stopAttemptTimeout()
        // 点播内容：保存当前播放位置到持久化 Store，重载后由 Store 断点续播
        // （playCurrent 开头也会兜底保存一次，同一 key 写入幂等）
        saveVodProgressNow()
        Log.d(TAG, "reloadCurrentChannel(reason=$reason) idx=$currentIndex url=$currentPlayingUrl isLive=$isLiveStream")
        DebugDiagnostics.onReload(reason)
        playCurrent(ignoreLastMode = ignoreLastMode, proxyFirst = proxyFirst)
    }

    private fun resetRace() {
        raceActive = false
        raceDecided = false
        stopAttemptTimeout()
        stopAvSyncWatcher()
        stopVodProgressSaver()
        connStatsJob?.cancel()
        connStatsJob = null
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
        // 点播（VOD）不自动切台：直播源常抖动/缺失，候选用尽后切下一台是合理的容错；
        // 但点播是具体节目，自动切到列表下一个会"偷偷换片"，应直接报错交用户手动处理（换源/重载）。
        if (!isLiveStream) {
            Log.w(TAG, "点播播放失败，不自动切台: $currentPlayingUrl")
            updateState(error = "点播播放失败：${currentPlayingUrl}", isLoading = false, isPlaying = false)
            return
        }
        // 直播：所有候选（直连/代理）串行用尽即切下一台；FLV 重试与 legacy 代理补试已并入串行链路，
        // 不再单独处理。
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
        loadRemainMs: Long = _state.value.loadRemainMs,
        isLive: Boolean = isLiveStream,
        loadSpeedKbps: Int = _state.value.loadSpeedKbps,
        loadSpeedRealKbps: Int = _state.value.loadSpeedRealKbps,
        totalBytesLoadedMB: Float = _state.value.totalBytesLoadedMB,
        currentLoadUrl: String = _state.value.currentLoadUrl
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
            loadRemainMs = loadRemainMs,
            isLive = isLive,
            loadSpeedKbps = loadSpeedKbps,
            loadSpeedRealKbps = loadSpeedRealKbps,
            totalBytesLoadedMB = totalBytesLoadedMB,
            currentLoadUrl = currentLoadUrl
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
    /** 是否因进入后台而暂停：避免用户主动暂停后，回到前台被强制恢复播放 */
    private var pausedByLifecycle = false

    fun onResume() {
        // 只恢复「由 onPause 主动暂停」的播放，尊重用户手动暂停状态
        if (pausedByLifecycle) {
            player?.playWhenReady = true
            pausedByLifecycle = false
        }
    }

    fun onPause() {
        // 点播：进入后台前持久化进度，确保被系统回收也能断点续播
        saveVodProgressNow()
        if (player?.playWhenReady == true) {
            player?.playWhenReady = false
            pausedByLifecycle = true
        }
    }

    /** 停播：停止当前播放并释放解码器/渲染资源，保留播放列表，停留在当前界面（不退出 APP）。 */
    fun stopPlayback() {
        // 同 release()：resetRace() 会清空 player，保存必须在此之前
        saveVodProgressNow()
        bufferSamplerJob?.cancel()
        bufferSamplerJob = null
        stopAttemptTimeout()
        resetRace()
        stopAvSyncWatcher()
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
        // 必须在 resetRace() 之前保存：resetRace() 内的 releaseCurrentPlayer() 会清空
        // _playerRef，之后再读 currentPosition 恒为 0（旧实现即因此从未保存成功）。
        saveVodProgressNow()
        stopAttemptTimeout()
        resetRace()
        stopAvSyncWatcher()
        stopVodProgressSaver()
        connStatsJob?.cancel()
        connStatsJob = null
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