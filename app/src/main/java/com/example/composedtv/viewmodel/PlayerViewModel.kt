package com.example.composedtv.viewmodel

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.composedtv.data.remote.ApiChannel
import com.example.composedtv.data.remote.ApiClient
import com.example.composedtv.data.remote.ApiFavorite
import com.example.composedtv.data.remote.ApiSource
import com.example.composedtv.data.remote.StoredUser
import com.example.composedtv.player.PlaylistItem
import com.example.composedtv.player.PlayerEngine
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

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
    val isLoadingChannels: Boolean = false
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
    val langs: List<String> = emptyList()
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

/** 播放参数设置（由 MENU 设置抽屉调整，持久化保存） */
data class PlaybackSettings(
    /** 直连胜出后持续缓冲判定 stuck 的时长（毫秒） */
    val stuckTimeoutMs: Long = 8_000L,
    /** 副路径（代理）延迟启动毫秒数 */
    val hedgeMs: Long = 1_500L,
    /** 直连胜出后 stuck 时最多复活代理候选的次数 */
    val reviveMax: Int = 1,
    /** 视频渲染方式（AUTO / SURFACE / TEXTURE） */
    val rendererMode: RendererMode = RendererMode.AUTO
) {
    companion object {
        private const val PREF_NAME = "playback_settings"
        private const val KEY_STUCK = "stuck_timeout_ms"
        private const val KEY_HEDGE = "race_hedge_ms"
        private const val KEY_REVIVE = "proxy_revive_max"
        private const val KEY_RENDERER = "renderer_mode"

        fun load(context: Context): PlaybackSettings {
            val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return PlaybackSettings(
                stuckTimeoutMs = sp.getLong(KEY_STUCK, 8_000L),
                hedgeMs = sp.getLong(KEY_HEDGE, 1_500L),
                reviveMax = sp.getInt(KEY_REVIVE, 1),
                rendererMode = RendererMode.fromValue(sp.getInt(KEY_RENDERER, RendererMode.AUTO.value))
            )
        }

        fun save(context: Context, s: PlaybackSettings) {
            val sp: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            sp.edit()
                .putLong(KEY_STUCK, s.stuckTimeoutMs)
                .putLong(KEY_HEDGE, s.hedgeMs)
                .putInt(KEY_REVIVE, s.reviveMax)
                .putInt(KEY_RENDERER, s.rendererMode.value)
                .apply()
        }
    }
}

/** 设置抽屉可选值集合（供 UI 渲染单选列表） */
object PlaybackSettingOptions {
    val stuckOptions = listOf(5_000L to "5秒", 8_000L to "8秒(默认)", 12_000L to "12秒")
    val hedgeOptions = listOf(1_000L to "1秒", 1_500L to "1.5秒(默认)", 2_500L to "2.5秒")
    val reviveOptions = listOf(0 to "0次(关闭)", 1 to "1次(默认)", 2 to "2次")
    val rendererOptions = listOf(
        RendererMode.AUTO to "自动(默认)",
        RendererMode.SURFACE to "SurfaceView(性能优)",
        RendererMode.TEXTURE to "TextureView(兼容)"
    )
}

/** UI 状态 */
data class PlayerUiState(
    val isLoading: Boolean = false,
    val isGuest: Boolean = false,
    val sidePanelVisible: Boolean = false,
    val sidePanel: SidePanelData = SidePanelData(),
    val initialChannelLoaded: Boolean = false,
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

    /** 播放列表管理器（由 PlayerScreen 持有 PlayerEngine，VM 负责数据供给） */
    private var currentPlaylist: List<PlaylistItem> = emptyList()

    /** 当前播放频道的索引（由 PlayerScreen 同步更新，用于 Activity 层兜底切侧边栏） */
    private var currentPlayIndex: Int = 0

    /** 正在等待定位到侧边栏的「当前播放频道」（显示侧边栏时临时持有，定位完成后清空） */
    private var pendingCurrentChannel: PlaylistItem? = null

    /** 初始化：根据是否游客加载初始频道 */
    fun initialize(isGuest: Boolean) {
        _uiState.value = _uiState.value.copy(isGuest = isGuest, storedUsers = ApiClient.getStoredUsers())
        if (isGuest) {
            loadGuestStartChannel()
        } else {
            loadFirstFavoriteChannel()
        }
    }

    // ===== 初始播放 =====

    private fun loadGuestStartChannel() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            try {
                val guestChannel = ApiClient.getGuestStart()
                if (guestChannel != null) {
                    val playlist = listOf(
                        PlaylistItem(
                            name = guestChannel.name,
                            url = guestChannel.url,
                            sourceId = guestChannel.id,
                            country = ""
                        )
                    )
                    currentPlaylist = playlist
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        initialChannelLoaded = true,
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
                            country = ""
                        )
                    }
                    currentPlaylist = playlist
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        initialChannelLoaded = true,
                        errorMessage = null
                    )
                } else {
                    // 没有收藏 → 退化为游客起始频道
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

    /** 供 PlayerScreen 同步当前播放索引（用于 Activity 层兜底的确定键弹出侧边栏） */
    fun syncCurrentPlayIndex(index: Int) {
        currentPlayIndex = index
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

    /** 打开侧边栏时加载源列表 */
    fun loadSidePanelSources() {
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

    /** 加载指定分类的频道列表 */
    fun loadChannelsForCategory(sourceIndex: Int, categoryIndex: Int) {
        val state = _uiState.value
        val source = state.sidePanel.sources.getOrNull(sourceIndex) ?: return
        val category = state.sidePanel.categories.getOrNull(categoryIndex) ?: return
        if (category.isSearch) {
            enterSearchMode(sourceIndex, categoryIndex)
            return
        }
        // 同一源同一分类且频道已加载（缓存命中）→ 直接复用，不显示 spinner
        if (state.sidePanel.selectedSourceIndex == sourceIndex &&
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
                            category = "收藏"
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
                                langs = ch.langs
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
                        isLoadingChannels = false
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
                        isLoadingChannels = false
                    )
                )
            }
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
                country = ch.countryAttr
            )
        }
        currentPlaylist = playlist

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
                                category = ch.group
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
                    loadCategoriesForSource(prefSrcIdx)
                } else if (state.sidePanel.categories.isEmpty() ||
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
                        // 需要在当前源重选分类
                        loadCategoriesForSource(prefSrcIdx)
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

    fun updateHedge(ms: Long) {
        val next = _uiState.value.playbackSettings.copy(hedgeMs = ms)
        commitSettings(next)
    }

    fun updateReviveMax(n: Int) {
        val next = _uiState.value.playbackSettings.copy(reviveMax = n)
        commitSettings(next)
    }

    fun updateRendererMode(mode: RendererMode) {
        val next = _uiState.value.playbackSettings.copy(rendererMode = mode)
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

    fun selectStoredUser(username: String, onResult: (Boolean) -> Unit) {
        val ok = ApiClient.switchToStoredUser(username)
        if (ok) {
            _uiState.value = _uiState.value.copy(storedUsers = ApiClient.getStoredUsers())
        }
        onResult(ok)
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
