@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.composedtv.debug

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.example.composedtv.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
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
    val serverRunning: Boolean = false,
    val serverUrl: String = "",

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
    val logPath: String = "-"
)

object DebugDiagnostics {

    private const val TAG = "Diag"
    private const val MAX_LOG_LINES = 3000
    private const val LOG_FILE_NAME = "iptv-debug.log"

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

    fun init(context: Context) {
        appContext = context.applicationContext
        log(TAG, "init enabled=$enabled device=${Build.MANUFACTURER}/${Build.MODEL} sdk=${Build.VERSION.SDK_INT}")
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

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format
        ) {
            log(TAG, "video-format ${format.sampleMimeType} ${format.width}x${format.height}" +
                    " fps=${format.frameRate} bps=${format.bitrate} codecs=${format.codecs}")
        }

        override fun onVideoSizeChanged(
            eventTime: AnalyticsListener.EventTime,
            videoSize: VideoSize
        ) {
            log(TAG, "video-size ${videoSize.width}x${videoSize.height}")
        }

        fun onDroppedFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int
        ) {
            log(TAG, "dropped-frames count=$droppedFrames")
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
            serverRunning = DebugLogServer.isRunning(),
            serverUrl = DebugLogServer.url().orEmpty(),
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
            logPath = logPath() ?: "-"
        )
    }

    fun toJson(s: DiagSnapshot = snapshot()): String = JSONObject().apply {
        put("enabled", s.enabled)
        put("serverUrl", s.serverUrl)
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
        put("logPath", s.logPath)
    }.toString(2)

    private fun stateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "?$state"
    }

    // ===== 日志 =====

    private val logLock = Any()
    private val logLines = ArrayDeque<String>(512)
    private val writer = Executors.newSingleThreadExecutor()

    @Volatile
    private var fileSink: File? = null

    private val tsFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun stamp(): String = tsFormat.format(Date())

    /** 文件日志落盘（HTTP 服务启动时自动开启） */
    fun startFileLogging() {
        val dir = appContext?.getExternalFilesDir(null)
        if (dir == null) {
            Log.w(TAG, "startFileLogging: 无法获取外部目录")
            return
        }
        if (!dir.exists()) dir.mkdirs()
        fileSink = File(dir, LOG_FILE_NAME)
        log(TAG, "file logging -> ${fileSink?.absolutePath}")
    }

    fun stopFileLogging() {
        fileSink = null
    }

    fun logPath(): String? = fileSink?.absolutePath

    fun log(tag: String, msg: String) {
        val line = "${stamp()} [$tag] $msg"
        Log.d(TAG, line)
        synchronized(logLock) {
            if (logLines.size >= MAX_LOG_LINES) logLines.removeFirst()
            logLines.addLast(line)
        }
        val f = fileSink
        if (f != null) {
            writer.execute {
                runCatching {
                    FileWriter(f, true).use { it.appendLine(line) }
                }.onFailure { Log.w(TAG, "写日志文件失败: ${it.message}") }
            }
        }
    }

    fun logSize(): Int = synchronized(logLock) { logLines.size }

    /** 导出全部内存日志（最新的在后） */
    fun exportLog(): String = synchronized(logLock) { logLines.joinToString("\n") }

    fun clearLog() {
        synchronized(logLock) { logLines.clear() }
        val f = fileSink
        if (f != null) {
            writer.execute { runCatching { FileWriter(f, false).use { it.write("") } } }
        }
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

    /** 本机 IPv4（HTTP 服务地址展示用，不需要额外权限） */
    fun localIpAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.flatMap { it.inetAddresses.toList() }
            ?.firstOrNull { !it.isLoopbackAddress && it.address.size == 4 }
            ?.hostAddress
    }.getOrNull()
}
