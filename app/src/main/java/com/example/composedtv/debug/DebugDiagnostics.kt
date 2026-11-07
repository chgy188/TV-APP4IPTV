package com.example.composedtv.debug

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.example.composedtv.BuildConfig
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * 无 ADB 环境下的播放诊断中心。
 *
 * 背景：极米 H1（Android 5.1.1 / armeabi-v7a）无法保证能开 ADB，
 * 需要把 ExoPlayer 内部状态直接暴露到屏幕上（HUD）或局域网（HTTP）。
 *
 * 采集分两类：
 * 1) 轮询类：ExoPlayer.getVideoDecoderCounters() —— 拿到解码器 init 次数、
 *    渲染帧数、丢帧数、「丢到关键帧」次数。这几个数是判断
 *    "解码器反复重启" 与 "退化成只出关键帧（图片轮播）" 的直接证据。
 * 2) 事件类：AnalyticsListener —— 解码器名称、视频格式、错误。
 *
 * 另外记录本 App 自身的关键计数器（Surface 注入次数、player 创建次数、
 * 重载次数），用于验证 PlayerEngine 的竞速/兜底逻辑是否异常。
 *
 * release 构建默认关闭，可通过遥控器数字键 0 现场打开（见 PlayerScreen）。
 */
data class DiagSnapshot(
    val enabled: Boolean = true,

    // ===== 设备 =====
    val device: String = "",
    val sdkInt: Int = 0,
    val abi: String = "",
    val legacy: Boolean = false,
    val rendererMode: String = "-",

    // ===== 当前频道 =====
    val channelName: String = "",
    val channelUrl: String = "",
    val usingProxy: Boolean = false,

    // ===== 解码器 =====
    val decoderName: String = "-",
    val decoderInitSession: Int = 0,
    val decoderInitCurrent: Int = 0,
    val decoderInitDelta: Int = 0,
    val lastDecoderInitCostMs: Long = 0,

    // ===== 视频格式 =====
    val resolution: String = "-",
    val frameRate: String = "-",
    val bitrate: String = "-",
    val codecs: String = "-",
    val mime: String = "-",

    // ===== 音频（定位老设备「有画面无声音」）=====
    /** 音轨状态：SELECTED / UNSUPPORTED / NOT_SELECTED / NO_AUDIO_TRACK / - */
    val audioState: String = "-",
    /** 当前音轨 mime/codecs/声道/采样率 */
    val audioFormat: String = "-",
    /** 实际启用的音频解码器名 */
    val audioDecoder: String = "-",
    /** 全部音频轨摘要（含各轨 supported/selected 标记） */
    val audioTracks: String = "-",
    /** 最近一次音频解码/AudioTrack 错误 */
    val audioError: String = "-",
    /** 音轨兜底改选记录 */
    val audioFallback: String = "-",
    /** 设备支持的音频 MediaCodec 解码器列表 */
    val audioDecoders: String = "-",
    /** AudioTrack 实际输出配置（编码/采样率/声道掩码），用于确认是否真的在出声 */
    val audioTrackCfg: String = "-",

    // ===== 播放 =====
    val state: String = "-",
    val isLoading: Boolean = false,
    val positionMs: Long = 0,
    val bufferedMs: Long = 0,

    // ===== 帧统计（DecoderCounters） =====
    val renderedFps: Double = 0.0,
    val renderedTotal: Int = 0,
    val droppedTotal: Int = 0,
    val droppedToKeyframe: Int = 0,
    val skippedTotal: Int = 0,

    // ===== 本 App 计数器 =====
    val surfaceInjectCalls: Int = 0,
    val surfaceInjectApplied: Int = 0,
    val playerCreateCount: Int = 0,
    val playerReleaseCount: Int = 0,
    val raceStartCount: Int = 0,
    val reloadCount: Int = 0,
    val lastReloadReason: String = "-",
    val errorCount: Int = 0,
    val lastError: String = "-",

    // ===== 日志 =====
    val logLines: Int = 0,

    // ===== 连接真实信息（真实下载速率 / 累计字节 / 实际请求 URL） =====
    val realSpeedKbps: Int = 0,
    val totalMB: Float = 0f,
    val loadUrl: String = ""
)

object DebugDiagnostics {

    private const val TAG = "Diag"
    private const val MAX_LOG_LINES = 3000

    /** HUD / HTTP 服务总开关。release 默认关，可用遥控器数字键 0 打开 */
    @Volatile
    var enabled: Boolean = BuildConfig.DEBUG

    @Volatile
    private var appContext: Context? = null

    /** 当前主 player（由 PlayerEngine 注入） */
    @Volatile
    private var player: ExoPlayer? = null

    @Volatile
    private var legacy: Boolean = false

    @Volatile
    private var rendererMode: String = "-"

    @Volatile
    private var channelName: String = ""

    @Volatile
    private var channelUrl: String = ""

    @Volatile
    private var usingProxy: Boolean = false

    @Volatile private var realSpeedKbps: Int = 0
    @Volatile private var totalMB: Float = 0f
    @Volatile private var loadUrl: String = ""

    /** 由 PlayerEngine 真实速率采样协程写入：真实下载速率(Kbps) / 已下载 MB / 实际请求 URL */
    fun setConnInfo(realKbps: Int, totalMB: Float, url: String) {
        this.realSpeedKbps = realKbps
        this.totalMB = totalMB
        this.loadUrl = url
    }

    // ===== 音频诊断（定位 Android 5 等老设备「有画面无声音」）=====
    @Volatile private var audioState = "-"
    @Volatile private var audioFormat = "-"
    @Volatile private var audioDecoder = "-"
    @Volatile private var audioTracks = "-"
    @Volatile private var audioError = "-"
    @Volatile private var audioFallback = "-"
    @Volatile private var audioDecoders = "-"
    @Volatile private var audioTrackCfg = "-"

    /** 设备实际提供的音频 MediaCodec 解码器：两台设备对比即可看出极米缺哪一种 codec */
    private fun logAudioDecoders() {
        val list = try {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .filter { !it.isEncoder }
                .flatMap { info ->
                    info.supportedTypes
                        .filter { it.startsWith("audio/", ignoreCase = true) }
                        .map { "${info.name}($it)" }
                }
        } catch (t: Throwable) {
            listOf("MediaCodecList unavailable: ${t.message}")
        }
        audioDecoders = list.joinToString(" ")
        log(TAG, "audio-decoders sdk=${Build.VERSION.SDK_INT} $audioDecoders")
    }

    /** 由 PlayerEngine.onTracksChanged 调用：记录每条音轨的 codec / 声道 / 是否被设备支持 */
    fun onAudioTracks(tracks: Tracks) {
        var total = 0
        var supported = 0
        var selectedFormat: Format? = null
        val sb = StringBuilder()
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                total++
                val f = group.getTrackFormat(i)
                val sup = group.isTrackSupported(i)
                val sel = group.isTrackSelected(i)
                if (sup) supported++
                if (sel) selectedFormat = f
                sb.append("[${f.sampleMimeType ?: "?"}/${f.codecs ?: "?"} " +
                    "ch=${f.channelCount} hz=${f.sampleRate} sup=$sup sel=$sel]")
            }
        }
        audioTracks = sb.toString().ifEmpty { "-" }
        audioState = when {
            total == 0 -> "NO_AUDIO_TRACK"
            // 选中了某条轨，但该轨 isTrackSupported=false：最常见于老设备缺该格式解码器
            // （如 Android 5 无 E-AC3），即使 DefaultTrackSelector 已回退到 AAC 备用轨，
            // 若 AAC 轨也因 CodecPrivate 不完整被判 unsupported，就会落到这里 → 视频正常但静音
            selectedFormat != null && supported == 0 -> "SELECTED_UNSUPPORTED"
            selectedFormat != null -> "SELECTED"
            supported > 0 -> "NOT_SELECTED"
            else -> "UNSUPPORTED"
        }
        selectedFormat?.let {
            audioFormat = "${it.sampleMimeType ?: "?"}/${it.codecs ?: "?"} " +
                "ch=${it.channelCount} hz=${it.sampleRate}"
        }
        log(TAG, "audio-tracks state=$audioState $audioTracks")
        if (audioState == "UNSUPPORTED" || audioState == "SELECTED_UNSUPPORTED") {
            log(TAG, "audio $audioState: 选中/可用音轨不被本设备 MediaCodec 支持 → 视频正常但静音" +
                "（需引入软解扩展或换带 AAC-LC 2.0 音轨的片源）")
        }
    }

    fun onAudioTrackInitialized(config: AudioSink.AudioTrackConfig) {
        audioTrackCfg = "${encName(config.encoding)} ${config.sampleRate}Hz " +
            "chCfg=0x${config.channelConfig.toString(16)} buf=${config.bufferSize} offload=${config.offload}"
        log(TAG, "audio-track-init $audioTrackCfg")
    }

    private fun encName(enc: Int): String = when (enc) {
        AudioFormat.ENCODING_PCM_16BIT -> "PCM16"
        AudioFormat.ENCODING_PCM_FLOAT -> "FLOAT"
        AudioFormat.ENCODING_AC3 -> "AC3"
        AudioFormat.ENCODING_E_AC3 -> "EAC3"
        AudioFormat.ENCODING_DTS -> "DTS"
        AudioFormat.ENCODING_DTS_HD -> "DTS_HD"
        else -> "enc$enc"
    }

    fun onAudioFallback(desc: String) {
        audioFallback = desc
        log(TAG, "audio-fallback $desc")
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        log(TAG, "init enabled=$enabled device=${Build.MANUFACTURER}/${Build.MODEL} sdk=${Build.VERSION.SDK_INT}")
        logAudioDecoders()
    }

    fun setEnv(isLegacy: Boolean, renderer: String) {
        legacy = isLegacy
        rendererMode = renderer
    }

    fun setChannel(name: String, url: String, proxy: Boolean) {
        channelName = name
        channelUrl = url
        usingProxy = proxy
    }

    fun attachPlayer(exo: ExoPlayer?) {
        player = exo
    }

    // ===== 计数器 =====
    private val cSurfaceCalls = AtomicInteger()
    private val cSurfaceApplied = AtomicInteger()
    private val cPlayerCreate = AtomicInteger()
    private val cPlayerRelease = AtomicInteger()
    private val cRaceStart = AtomicInteger()
    private val cReload = AtomicInteger()
    private val cError = AtomicInteger()
    private val cDecoderInit = AtomicInteger()

    @Volatile
    private var lastError: String = "-"

    @Volatile
    private var lastReloadReason: String = "-"

    @Volatile
    private var lastDecoderName = "-"

    @Volatile
    private var lastDecoderInitCostMs = 0L

    // ===== 轮询采样状态 =====
    private var lastSampleNanos = 0L
    private var lastRendered = 0
    private var lastDecoderInitCount = 0

    @Volatile
    private var renderedFps = 0.0

    @Volatile
    private var renderedTotal = 0

    @Volatile
    private var droppedTotal = 0

    @Volatile
    private var droppedToKeyframe = 0

    @Volatile
    private var skippedTotal = 0

    @Volatile
    private var decoderInitCurrent = 0

    @Volatile
    private var decoderInitDelta = 0

    // ===== 计数器入口 =====

    /** PlayerEngine.setSharedSurface 被调用次数（含被幂等判断拦截的） */
    fun onSurfaceInjectCall() {
        cSurfaceCalls.incrementAndGet()
    }

    /** 真正执行 exo.setVideoSurface 的次数（幂等保护失效次数） */
    fun onSurfaceInjectApplied() {
        cSurfaceApplied.incrementAndGet()
    }

    fun onPlayerCreated() = cPlayerCreate.incrementAndGet()
    fun onPlayerReleased() = cPlayerRelease.incrementAndGet()
    fun onRaceStart() = cRaceStart.incrementAndGet()

    fun onReload(reason: String) {
        cReload.incrementAndGet()
        lastReloadReason = reason
        log(TAG, "reload reason=$reason")
    }

    fun onError(where: String, e: PlaybackException?) {
        cError.incrementAndGet()
        lastError = "$where ${e?.errorCodeName ?: "null"}"
        log(TAG, "error $lastError ${e?.message ?: ""}")
    }

    // ===== AnalyticsListener =====

    /** 每个 player 实例挂一个 */
    fun newAnalyticsListener(): AnalyticsListener = object : AnalyticsListener {

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            lastDecoderName = decoderName
            lastDecoderInitCostMs = initializationDurationMs
            cDecoderInit.incrementAndGet()
            log(TAG, "decoder-init name=$decoderName cost=${initializationDurationMs}ms")
        }

        // 用三参数版本：两参数版本在 media3 1.2.1 已废弃
        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?
        ) {
            log(TAG, "video-format ${format.sampleMimeType} ${format.width}x${format.height}" +
                    " fps=${format.frameRate} bps=${format.bitrate} codecs=${format.codecs}" +
                    " reuse=${decoderReuseEvaluation?.result ?: "-"}")
        }

        override fun onVideoSizeChanged(
            eventTime: AnalyticsListener.EventTime,
            videoSize: VideoSize
        ) {
            log(TAG, "video-size ${videoSize.width}x${videoSize.height}")
        }

        // ===== 音频链路诊断：老设备常因系统缺该音轨 MediaCodec 而「有画面无声音」=====

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?
        ) {
            audioFormat = "${format.sampleMimeType ?: "?"}/${format.codecs ?: "?"} " +
                "ch=${format.channelCount} hz=${format.sampleRate}"
            log(TAG, "audio-format $audioFormat")
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            audioDecoder = decoderName
            log(TAG, "audio-decoder-init name=$decoderName cost=${initializationDurationMs}ms")
        }

        override fun onAudioCodecError(
            eventTime: AnalyticsListener.EventTime,
            error: Exception
        ) {
            audioError = "codec: ${error.message}"
            log(TAG, "audio-codec-error ${error.message}")
        }

        override fun onAudioSinkError(
            eventTime: AnalyticsListener.EventTime,
            error: Exception
        ) {
            audioError = "sink: ${error.message}"
            log(TAG, "audio-sink-error ${error.message}")
        }

        override fun onAudioTrackInitialized(
            eventTime: AnalyticsListener.EventTime,
            audioTrackConfig: AudioSink.AudioTrackConfig
        ) {
            onAudioTrackInitialized(audioTrackConfig)
        }

        // 原名 onDroppedFrames 是 ExoPlayer 2 的写法，media3 已改名 onDroppedVideoFrames，
        // 且多出 elapsedMs 参数；此前没有 override 也不会报错，导致丢帧事件一直没被采集
        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long
        ) {
            log(TAG, "dropped-frames count=$droppedFrames elapsed=${elapsedMs}ms" +
                    " pos=${eventTime.currentPlaybackPositionMs}ms")
        }

        override fun onPlayerError(
            eventTime: AnalyticsListener.EventTime,
            error: PlaybackException
        ) {
            onError("player", error)
        }
    }

    // ===== 快照 =====

    fun snapshot(): DiagSnapshot {
        val p = player
        val now = System.nanoTime()

        if (p != null) {
            val c: DecoderCounters? = p.videoDecoderCounters
            if (c != null) {
                // 跨线程读取前确保计数可见（解码在播放线程，这里在主线程读）
                c.ensureUpdated()
                val curRendered = c.renderedOutputBufferCount
                val curInit = c.decoderInitCount
                renderedTotal = curRendered
                droppedTotal = c.droppedBufferCount
                droppedToKeyframe = c.droppedToKeyframeCount
                skippedTotal = c.skippedOutputBufferCount
                decoderInitCurrent = curInit

                if (lastSampleNanos == 0L) {
                    // 首次采样或刚切换 player：只建立基准，不算差值
                    lastRendered = curRendered
                    lastDecoderInitCount = curInit
                    lastSampleNanos = now
                    renderedFps = 0.0
                    decoderInitDelta = 0
                } else {
                    val dtMs = (now - lastSampleNanos) / 1_000_000.0
                    if (dtMs >= 250.0) {
                        renderedFps = (curRendered - lastRendered) * 1000.0 / dtMs
                        decoderInitDelta = curInit - lastDecoderInitCount
                        lastRendered = curRendered
                        lastDecoderInitCount = curInit
                        lastSampleNanos = now
                    }
                }
            }
        } else {
            lastSampleNanos = 0L
            lastRendered = 0
            lastDecoderInitCount = 0
            renderedFps = 0.0
            decoderInitDelta = 0
            renderedTotal = 0
            droppedTotal = 0
            droppedToKeyframe = 0
            skippedTotal = 0
            decoderInitCurrent = 0
        }

        val fmt = p?.videoFormat
        return DiagSnapshot(
            enabled = enabled,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            sdkInt = Build.VERSION.SDK_INT,
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?",
            legacy = legacy,
            rendererMode = rendererMode,
            channelName = channelName,
            channelUrl = channelUrl,
            usingProxy = usingProxy,
            decoderName = lastDecoderName,
            decoderInitSession = cDecoderInit.get(),
            decoderInitCurrent = decoderInitCurrent,
            decoderInitDelta = decoderInitDelta,
            lastDecoderInitCostMs = lastDecoderInitCostMs,
            resolution = if (fmt != null && fmt.width > 0) "${fmt.width}x${fmt.height}" else "-",
            frameRate = fmt?.takeIf { it.frameRate > 0 }
                ?.let { String.format(Locale.US, "%.2f", it.frameRate) } ?: "-",
            bitrate = fmt?.takeIf { it.bitrate > 0 }?.let { "${it.bitrate / 1000}kbps" } ?: "-",
            codecs = fmt?.codecs ?: "-",
            mime = fmt?.sampleMimeType ?: "-",
            audioState = audioState,
            audioFormat = audioFormat,
            audioDecoder = audioDecoder,
            audioTracks = audioTracks,
            audioError = audioError,
            audioFallback = audioFallback,
            audioDecoders = audioDecoders,
            audioTrackCfg = audioTrackCfg,
            state = p?.let { stateName(it.playbackState) } ?: "-",
            isLoading = p?.isLoading ?: false,
            positionMs = p?.currentPosition ?: 0,
            // 缓冲余量（可播放多久），比 bufferedPosition 绝对值更直观
            bufferedMs = (p?.bufferedPosition ?: 0) - (p?.currentPosition ?: 0),
            renderedFps = renderedFps,
            renderedTotal = renderedTotal,
            droppedTotal = droppedTotal,
            droppedToKeyframe = droppedToKeyframe,
            skippedTotal = skippedTotal,
            surfaceInjectCalls = cSurfaceCalls.get(),
            surfaceInjectApplied = cSurfaceApplied.get(),
            playerCreateCount = cPlayerCreate.get(),
            playerReleaseCount = cPlayerRelease.get(),
            raceStartCount = cRaceStart.get(),
            reloadCount = cReload.get(),
            lastReloadReason = lastReloadReason,
            errorCount = cError.get(),
            lastError = lastError,
            logLines = logSize(),
            realSpeedKbps = realSpeedKbps,
            totalMB = totalMB,
            loadUrl = loadUrl
        )
    }

    fun toJson(s: DiagSnapshot = snapshot()): String = JSONObject().apply {
        put("enabled", s.enabled)
        put("device", s.device)
        put("sdkInt", s.sdkInt)
        put("abi", s.abi)
        put("legacy", s.legacy)
        put("rendererMode", s.rendererMode)
        put("channelName", s.channelName)
        put("channelUrl", s.channelUrl)
        put("usingProxy", s.usingProxy)
        put("decoderName", s.decoderName)
        put("decoderInitSession", s.decoderInitSession)
        put("decoderInitCurrent", s.decoderInitCurrent)
        put("decoderInitDelta", s.decoderInitDelta)
        put("lastDecoderInitCostMs", s.lastDecoderInitCostMs)
        put("resolution", s.resolution)
        put("frameRate", s.frameRate)
        put("bitrate", s.bitrate)
        put("codecs", s.codecs)
        put("mime", s.mime)
        put("audioState", s.audioState)
        put("audioFormat", s.audioFormat)
        put("audioDecoder", s.audioDecoder)
        put("audioTracks", s.audioTracks)
        put("audioError", s.audioError)
        put("audioFallback", s.audioFallback)
        put("audioDecoders", s.audioDecoders)
        put("audioTrackCfg", s.audioTrackCfg)
        put("state", s.state)
        put("isLoading", s.isLoading)
        put("positionMs", s.positionMs)
        put("bufferedMs", s.bufferedMs)
        put("renderedFps", s.renderedFps)
        put("renderedTotal", s.renderedTotal)
        put("droppedTotal", s.droppedTotal)
        put("droppedToKeyframe", s.droppedToKeyframe)
        put("skippedTotal", s.skippedTotal)
        put("surfaceInjectCalls", s.surfaceInjectCalls)
        put("surfaceInjectApplied", s.surfaceInjectApplied)
        put("playerCreateCount", s.playerCreateCount)
        put("playerReleaseCount", s.playerReleaseCount)
        put("raceStartCount", s.raceStartCount)
        put("reloadCount", s.reloadCount)
        put("lastReloadReason", s.lastReloadReason)
        put("errorCount", s.errorCount)
        put("lastError", s.lastError)
    }.toString(2)

    private fun stateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "?$state"
    }

    // ===== 日志（内存环形缓冲，供诊断面板展示） =====

    private val logLock = Any()
    private val logLines = ArrayDeque<String>(512)

    private val tsFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun stamp(): String = tsFormat.format(Date())

    fun log(tag: String, msg: String) {
        val line = "${stamp()} [$tag] $msg"
        Log.d(TAG, line)
        synchronized(logLock) {
            if (logLines.size >= MAX_LOG_LINES) logLines.removeFirst()
            logLines.addLast(line)
        }
    }

    fun logSize(): Int = synchronized(logLock) { logLines.size }

    /** 导出全部内存日志（最新的在后） */
    fun exportLog(): String = synchronized(logLock) { logLines.joinToString("\n") }

    fun clearLog() {
        synchronized(logLock) { logLines.clear() }
    }

    /** 复位所有计数器（切换频道前后对比用） */
    fun resetCounters() {
        cSurfaceCalls.set(0)
        cSurfaceApplied.set(0)
        cPlayerCreate.set(0)
        cPlayerRelease.set(0)
        cRaceStart.set(0)
        cReload.set(0)
        cError.set(0)
        cDecoderInit.set(0)
        lastError = "-"
        lastReloadReason = "-"
        lastSampleNanos = 0L
        lastRendered = 0
        lastDecoderInitCount = 0
        log(TAG, "counters reset")
    }

}
