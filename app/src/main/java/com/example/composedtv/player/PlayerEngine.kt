@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.composedtv.player

import android.content.Context
import android.util.Log
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 播放列表项 */
data class PlaylistItem(
    val name: String,
    val url: String,
    val sourceId: String? = null,
    val favId: String? = null,
    val isFavorite: Boolean = false
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
 */
class PlayerEngine(private val context: Context) {

    companion object {
        private const val TAG = "PlayerEngine"
        private const val WATCHDOG_TIMEOUT_MS = 15_000L
        private const val RACE_HEDGE_MS = 1500L
        private val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(PlayerState())
    val stateFlow: kotlinx.coroutines.flow.StateFlow<PlayerState> = _state

    /** 当前绑定的主 player（供 PlayerView 使用） */
    private val _playerRef = kotlinx.coroutines.flow.MutableStateFlow<ExoPlayer?>(null)
    val playerFlow: kotlinx.coroutines.flow.StateFlow<ExoPlayer?> = _playerRef
    val player: ExoPlayer? get() = _playerRef.value

    private var playlist: List<PlaylistItem> = emptyList()
    private var currentIndex = 0
    private var consecutiveErrors = 0
    private var flvRetryDone = false

    /** 是否已渲染过第一帧（用于区分"首次缓冲"与"播放中卡顿"） */
    private var hasRenderedFirstFrame = false
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
    private var raceHedgeJob: Job? = null
    private var racePlayToken = 0
    private var watchdogJob: Job? = null

    // 信息条自动隐藏
    private var infoHideJob: Job? = null
    // transientHint 自动清理
    private var hintHideJob: Job? = null

    /** 当前正在播放的频道的 URL（用于手动重载时复用） */
    private var currentPlayingUrl: String = ""

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

    /** 播放单条（无列表上下切换） */
    fun playSingle(item: PlaylistItem) {
        playlist = listOf(item)
        currentIndex = 0
        consecutiveErrors = 0
        playCurrent()
    }

    private fun playCurrent() {
        if (playlist.isEmpty()) return
        val item = playlist[currentIndex.coerceIn(0, playlist.lastIndex)]
        resetRace()
        player?.stop()
        player?.release()
        _playerRef.value = null
        flvRetryDone = false
        currentPlayingUrl = item.url
        hasRenderedFirstFrame = false
        // 判断是否直播流：HLS (.m3u8) 和 FLV 视为直播，其余（如 .mp4/.mkv）视为点播
        isLiveStream = isFlvStream(item.url) || item.url.lowercase().let {
            it.contains(".m3u8") || it.contains(".m3u")
        }
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
        playUrl(item.url)
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
    private fun planPlay(url: String): List<String> {
        val lower = url.lowercase()
        val isHls = lower.contains(".m3u8") || lower.contains(".m3u")
        if (!isHls) return listOf("direct")
        if (url.startsWith("http://")) return listOf("proxy", "direct")
        return listOf("direct", "proxy")
    }

    private fun playUrl(url: String) {
        if (url.isBlank()) {
            handlePlayFailure()
            return
        }
        val attempts = planPlay(url)
        startRace(url, attempts)
    }

    // ===== 竞速播放 =====
    private fun createCandidatePlayer(url: String, useProxy: Boolean, winner: Boolean): ExoPlayer {
        val lower = url.lowercase()
        val isHls = lower.contains(".m3u8") || lower.contains(".m3u")
        val isFlv = isFlvStream(url)
        val finalUrl = if (useProxy && isHls) ApiClient.hlsProxyUrl(url) else url

        // 调优后的缓冲策略：
        // - minBuffer 20s：稳定播放所需的最小缓冲
        // - maxBuffer 60s：网络好时多缓冲，应对后续波动
        // - initialBuffer 1s：起播快（仅需 1s 数据即开始播放）
        // - rebuffer 2.5s：卡顿后需更多缓冲才恢复，减少二次卡顿
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 60_000, 1_000, 2_500)
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
        // - 设定上限 1080p / 10Mbps，避免超清流卡顿
        // - 允许降到任意低码率，保证流畅
        // - 不强制最低/最高，由 ExoPlayer 根据实时带宽自适应
        p.trackSelectionParameters = p.trackSelectionParameters
            .buildUpon()
            .setMaxVideoSize(1920, 1080)
            .setMaxVideoBitrate(10_000_000)
            .setMinVideoBitrate(0)
            .setForceLowestBitrate(false)
            .setForceHighestSupportedBitrate(false)
            .build()

        if (!winner) p.volume = 0f

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
        Log.d(TAG, "竞速开始: $attempts")

        val firstAttempt = attempts[0]
        val c0 = RaceCandidate(firstAttempt, null, false)
        c0.exo = createCandidatePlayer(url, firstAttempt == "proxy", winner = true)
        _playerRef.value = c0.exo
        raceCandidates = listOf(c0)
        attachCandidateListener(c0, url, myToken)
        startWatchdog()

        if (attempts.size > 1) {
            raceHedgeJob?.cancel()
            raceHedgeJob = scope.launch {
                delay(RACE_HEDGE_MS)
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

        for (c in raceCandidates) {
            if (c !== winner) {
                c.exo?.stop()
                c.exo?.release()
            }
        }

        val winExo = winner.exo
        if (winExo != null) {
            winExo.volume = 1f
            _playerRef.value = winExo
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
                    }
                    Player.STATE_READY -> {
                        updateState(isLoading = false, isPlaying = true, error = null)
                    }
                    Player.STATE_ENDED -> {
                        Log.w(TAG, "播放期 STATE_ENDED，不再自动重载: $currentPlayingUrl")
                    }
                    Player.STATE_IDLE -> {
                        // no-op
                    }
                }
            }

            override fun onRenderedFirstFrame() {
                hasRenderedFirstFrame = true
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "播放期 onPlayerError: ${error.errorCodeName} url=$currentPlayingUrl", error)
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
        // 点播内容：保存当前播放位置以便重载后恢复
        if (!isLiveStream) {
            val pos = player?.currentPosition ?: 0
            if (pos > 0) {
                pendingResumePositionMs = pos
                Log.d(TAG, "reloadCurrentChannel: 保存点播播放位置 ${pos}ms")
            }
        }
        Log.d(TAG, "reloadCurrentChannel(reason=$reason) idx=$currentIndex url=$currentPlayingUrl isLive=$isLiveStream")
        scope.launch {
            delay(300)
            playCurrent()
        }
    }

    private fun raceCandidateFailed(cand: RaceCandidate) {
        if (raceDecided || cand.failed) return
        cand.failed = true
        cand.exo?.stop()
        cand.exo?.release()
        cand.exo = null
        val alive = raceCandidates.filter { !it.failed }
        if (alive.isEmpty()) {
            raceActive = false
            raceDecided = true
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
        }
        raceCandidates = emptyList()
    }

    private fun handlePlayFailure() {
        stopWatchdog()
        if (playlist.isEmpty()) {
            updateState(error = "无法播放", isLoading = false)
            return
        }
        val curUrl = playlist.getOrNull(currentIndex)?.url ?: ""
        if (isFlvStream(curUrl) && !flvRetryDone) {
            flvRetryDone = true
            Log.d(TAG, "FLV 首次失败，原地重试: $curUrl")
            updateState(isLoading = true)
            resetRace()
            player?.stop()
            player?.release()
            _playerRef.value = null
            playUrl(curUrl)
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
                Log.d(TAG, "Watchdog: 竞速超时（${WATCHDOG_TIMEOUT_MS}ms 无候选胜出），视为竞速失败，跳下一台: $currentPlayingUrl")
                stopWatchdog()
                raceActive = false
                raceDecided = true
                handlePlayFailure()
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

    /** 显示信息条（常驻，不自动隐藏） */
    fun showInfo() {
        infoHideJob?.cancel()
        updateState(showInfo = true)
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
        infoHideJob?.cancel()
        hintHideJob?.cancel()
        player?.stop()
        player?.release()
        _playerRef.value = null
        playlist = emptyList()
    }
}
