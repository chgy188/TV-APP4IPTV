package com.example.composedtv.viewmodel

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.composedtv.BuildConfig
import com.example.composedtv.data.remote.ApiChannel
import com.example.composedtv.data.remote.ApiClient
import com.example.composedtv.data.remote.ApiFavorite
import com.example.composedtv.data.remote.ApiSource
import com.example.composedtv.data.remote.StoredUser
import com.example.composedtv.player.PlaylistItem
import com.example.composedtv.player.PlayerEngine
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 侧边栏三排目录数据 */
data class SidePanelData(
    val sources: List<ApiSource> = emptyList(),
    val categories: List<CategoryEntry> = emptyList(),
    val channels: List<ChannelEntry> = emptyList(),
    val selectedSourceIndex: Int = 0,
    val selectedCategoryIndex: Int = 0,
    val selectedChannelIndex: Int = 0,
    /** 当前 categories 实际所属源的 id；用于判断缓存是否正确归属，避免换源后分类不刷新 */
    val loadedSourceId: String? = null,
    /** 搜索模式：第三列显示搜索框 + 实时筛选结果 */
    val isSearchMode: Boolean = false,
    /** 当前搜索查询字符串 */
    val searchQuery: String = "",
    /** 搜索结果（跨当前源所有分类） */
    val searchResults: List<ChannelEntry> = emptyList(),
    /** 分类列表是否正在加载（显示 spinner） */
    val isLoadingCategories: Boolean = false,
    /** 频道列表是否正在加载（显示 spinner） */
    val isLoadingChannels: Boolean = false,
    /** 收藏数据已变更（添加/取消）→ 缓存的「收藏」频道列表已过期，需重新拉取 */
    val favoritesDirty: Boolean = false
)

/** 分类条目：收藏/搜索是特殊的虚拟分类 */
data class CategoryEntry(
    val name: String,
    val isFavorites: Boolean = false,
    val isSearch: Boolean = false
)

/** 频道条目 */
data class ChannelEntry(
    val id: String,
    val name: String,
    val url: String,
    val logo: String,
    val sourceId: String?,
    val favId: String?,
    val isFavorite: Boolean,
    val category: String,
    /** 频道归属国（ISO 2 字母代码，来自 iptv-org） */
    val countryAttr: String = "",
    /** 频道语言（ISO 639-3 代码数组，来自 iptv-org） */
    val langs: List<String> = emptyList(),
    /** 源站要求的自定义请求头：User-Agent（防盗链常用） */
    val ua: String = "",
    /** 源站要求的自定义请求头：Referer（服务端字段名 rf，防盗链常用） */
    val rf: String = ""
)

/** 视频渲染方式。
 * - AUTO：新设备用 SurfaceView（性能最好），老设备 Android6 用 TextureView（规避 SurfaceView 黑屏）
 * - SURFACE：强制 SurfaceView（性能最佳，但部分老旧电视驱动有 Z-order / Surface 重建黑屏问题）
 * - TEXTURE：强制 TextureView（兼容性好，与 Compose 叠加层 Z 序一致，修复"有声音没画面"，代价是略耗 CPU）
 */
enum class RendererMode(val value: Int) {
    AUTO(0), SURFACE(1), TEXTURE(2);

    fun useTextureView(): Boolean = when (this) {
        TEXTURE -> true
        SURFACE -> false
        AUTO -> android.os.Build.VERSION.SDK_INT <= 23 // Android 6.0.1 及以下老设备用 TextureView
    }

    companion object {
        fun fromValue(v: Int) = entries.firstOrNull { it.value == v } ?: AUTO
    }
}

/**
 * 首播频道模式（仅登录用户可在设置抽屉切换；游客固定为「上次退出频道」）。
 * - FAVORITE_FIRST：收藏列表第一个（登录用户默认，保持原有行为）
 * - LAST_PLAYED：上次退出时正在播放的频道
 */
enum class StartChannelMode(val value: Int) {
    FAVORITE_FIRST(0),
    LAST_PLAYED(1);

    companion object {
        fun fromValue(v: Int) = entries.firstOrNull { it.value == v } ?: FAVORITE_FIRST
    }
}

/** 播放参数设置（由 MENU 设置抽屉调整，持久化保存） */
data class PlaybackSettings(
    /** 直连胜出后持续缓冲判定 stuck 的时长（毫秒） */
    val stuckTimeoutMs: Long = 8_000L,
    /** 直连候选的起播超时（毫秒）：超过该时长仍未 READY 则切换下一个候选。
     *  默认 6s：既能覆盖正常起播，又不会让"慢但不报错"的源拖太久；
     *  网络差 / 源响应慢时可调到 10s，避免被误判为不可播 */
    val directTimeoutMs: Long = 6_000L,
    /** 代理候选的起播超时（毫秒）：代理路径多一跳、握手更慢，需比直连更长容忍。
     *  超过该时长仍未 READY 则切换下一个候选；网络差 / 代理慢时可调大，避免被误判为不可播 */
    val proxyTimeoutMs: Long = 10_000L,
    /** 视频渲染方式（AUTO / SURFACE / TEXTURE） */
    val rendererMode: RendererMode = RendererMode.AUTO,
    /** 首播频道模式（登录用户可调；游客忽略该设置，始终续播上次退出时的频道） */
    val startChannelMode: StartChannelMode = StartChannelMode.FAVORITE_FIRST,
    /** 画面诊断 HUD 开关（无 ADB 环境调试用；持久化保存，重启后保持上次选择） */
    val diagHudEnabled: Boolean = false
) {
    companion object {
        private const val PREF_NAME = "playback_settings"
        private const val KEY_STUCK = "stuck_timeout_ms"
        private const val KEY_DIRECT_TIMEOUT = "direct_timeout_ms"
        private const val KEY_PROXY_TIMEOUT = "proxy_timeout_ms"
        private const val KEY_RENDERER = "renderer_mode"
        private const val KEY_START_CHANNEL = "start_channel_mode"
        private const val KEY_DIAG_HUD = "diag_hud_enabled"

        fun load(context: Context): PlaybackSettings {
            val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return PlaybackSettings(
                stuckTimeoutMs = sp.getLong(KEY_STUCK, 8_000L),
                directTimeoutMs = sp.getLong(KEY_DIRECT_TIMEOUT, 6_000L),
                proxyTimeoutMs = sp.getLong(KEY_PROXY_TIMEOUT, 10_000L),
                rendererMode = RendererMode.fromValue(sp.getInt(KEY_RENDERER, RendererMode.AUTO.value)),
                startChannelMode = StartChannelMode.fromValue(
                    sp.getInt(KEY_START_CHANNEL, StartChannelMode.FAVORITE_FIRST.value)
                ),
                // 默认值随构建类型：debug 包默认开（便于调试），release 包默认关（对普通用户干净）。
                // 一旦用户在设置里切换过，就以持久化的值为准。
                diagHudEnabled = sp.getBoolean(KEY_DIAG_HUD, BuildConfig.DEBUG)
            )
        }

        fun save(context: Context, s: PlaybackSettings) {
            val sp: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            sp.edit()
                .putLong(KEY_STUCK, s.stuckTimeoutMs)
                .putLong(KEY_DIRECT_TIMEOUT, s.directTimeoutMs)
                .putLong(KEY_PROXY_TIMEOUT, s.proxyTimeoutMs)
                .putInt(KEY_RENDERER, s.rendererMode.value)
                .putInt(KEY_START_CHANNEL, s.startChannelMode.value)
                .putBoolean(KEY_DIAG_HUD, s.diagHudEnabled)
                .apply()
        }
    }
}

/**
 * 上次退出时正在播放的频道（首播续播用）。
 *
 * 除了频道本身，还记录其「播放列表来源」与索引，启动时据此重建同一份列表，
 * 使续播后 ↑↓ 切台依然可用（而不是只剩孤零零一个频道）。
 */
data class LastPlayedChannel(
    val name: String,
    val url: String,
    val sourceId: String? = null,
    val favId: String? = null,
    val isFavorite: Boolean = false,
    val country: String = "",
    val ua: String = "",
    val rf: String = "",
    /** 播放列表来源类型：[ORIGIN_FAVORITES] / [ORIGIN_CATEGORY] / [ORIGIN_SINGLE] */
    val originKind: String = ORIGIN_SINGLE,
    /** 来源为分类时的源 id */
    val originSourceId: String? = null,
    /** 来源为分类时的分类名 */
    val originCategory: String? = null,
    /** 在来源列表中的索引 */
    val originIndex: Int = 0
) {
    fun toPlaylistItem() = PlaylistItem(
        name = name,
        url = url,
        sourceId = sourceId,
        favId = favId,
        isFavorite = isFavorite,
        country = country,
        ua = ua,
        rf = rf
    )

    companion object {
        /** 播放列表来自收藏 */
        const val ORIGIN_FAVORITES = "favorites"
        /** 播放列表来自某个源的某个分类 */
        const val ORIGIN_CATEGORY = "category"
        /** 播放列表只有一个频道（游客默认频道 / 搜索结果等），重建时退化为单条 */
        const val ORIGIN_SINGLE = "single"

        private const val PREF_NAME = "last_played_channel"
        private const val K_NAME = "name"
        private const val K_URL = "url"
        private const val K_SOURCE_ID = "source_id"
        private const val K_FAV_ID = "fav_id"
        private const val K_IS_FAV = "is_fav"
        private const val K_COUNTRY = "country"
        private const val K_UA = "ua"
        private const val K_RF = "rf"
        private const val K_ORIGIN_KIND = "origin_kind"
        private const val K_ORIGIN_SOURCE = "origin_source_id"
        private const val K_ORIGIN_CAT = "origin_category"
        private const val K_ORIGIN_IDX = "origin_index"

        fun load(context: Context): LastPlayedChannel? {
            val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val url = sp.getString(K_URL, null)?.takeIf { it.isNotBlank() } ?: return null
            return LastPlayedChannel(
                name = sp.getString(K_NAME, null) ?: "",
                url = url,
                sourceId = sp.getString(K_SOURCE_ID, null),
                favId = sp.getString(K_FAV_ID, null),
                isFavorite = sp.getBoolean(K_IS_FAV, false),
                country = sp.getString(K_COUNTRY, null) ?: "",
                ua = sp.getString(K_UA, null) ?: "",
                rf = sp.getString(K_RF, null) ?: "",
                originKind = sp.getString(K_ORIGIN_KIND, null) ?: ORIGIN_SINGLE,
                originSourceId = sp.getString(K_ORIGIN_SOURCE, null),
                originCategory = sp.getString(K_ORIGIN_CAT, null),
                originIndex = sp.getInt(K_ORIGIN_IDX, 0)
            )
        }

        fun save(context: Context, c: LastPlayedChannel) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
                .putString(K_NAME, c.name)
                .putString(K_URL, c.url)
                .putString(K_SOURCE_ID, c.sourceId)
                .putString(K_FAV_ID, c.favId)
                .putBoolean(K_IS_FAV, c.isFavorite)
                .putString(K_COUNTRY, c.country)
                .putString(K_UA, c.ua)
                .putString(K_RF, c.rf)
                .putString(K_ORIGIN_KIND, c.originKind)
                .putString(K_ORIGIN_SOURCE, c.originSourceId)
                .putString(K_ORIGIN_CAT, c.originCategory)
                .putInt(K_ORIGIN_IDX, c.originIndex)
                .apply()
        }
    }
}

/** 设置抽屉可选值集合（供 UI 渲染单选列表） */
object PlaybackSettingOptions {
    val stuckOptions = listOf(5_000L to "5秒", 8_000L to "8秒(默认)", 12_000L to "12秒")
    val directTimeoutOptions = listOf(
        6_000L to "6秒(默认)",
        10_000L to "10秒(网络差/源慢)"
    )
    val proxyTimeoutOptions = listOf(
        7_000L to "7秒(网络好)",
        10_000L to "10秒(默认)",
        15_000L to "15秒(网络差)"
    )
    val rendererOptions = listOf(
        RendererMode.AUTO to "自动(默认)",
        RendererMode.SURFACE to "SurfaceView(性能优)",
        RendererMode.TEXTURE to "TextureView(兼容)"
    )
    val startChannelOptions = listOf(
        StartChannelMode.FAVORITE_FIRST to "收藏第一个(默认)",
        StartChannelMode.LAST_PLAYED to "上次退出频道"
    )
}

/** UI 状态 */
data class PlayerUiState(
    val isLoading: Boolean = false,
    val isGuest: Boolean = false,
    val sidePanelVisible: Boolean = false,
    val sidePanel: SidePanelData = SidePanelData(),
    val initialChannelLoaded: Boolean = false,
    /** 初始播放列表中的起始索引（续播上次退出频道时不为 0） */
    val initialPlayIndex: Int = 0,
    val errorMessage: String? = null,
    val storedUsers: List<StoredUser> = emptyList(),
    /** 上次成功登录的用户名（用于登录界面预填，null 表示无记录） */
    val lastLoginUsername: String? = null,
    /** 设置抽屉是否可见（MENU 键切换） */
    val settingsVisible: Boolean = false,
    /** 播放参数设置（由 MENU 设置抽屉调整，持久化保存） */
    val playbackSettings: PlaybackSettings = PlaybackSettings()
)

class PlayerViewModel(private val app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "PlayerViewModel"
        /** 搜索结果最大条数：TV 端不宜一次显示过多，足够覆盖常用场景 */
        private const val SEARCH_MAX_RESULTS = 200
    }

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    init {
        // 启动即读取上次登录用户名（供登录界面预填）
        _uiState.value = _uiState.value.copy(
            lastLoginUsername = ApiClient.getLastLoginUsername(),
            storedUsers = ApiClient.getStoredUsers(),
            playbackSettings = PlaybackSettings.load(app)
        )
    }

    /** 播放列表来源：用于把「上次退出时的频道」连同它的列表一起还原（续播后 ↑↓ 仍可切台） */
    private data class PlaylistOrigin(
        val kind: String = LastPlayedChannel.ORIGIN_SINGLE,
        val sourceId: String? = null,
        val category: String? = null
    )

    /** 播放列表管理器（由 PlayerScreen 持有 PlayerEngine，VM 负责数据供给） */
    private var currentPlaylist: List<PlaylistItem> = emptyList()

    /** 当前播放列表的来源（随 currentPlaylist 一起更新） */
    private var currentPlaylistOrigin = PlaylistOrigin()

    /** 当前播放频道的索引（由 PlayerScreen 同步更新，用于 Activity 层兜底切侧边栏） */
    private var currentPlayIndex: Int = 0

    /** 正在等待定位到侧边栏的「当前播放频道」（显示侧边栏时临时持有，定位完成后清空） */
    private var pendingCurrentChannel: PlaylistItem? = null

    /** 初始化：根据是否游客加载初始频道，并预热节目栏数据 */
    fun initialize(isGuest: Boolean) {
        _uiState.value = _uiState.value.copy(isGuest = isGuest, storedUsers = ApiClient.getStoredUsers())
        if (isGuest) {
            // 游客：固定续播上次退出时的频道；首次使用（无记录）才用默认起始频道
            loadGuestStartChannel()
        } else if (_uiState.value.playbackSettings.startChannelMode == StartChannelMode.LAST_PLAYED) {
            loadLastPlayedChannel()
        } else {
            loadFirstFavoriteChannel()
        }
        // 节目栏数据预热：启动即拉（源→分类→频道），首次打开节目栏无需等待
        preloadSidePanelAfterStart()
    }

    /**
     * 启动时预加载节目栏数据。
     *
     * 时机：等初始频道起播完成后再拉，避免与起播争抢带宽——老设备（如极米 H1）
     * 网络与 CPU 较弱，同时并发多个请求会拖慢起播。
     * 效果：用户按确定键打开节目栏时数据已就绪，不再出现加载 spinner。
     */
    private fun preloadSidePanelAfterStart() {
        viewModelScope.launch {
            // 等待初始频道就绪（最多等 15 秒；超时也照常预加载，保证节目栏最终有数据）
            runCatching {
                withTimeoutOrNull(15_000L) {
                    while (!_uiState.value.initialChannelLoaded) delay(200)
                }
            }
            loadSidePanelSources()
        }
    }

    // ===== 初始播放 =====

    /**
     * 游客首播：优先续播上次退出时的频道；首次使用（无记录）走后端默认起始频道。
     *
     * 注意「没有收藏 → 退化为游客起始频道」的登录分支也会走到这里（loadFirstFavoriteChannel），
     * 那种情况下若存在续播记录，同样优先续播，体验更连贯。
     */
    private fun loadGuestStartChannel() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            try {
                val last = LastPlayedChannel.load(app)
                if (last != null) {
                    val (playlist, idx) = buildResumePlaylist(last)
                    if (playlist.isNotEmpty()) {
                        currentPlaylist = playlist
                        currentPlaylistOrigin = PlaylistOrigin(
                            kind = last.originKind,
                            sourceId = last.originSourceId,
                            category = last.originCategory
                        )
                        currentPlayIndex = idx
                        Log.d(TAG, "续播上次退出频道: ${last.name} idx=$idx size=${playlist.size}")
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            initialChannelLoaded = true,
                            initialPlayIndex = idx,
                            errorMessage = null
                        )
                        return@launch
                    }
                }
                // 无续播记录（首次使用）→ 后端默认起始频道
                val guestChannel = ApiClient.getGuestStart()
                if (guestChannel != null) {
                    val playlist = listOf(
                        PlaylistItem(
                            name = guestChannel.name,
                            url = guestChannel.url,
                            sourceId = guestChannel.id,
                            country = "",
                            ua = guestChannel.ua,
                            rf = guestChannel.rf
                        )
                    )
                    currentPlaylist = playlist
                    currentPlaylistOrigin = PlaylistOrigin(LastPlayedChannel.ORIGIN_SINGLE, null, null)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        initialChannelLoaded = true,
                        initialPlayIndex = 0,
                        errorMessage = null
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = "无法获取游客频道，请检查网络"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadGuestStartChannel failed", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "连接失败：${e.message}"
                )
            }
        }
    }

    /**
     * 登录用户选择「上次退出频道」时的首播：有记录则续播，
     * 没有记录（首次 / 数据被清）则退回「收藏第一个」。
     */
    private fun loadLastPlayedChannel() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val last = runCatching { LastPlayedChannel.load(app) }.getOrNull()
            if (last == null) {
                Log.d(TAG, "无续播记录，退回收藏第一个")
                loadFirstFavoriteChannel()
                return@launch
            }
            try {
                val (playlist, idx) = buildResumePlaylist(last)
                if (playlist.isEmpty()) {
                    loadFirstFavoriteChannel()
                    return@launch
                }
                currentPlaylist = playlist
                currentPlaylistOrigin = PlaylistOrigin(
                    kind = last.originKind,
                    sourceId = last.originSourceId,
                    category = last.originCategory
                )
                currentPlayIndex = idx
                Log.d(TAG, "续播上次退出频道: ${last.name} idx=$idx size=${playlist.size}")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    initialChannelLoaded = true,
                    initialPlayIndex = idx,
                    errorMessage = null
                )
            } catch (e: Exception) {
                Log.e(TAG, "loadLastPlayedChannel failed", e)
                loadFirstFavoriteChannel()
            }
        }
    }

    /**
     * 按记录的来源重建续播用的播放列表，返回「列表 + 起始索引」。
     *
     * 重建失败（源/分类已不存在、网络异常）或列表里找不到该频道时，
     * 退化为只含该频道的单条列表，保证「至少把上次的频道播出来」。
     */
    private suspend fun buildResumePlaylist(last: LastPlayedChannel): Pair<List<PlaylistItem>, Int> {
        val rebuilt: List<PlaylistItem>? = when (last.originKind) {
            LastPlayedChannel.ORIGIN_FAVORITES -> runCatching {
                if (!ApiClient.isLoggedIn) {
                    emptyList<PlaylistItem>()
                } else {
                    ApiClient.getFavorites().map { fav ->
                        PlaylistItem(
                            name = fav.name,
                            url = fav.url,
                            sourceId = fav.sourceId,
                            favId = fav.id,
                            isFavorite = true,
                            ua = fav.ua,
                            rf = fav.rf,
                            country = ""
                        )
                    }
                }
            }.getOrNull()

            LastPlayedChannel.ORIGIN_CATEGORY -> runCatching {
                val sid = last.originSourceId
                val cat = last.originCategory
                if (sid == null || cat == null) {
                    emptyList<PlaylistItem>()
                } else {
                    ApiClient.getChannels(sid)
                        .filter { (it.group.ifEmpty { "未分类" }) == cat }
                        .map { ch ->
                            PlaylistItem(
                                name = ch.name,
                                url = ch.url,
                                sourceId = sid,
                                favId = null,
                                isFavorite = false,
                                country = ch.countryAttr,
                                ua = ch.ua,
                                rf = ch.rf
                            )
                        }
                }
            }.getOrNull()

            else -> null
        }

        if (rebuilt.isNullOrEmpty()) {
            return listOf(last.toPlaylistItem()) to 0
        }
        val idx = rebuilt.indexOfFirst { it.url == last.url }
        return if (idx >= 0) {
            rebuilt to idx
        } else {
            // 列表已变（频道被移除等）：把上次的频道放在首位，其余保留，保证切台仍可用
            (listOf(last.toPlaylistItem()) + rebuilt) to 0
        }
    }

    private fun loadFirstFavoriteChannel() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            try {
                val favorites = ApiClient.getFavorites()
                if (favorites.isNotEmpty()) {
                    // 播放列表 = 所有收藏
                    val playlist = favorites.map { fav ->
                        PlaylistItem(
                            name = fav.name,
                            url = fav.url,
                            sourceId = fav.sourceId,
                            favId = fav.id,
                            isFavorite = true,
                            ua = fav.ua,
                            rf = fav.rf,
                            country = ""
                        )
                    }
                    currentPlaylist = playlist
                    currentPlaylistOrigin = PlaylistOrigin(LastPlayedChannel.ORIGIN_FAVORITES)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        initialChannelLoaded = true,
                        initialPlayIndex = 0,
                        errorMessage = null
                    )
                } else {
                    // 没有收藏 → 退化为游客起始频道（其中有续播记录则优先续播）
                    Log.w(TAG, "No favorites, falling back to guest start")
                    loadGuestStartChannel()
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadFirstFavoriteChannel failed", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "加载收藏失败：${e.message}"
                )
            }
        }
    }

    /** 供 PlayerScreen 获取初始播放列表 */
    fun getInitialPlaylist(): List<PlaylistItem> = currentPlaylist

    /**
     * 供 PlayerScreen 同步当前播放索引（用于 Activity 层兜底的确定键弹出侧边栏）。
     * 同步的同时把「当前播放频道 + 其列表来源」写入持久化，作为下次启动的续播依据。
     */
    fun syncCurrentPlayIndex(index: Int) {
        currentPlayIndex = index
        persistLastPlayed(index)
    }

    /** 记录上次播放的频道（切台 / 起播即写，退出时无需额外处理） */
    private fun persistLastPlayed(index: Int) {
        val item = currentPlaylist.getOrNull(index) ?: return
        if (item.url.isBlank()) return
        runCatching {
            LastPlayedChannel.save(
                app,
                LastPlayedChannel(
                    name = item.name,
                    url = item.url,
                    sourceId = item.sourceId,
                    favId = item.favId,
                    isFavorite = item.isFavorite,
                    country = item.country,
                    ua = item.ua,
                    rf = item.rf,
                    originKind = currentPlaylistOrigin.kind,
                    originSourceId = currentPlaylistOrigin.sourceId,
                    originCategory = currentPlaylistOrigin.category,
                    originIndex = index
                )
            )
        }.onFailure { Log.w(TAG, "保存续播记录失败: ${it.message}") }
    }

    /** Activity 层兜底调用：切换侧边栏（无需传参，内部通过 currentPlaylist + currentPlayIndex 定位） */
    fun toggleSidePanelFromActivity() {
        val current = currentPlaylist.getOrNull(currentPlayIndex)
        toggleSidePanel(current)
    }

    /** 收藏信号：Activity/Compose 按键触发，PlayerScreen 收集后调用 engine 执行 */
    private val _toggleFavoriteSignal = kotlinx.coroutines.channels.Channel<Unit>(
        kotlinx.coroutines.channels.Channel.CONFLATED
    )
    val toggleFavoriteSignal: kotlinx.coroutines.flow.Flow<Unit> = _toggleFavoriteSignal.receiveAsFlow()

    /** 重载信号：Activity/Compose 按键触发，PlayerScreen 收集后调用 engine 执行 */
    private val _manualReloadSignal = kotlinx.coroutines.channels.Channel<Unit>(
        kotlinx.coroutines.channels.Channel.CONFLATED
    )
    val manualReloadSignal: kotlinx.coroutines.flow.Flow<Unit> = _manualReloadSignal.receiveAsFlow()

    /** 发送收藏信号（Activity dispatchKeyEvent 调用，不依赖 engine 引用） */
    fun sendToggleFavorite() {
        Log.d(TAG, "发送收藏信号")
        viewModelScope.launch { _toggleFavoriteSignal.send(Unit) }
    }

    /** 发送重载信号（Activity dispatchKeyEvent 调用，不依赖 engine 引用） */
    fun sendManualReload() {
        Log.d(TAG, "发送重载信号")
        viewModelScope.launch { _manualReloadSignal.send(Unit) }
    }

    // ===== 侧边栏数据加载 =====

    /** 节目栏源列表加载中标记：防止「启动预热」与「用户手动打开节目栏」重复触发并发加载 */
    @Volatile
    private var sourcesLoading = false

    /** 打开侧边栏时加载源列表（启动预热也走这里） */
    fun loadSidePanelSources() {
        // 已有加载在途则跳过，避免并发请求与状态互相覆盖
        if (sourcesLoading) return
        sourcesLoading = true
        viewModelScope.launch {
            try {
                // 若已有源列表（通常来自当天缓存/内存），直接复用，不显示 spinner
                val existing = _uiState.value.sidePanel.sources
                val sources = if (existing.isNotEmpty()) {
                    existing
                } else {
                    // 首次/无数据：先显示加载态
                    _uiState.value = _uiState.value.copy(
                        sidePanel = _uiState.value.sidePanel.copy(
                            isLoadingCategories = true,
                            isLoadingChannels = true
                        )
                    )
                    ApiClient.getAllSources()
                }
                if (sources.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        sidePanel = _uiState.value.sidePanel.copy(
                            sources = emptyList(),
                            isLoadingCategories = false,
                            isLoadingChannels = false
                        )
                    )
                    return@launch
                }
                val pending = pendingCurrentChannel
                val prefSourceIdx = if (pending != null) {
                    sources.indexOfFirst { it.id == pending.sourceId }.takeIf { it >= 0 } ?: 0
                } else 0
                _uiState.value = _uiState.value.copy(
                    sidePanel = SidePanelData(
                        sources = sources,
                        selectedSourceIndex = prefSourceIdx,
                        // 已有分类/频道（缓存命中）则不显示 spinner；否则加载中
                        isLoadingCategories = _uiState.value.sidePanel.categories.isEmpty(),
                        isLoadingChannels = _uiState.value.sidePanel.channels.isEmpty()
                    )
                )
                loadCategoriesForSource(prefSourceIdx)
            } catch (e: Exception) {
                Log.e(TAG, "loadSidePanelSources failed", e)
                _uiState.value = _uiState.value.copy(
                    sidePanel = _uiState.value.sidePanel.copy(
                        isLoadingCategories = false,
                        isLoadingChannels = false
                    )
                )
            } finally {
                // 无论成功/失败/提前 return，都复位加载标记，允许后续重新加载
                sourcesLoading = false
            }
        }
    }

    /** 加载指定源的分类列表（收藏始终是第一个）
     * @param force 为 true 时忽略缓存、强制重新加载（用户主动切换源时使用）
     */
    fun loadCategoriesForSource(sourceIndex: Int, force: Boolean = false) {
        val state = _uiState.value
        val source = state.sidePanel.sources.getOrNull(sourceIndex) ?: return
        // 缓存命中：已加载分类确实属于该源（用源 id 判定，避免 selectedSourceIndex 滞后导致误判）→ 直接复用
        if (!force && state.sidePanel.loadedSourceId == source.id &&
            state.sidePanel.categories.isNotEmpty()
        ) {
            return
        }
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    sidePanel = _uiState.value.sidePanel.copy(
                        isLoadingCategories = true,
                        isLoadingChannels = true
                    )
                )
                val channels = ApiClient.getChannels(source.id)
                if (channels.isEmpty() && state.sidePanel.channels.isEmpty()) {
                    // 无频道数据：仅关闭加载态，避免空 spinner
                    _uiState.value = _uiState.value.copy(
                        sidePanel = state.sidePanel.copy(
                            isLoadingCategories = false,
                            isLoadingChannels = false
                        )
                    )
                    return@launch
                }
                val groups = channels.map { it.group.ifEmpty { "未分类" } }.distinct()
                val categories = mutableListOf<CategoryEntry>()
                categories.add(CategoryEntry(name = "搜索", isSearch = true))
                if (ApiClient.isLoggedIn) {
                    categories.add(CategoryEntry(name = "收藏", isFavorites = true))
                }
                groups.forEach { g -> categories.add(CategoryEntry(name = g, isFavorites = false)) }

                val pending = pendingCurrentChannel
                val defaultCatIdx = categories.indexOfFirst { !it.isSearch && !it.isFavorites }
                    .takeIf { it >= 0 } ?: 0
                val prefCatIdx = if (pending != null && categories.isNotEmpty()) {
                    when {
                        pending.isFavorite || pending.favId != null -> {
                            categories.indexOfFirst { it.isFavorites }.takeIf { it >= 0 } ?: defaultCatIdx
                        }
                        else -> {
                            val matchedGroupChannel = channels.firstOrNull { it.url == pending.url }
                            val catName = matchedGroupChannel?.group?.ifEmpty { "未分类" }
                            categories.indexOfFirst { !it.isFavorites && !it.isSearch && it.name == catName }
                                .takeIf { it >= 0 } ?: defaultCatIdx
                        }
                    }
                } else defaultCatIdx

                _uiState.value = _uiState.value.copy(
                    sidePanel = state.sidePanel.copy(
                        categories = categories,
                        // 换源后必须先清空旧源频道列表，否则后续 loadChannelsForCategory
                        // 会因 channels 非空命中缓存判断而直接复用旧列表、不刷新
                        channels = emptyList(),
                        selectedSourceIndex = sourceIndex,
                        selectedCategoryIndex = prefCatIdx,
                        loadedSourceId = source.id,
                        isLoadingCategories = false
                    )
                )
                loadChannelsForCategory(sourceIndex, prefCatIdx)
            } catch (e: Exception) {
                Log.e(TAG, "loadCategoriesForSource failed", e)
                _uiState.value = _uiState.value.copy(
                    sidePanel = state.sidePanel.copy(
                        categories = emptyList(),
                        channels = emptyList(),
                        isLoadingCategories = false,
                        isLoadingChannels = false
                    )
                )
            }
        }
    }

    /**
     * 在当前源内定位「正在播放的频道」，使面板打开时高亮 / 聚焦到它。
     *
     * 必要性：启动预热时还没有「当前播放频道」信息，分类与频道是按默认分类加载的；
     * 用户按确定键打开面板时，播放中的频道往往不在已展示的分类里。
     * 此时不能走 [loadCategoriesForSource]——它的缓存命中分支会直接返回、不会切换分类，
     * 导致焦点与高亮停在无关频道上。这里按频道所属分组直接切到对应分类并强制刷新其列表。
     */
    private fun locateCurrentChannelInSource(sourceIndex: Int) {
        val pending = pendingCurrentChannel
        if (pending == null) {
            loadCategoriesForSource(sourceIndex, force = true)
            return
        }
        viewModelScope.launch {
            try {
                val state = _uiState.value
                val source = state.sidePanel.sources.getOrNull(sourceIndex) ?: return@launch
                val catIdx = if (pending.isFavorite || pending.favId != null) {
                    // 播放中的是收藏频道 → 切到「收藏」分类
                    state.sidePanel.categories.indexOfFirst { it.isFavorites }.takeIf { it >= 0 }
                } else {
                    // 用缓存的频道表（内存 / 每日磁盘缓存，通常为命中）反查频道所属分组
                    val all = ApiClient.getChannels(source.id)
                    val matched = all.firstOrNull { it.url == pending.url }
                        ?: all.firstOrNull { it.name == pending.name }
                    val group = matched?.group?.ifEmpty { "未分类" }
                    group?.let { g ->
                        state.sidePanel.categories
                            .indexOfFirst { !it.isSearch && !it.isFavorites && it.name == g }
                            .takeIf { it >= 0 }
                    }
                }
                if (catIdx != null) {
                    Log.d(TAG, "定位当前播放频道所在分类：cat=$catIdx url=${pending.url}")
                    loadChannelsForCategory(sourceIndex, catIdx, force = true)
                } else {
                    // 分类表里找不到（源已变更 / 数据未就绪）→ 重新拉分类再走定位流程
                    loadCategoriesForSource(sourceIndex, force = true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "locateCurrentChannelInSource failed", e)
                loadCategoriesForSource(sourceIndex, force = true)
            }
        }
    }

    /**
     * 加载指定分类的频道列表
     * @param force 为 true 时忽略缓存、强制重新加载（定位当前播放频道时使用）
     */
    fun loadChannelsForCategory(sourceIndex: Int, categoryIndex: Int, force: Boolean = false) {
        val state = _uiState.value
        val source = state.sidePanel.sources.getOrNull(sourceIndex) ?: return
        val category = state.sidePanel.categories.getOrNull(categoryIndex) ?: return
        if (category.isSearch) {
            enterSearchMode(sourceIndex, categoryIndex)
            return
        }
        // 同一源同一分类且频道已加载（缓存命中）→ 直接复用，不显示 spinner
        // 例外：收藏分类在数据已变更（favoritesDirty）时必须重新拉取，
        // 否则取消收藏后列表里仍显示该频道，要下次冷启动才消失
        if (!force && !(category.isFavorites && state.sidePanel.favoritesDirty) &&
            state.sidePanel.selectedSourceIndex == sourceIndex &&
            state.sidePanel.selectedCategoryIndex == categoryIndex &&
            state.sidePanel.channels.isNotEmpty()
        ) {
            return
        }
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    sidePanel = _uiState.value.sidePanel.copy(isLoadingChannels = true)
                )
                val channels: List<ChannelEntry>
                if (category.isFavorites) {
                    val favs = ApiClient.getFavorites()
                    channels = favs.map { fav ->
                        ChannelEntry(
                            id = fav.id,
                            name = fav.name,
                            url = fav.url,
                            logo = fav.logo ?: "",
                            sourceId = fav.sourceId,
                            favId = fav.id,
                            isFavorite = true,
                            category = "收藏",
                            ua = fav.ua,
                            rf = fav.rf
                        )
                    }
                } else {
                    val allChannels = ApiClient.getChannels(source.id)
                    channels = allChannels
                        .filter { (it.group.ifEmpty { "未分类" }) == category.name }
                        .map { ch ->
                            ChannelEntry(
                                id = ch.id,
                                name = ch.name,
                                url = ch.url,
                                logo = ch.logo,
                                sourceId = source.id,
                                favId = null,
                                isFavorite = false,
                                category = ch.group,
                                countryAttr = ch.countryAttr,
                                langs = ch.langs,
                                ua = ch.ua,
                                rf = ch.rf
                            )
                        }
                }

                val pending = pendingCurrentChannel
                val matchedIdx = if (pending != null) {
                    channels.indexOfFirst { it.url == pending.url }.takeIf { it >= 0 }
                        ?: channels.indexOfFirst { it.name == pending.name }.takeIf { it >= 0 }
                } else null

                if (matchedIdx != null) {
                    Log.d(TAG, "定位当前播放频道：idx=$matchedIdx url=${pending?.url}")
                    pendingCurrentChannel = null
                }

                _uiState.value = _uiState.value.copy(
                    sidePanel = state.sidePanel.copy(
                        channels = channels,
                        selectedCategoryIndex = categoryIndex,
                        selectedChannelIndex = matchedIdx ?: 0,
                        isSearchMode = false,
                        searchQuery = "",
                        searchResults = emptyList(),
                        isLoadingChannels = false,
                        // 收藏分类刚拉到最新数据 → 清除脏标记；其他分类保持不变
                        favoritesDirty = state.sidePanel.favoritesDirty && !category.isFavorites
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "loadChannelsForCategory failed", e)
                _uiState.value = _uiState.value.copy(
                    sidePanel = state.sidePanel.copy(
                        channels = emptyList(),
                        isSearchMode = false,
                        searchQuery = "",
                        searchResults = emptyList(),
                        isLoadingChannels = false,
                        favoritesDirty = state.sidePanel.favoritesDirty && !category.isFavorites
                    )
                )
            }
        }
    }

    /**
     * 收藏发生变更（添加 / 取消）后调用：让侧边栏「收藏」分类即时反映最新数据。
     *
     * - 当前正停留在收藏分类 → 立即重新拉取并替换列表，画面上马上少掉/多出该频道；
     * - 停留在其他分类（或面板关闭）→ 只打脏标记，下次切到收藏分类时强制重新加载，
     *   不会打断用户正在浏览的列表。
     */
    fun onFavoritesChanged() {
        val state = _uiState.value
        if (!ApiClient.isLoggedIn) return
        _uiState.value = _uiState.value.copy(
            sidePanel = state.sidePanel.copy(favoritesDirty = true)
        )
        // 分类表已加载且当前展示的就是收藏分类 → 立刻重载（loadChannelsForCategory 会清脏标记）
        val favCatIdx = state.sidePanel.categories.indexOfFirst { it.isFavorites }
        if (favCatIdx >= 0 && state.sidePanel.selectedCategoryIndex == favCatIdx &&
            !state.sidePanel.isSearchMode
        ) {
            loadChannelsForCategory(state.sidePanel.selectedSourceIndex, favCatIdx, force = true)
        }
    }

    /** 选择频道：构建播放列表并通知 PlayerScreen 播放 */
    fun selectChannel(channelEntry: ChannelEntry): List<PlaylistItem> {
        val state = _uiState.value
        // 搜索模式下用搜索结果作为播放列表，否则用当前分类的频道列表
        val channels = if (state.sidePanel.isSearchMode) {
            state.sidePanel.searchResults
        } else {
            state.sidePanel.channels
        }
        val idx = channels.indexOfFirst { it.id == channelEntry.id && it.url == channelEntry.url }
        val safeIdx = if (idx >= 0) idx else 0

        // 播放列表 = 当前列表（搜索结果或分类频道）
        val playlist = channels.map { ch ->
            PlaylistItem(
                name = ch.name,
                url = ch.url,
                sourceId = ch.sourceId,
                favId = ch.favId,
                isFavorite = ch.isFavorite,
                country = ch.countryAttr,
                ua = ch.ua,
                rf = ch.rf
            )
        }
        currentPlaylist = playlist
        // 记录列表来源：续播时据此重建同一份列表（搜索结果无法复原，退化为单条）
        currentPlaylistOrigin = if (state.sidePanel.isSearchMode) {
            PlaylistOrigin(LastPlayedChannel.ORIGIN_SINGLE)
        } else {
            val cat = state.sidePanel.categories.getOrNull(state.sidePanel.selectedCategoryIndex)
            when {
                cat == null -> PlaylistOrigin(LastPlayedChannel.ORIGIN_SINGLE)
                cat.isFavorites -> PlaylistOrigin(LastPlayedChannel.ORIGIN_FAVORITES)
                else -> PlaylistOrigin(
                    kind = LastPlayedChannel.ORIGIN_CATEGORY,
                    sourceId = state.sidePanel.sources.getOrNull(state.sidePanel.selectedSourceIndex)?.id,
                    category = cat.name
                )
            }
        }

        _uiState.value = _uiState.value.copy(
            sidePanel = state.sidePanel.copy(selectedChannelIndex = safeIdx)
        )
        return playlist
    }

    // ===== 搜索功能 =====

    /**
     * 进入搜索模式：第三列切换为搜索输入 + 实时筛选结果。
     * 立即清空 channels 列表避免显示旧分类频道，搜索结果为空直到用户输入。
     */
    private fun enterSearchMode(sourceIndex: Int, categoryIndex: Int) {
        val state = _uiState.value
        _uiState.value = _uiState.value.copy(
            sidePanel = state.sidePanel.copy(
                selectedSourceIndex = sourceIndex,
                selectedCategoryIndex = categoryIndex,
                isSearchMode = true,
                searchQuery = "",
                searchResults = emptyList(),
                channels = emptyList(),
                selectedChannelIndex = 0
            )
        )
    }

    /**
     * 更新搜索查询：在当前源的所有频道中按名称包含匹配（大小写不敏感）。
     * 空查询 → 结果为空（避免一次返回全量列表，TV 端滚动不便）。
     * 结果数上限 [SEARCH_MAX_RESULTS]，避免 LazyColumn 过大影响体验。
     */
    fun updateSearchQuery(query: String) {
        val state = _uiState.value
        if (!state.sidePanel.isSearchMode) return
        val source = state.sidePanel.sources.getOrNull(state.sidePanel.selectedSourceIndex) ?: return
        viewModelScope.launch {
            try {
                val allChannels = ApiClient.getChannels(source.id)
                val q = query.trim()
                val results: List<ChannelEntry> = if (q.isEmpty()) {
                    emptyList()
                } else {
                    val lowerQ = q.lowercase()
                    allChannels.asSequence()
                        .filter { it.name.lowercase().contains(lowerQ) }
                        .take(SEARCH_MAX_RESULTS)
                        .map { ch ->
                            ChannelEntry(
                                id = ch.id,
                                name = ch.name,
                                url = ch.url,
                                logo = ch.logo,
                                sourceId = source.id,
                                favId = null,
                                isFavorite = false,
                                category = ch.group,
                                ua = ch.ua,
                                rf = ch.rf
                            )
                        }
                        .toList()
                }
                _uiState.value = _uiState.value.copy(
                    sidePanel = _uiState.value.sidePanel.copy(
                        searchQuery = query,
                        searchResults = results,
                        selectedChannelIndex = 0
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "updateSearchQuery failed", e)
            }
        }
    }

    /** 退出搜索模式：回到当前源的首个真实分类 */
    fun exitSearchMode() {
        val state = _uiState.value
        val categories = state.sidePanel.categories
        val target = categories.indexOfFirst { !it.isSearch && !it.isFavorites }
            .takeIf { it >= 0 } ?: categories.indexOfFirst { !it.isSearch }.takeIf { it >= 0 } ?: 0
        loadChannelsForCategory(state.sidePanel.selectedSourceIndex, target)
    }

    // ===== 侧边栏开关 =====

    /**
     * 打开侧边栏
     * @param currentChannel 当前正在播放的频道（用于在侧边栏中定位并高亮到对应的源/分类/频道）
     */
    fun showSidePanel(currentChannel: PlaylistItem? = null) {
        if (!_uiState.value.sidePanelVisible) {
            pendingCurrentChannel = currentChannel
            _uiState.value = _uiState.value.copy(sidePanelVisible = true)
            if (_uiState.value.sidePanel.sources.isEmpty()) {
                loadSidePanelSources()
            } else if (currentChannel != null) {
                // 已有源列表：根据 currentChannel 同步选中正确的源/分类/频道
                val state = _uiState.value
                val sources = state.sidePanel.sources
                val prefSrcIdx = if (sources.isNotEmpty() && currentChannel.sourceId != null) {
                    sources.indexOfFirst { it.id == currentChannel.sourceId }.takeIf { it >= 0 } ?: state.sidePanel.selectedSourceIndex
                } else state.sidePanel.selectedSourceIndex
                if (prefSrcIdx != state.sidePanel.selectedSourceIndex) {
                    _uiState.value = _uiState.value.copy(
                        sidePanel = state.sidePanel.copy(selectedSourceIndex = prefSrcIdx)
                    )
                }
                if (state.sidePanel.categories.isEmpty() ||
                    state.sidePanel.loadedSourceId != sources.getOrNull(prefSrcIdx)?.id
                ) {
                    // 分类为空，或当前分类不属于正在播放的源（换源后未刷新）→ 重新加载
                    loadCategoriesForSource(prefSrcIdx)
                } else {
                    // 分类也已加载：在当前分类下查找频道 URL，找不到就切换到合适的分类
                    val inCurrent = state.sidePanel.channels.any { it.url == currentChannel.url }
                    if (inCurrent) {
                        // 直接在当前分类匹配
                        val idx = state.sidePanel.channels.indexOfFirst { it.url == currentChannel.url }
                            .takeIf { it >= 0 } ?: 0
                        _uiState.value = _uiState.value.copy(
                            sidePanel = state.sidePanel.copy(selectedChannelIndex = idx)
                        )
                        pendingCurrentChannel = null
                    } else {
                        // 需要在当前源重选分类 → 定位到当前播放频道所在的分类
                        locateCurrentChannelInSource(prefSrcIdx)
                    }
                }
            }
        }
    }

    fun hideSidePanel() {
        _uiState.value = _uiState.value.copy(sidePanelVisible = false)
    }

    /**
     * 切换侧边栏可见性
     * @param currentChannel 当前正在播放的频道（显示时传入用于定位）
     */
    fun toggleSidePanel(currentChannel: PlaylistItem? = null) {
        if (_uiState.value.sidePanelVisible) hideSidePanel() else showSidePanel(currentChannel)
    }

    // ===== 设置抽屉（MENU 键） =====

    /** 切换右侧配置抽屉可见性（若侧边栏打开则先关侧边栏，避免两者重叠） */
    fun toggleSettingsDrawer() {
        _uiState.value = _uiState.value.copy(
            settingsVisible = !_uiState.value.settingsVisible,
            sidePanelVisible = false
        )
    }

    fun hideSettingsDrawer() {
        _uiState.value = _uiState.value.copy(settingsVisible = false)
    }

    fun updateStuckTimeout(ms: Long) {
        val next = _uiState.value.playbackSettings.copy(stuckTimeoutMs = ms)
        commitSettings(next)
    }

    fun updateDirectTimeout(ms: Long) {
        val next = _uiState.value.playbackSettings.copy(directTimeoutMs = ms)
        commitSettings(next)
    }

    fun updateProxyTimeout(ms: Long) {
        val next = _uiState.value.playbackSettings.copy(proxyTimeoutMs = ms)
        commitSettings(next)
    }

    fun updateRendererMode(mode: RendererMode) {
        val next = _uiState.value.playbackSettings.copy(rendererMode = mode)
        commitSettings(next)
    }

    /** 首播频道模式（仅登录用户可调；下次启动生效） */
    fun updateStartChannelMode(mode: StartChannelMode) {
        val next = _uiState.value.playbackSettings.copy(startChannelMode = mode)
        commitSettings(next)
    }

    /** 画面诊断 HUD 开关（持久化，重启后保持上次选择） */
    fun updateDiagHud(enabled: Boolean) {
        val next = _uiState.value.playbackSettings.copy(diagHudEnabled = enabled)
        commitSettings(next)
    }

    /**
     * 保存设置到持久化并更新状态。
     * 注意：composedTV 架构中 engine 在 PlayerScreen 创建，VM 不直接持有 engine，
     * 由 PlayerScreen 监听 playbackSettings 变化后调用 engine.applyPlaybackSettings()。
     */
    private fun commitSettings(next: PlaybackSettings) {
        PlaybackSettings.save(app, next)
        _uiState.value = _uiState.value.copy(playbackSettings = next)
    }

    /**
     * 准备进入登录界面：可传入用户名用于预填（点击已登录用户卡片时）。
     * 复用 lastLoginUsername 字段承载「待登录用户名」，LoginScreen 会把它显示为预填值。
     * 传 null 时清空，让用户手动输入。
     */
    fun prepareLoginWithUsername(username: String?) {
        _uiState.value = _uiState.value.copy(lastLoginUsername = username)
    }

    // ===== 认证 =====

    fun login(username: String, password: String, isRegister: Boolean, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val action = if (isRegister) "register" else "login"
            val params = mutableMapOf("username" to username, "password" to password)
            if (isRegister) params["confirm"] = password
            val res = ApiClient.auth(action, params)
            if (res.ok) {
                _uiState.value = _uiState.value.copy(
                    storedUsers = ApiClient.getStoredUsers(),
                    lastLoginUsername = ApiClient.getLastLoginUsername()
                )
            }
            onResult(res.ok, res.message)
        }
    }

    fun enterAsGuest() {
        ApiClient.enterAsGuest()
    }

    fun logout() {
        viewModelScope.launch {
            ApiClient.auth("logout")
            ApiClient.logoutLocal()
            _uiState.value = _uiState.value.copy(storedUsers = ApiClient.getStoredUsers())
        }
    }
}
