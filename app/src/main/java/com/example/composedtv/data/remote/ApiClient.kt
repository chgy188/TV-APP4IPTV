package com.example.composedtv.data.remote

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * worker4iptv 后端 API 客户端（Compose TV 版）
 *
 * 支持多用户会话存储：可保存多个已登录用户的 token，在用户选择界面快速切换。
 * 当前活跃用户的 token 用于所有需认证的 API 调用。
 */
object ApiClient {

    private const val TAG = "ApiClient"

    var baseUrl: String = "https://tv.run4u.dpdns.org"

    private var prefs: SharedPreferences? = null
    /** 应用 Context（由 init 注入，用于访问 filesDir） */
    private var appContext: Context? = null
    private const val PREFS_NAME = "composedtv_prefs"
    private const val KEY_TOKEN = "auth_token"
    private const val KEY_USER = "auth_user"
    private const val KEY_STORED_USERS = "stored_users"
    private const val KEY_LAST_LOGIN_USERNAME = "last_login_username"

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun init(context: Context) {
        val ctx = context.applicationContext
        prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        appContext = ctx
        // 清理过期的每日缓存（昨天及以前的）
        cleanOldDailyCache()
    }

    /* ====================== 每日磁盘缓存 ====================== */
    /** 每日缓存根目录：filesDir/daily_cache/ */
    private fun dailyCacheDir(): File? {
        val ctx = appContext ?: return null
        val dir = File(ctx.filesDir, "daily_cache")
        if (!dir.exists()) dir.mkdirs()
        return if (dir.exists() && dir.isDirectory) dir else null
    }

    /** 今天的日期字符串 yyyyMMdd，用于构造缓存文件名 */
    private fun todayStr(): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    /** 清理昨天及以前的每日缓存文件 */
    private fun cleanOldDailyCache() {
        val dir = dailyCacheDir() ?: return
        val today = todayStr()
        try {
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.contains("_") && !f.name.startsWith("sources_$today")
                    && !f.name.startsWith("channels_$today")) {
                    f.delete()
                }
            }
        } catch (_: Exception) { }
    }

    /** 将 sourceId 转换为文件安全的名称（替换特殊字符） */
    private fun safeFileName(raw: String): String =
        raw.replace("[^a-zA-Z0-9._-]".toRegex(), "_")

    /** 读每日磁盘缓存：成功返回 JSONArray，失败返回 null */
    private fun readDailyCache(filename: String): JSONArray? = runCatching {
        val dir = dailyCacheDir() ?: return null
        val f = File(dir, filename)
        if (!f.exists() || !f.isFile) return null
        val text = f.readText(Charsets.UTF_8)
        if (text.isBlank()) return null
        JSONArray(text)
    }.getOrNull()

    /** 写每日磁盘缓存 */
    private fun writeDailyCache(filename: String, arr: JSONArray) {
        runCatching {
            val dir = dailyCacheDir() ?: return
            val f = File(dir, filename)
            f.writeText(arr.toString(), Charsets.UTF_8)
        }
    }

    /* ====================== 内存缓存 ====================== */
    private var cachedPublicSources: List<ApiSource>? = null
    private var cachedMySources: List<ApiSource>? = null
    private val cachedChannels = mutableMapOf<String, List<ApiChannel>>()
    private var cachedFavorites: List<ApiFavorite>? = null

    fun invalidateCache(channels: Boolean = false, favorites: Boolean = false, sources: Boolean = false) {
        if (sources) {
            cachedPublicSources = null
            cachedMySources = null
        }
        if (channels) cachedChannels.clear()
        if (favorites) cachedFavorites = null
    }

    /* ====================== 认证态 ====================== */

    var token: String?
        get() = prefs?.getString(KEY_TOKEN, null)
        private set(v) {
            prefs?.edit()?.putString(KEY_TOKEN, v)?.apply()
        }

    var currentUser: ApiUser?
        get() {
            val raw = prefs?.getString(KEY_USER, null) ?: return null
            return try {
                val o = JSONObject(raw)
                ApiUser(
                    id = o.optString("id", ""),
                    username = o.optString("username", ""),
                    role = o.optString("role", "").takeIf { it.isNotEmpty() },
                    needsDefaultSource = o.optBoolean("needsDefaultSource", false)
                )
            } catch (e: Exception) { null }
        }
        private set(v) {
            if (v == null) prefs?.edit()?.remove(KEY_USER)?.apply()
            else prefs?.edit()?.putString(KEY_USER, JSONObject().apply {
                put("id", v.id); put("username", v.username)
                put("role", v.role); put("needsDefaultSource", v.needsDefaultSource)
            }.toString())?.apply()
        }

    val isLoggedIn: Boolean get() = !token.isNullOrEmpty()

    /* ====================== 多用户存储 ====================== */

    /** 获取所有已存储的用户会话（不含当前活跃用户重复） */
    fun getStoredUsers(): List<StoredUser> {
        val raw = prefs?.getString(KEY_STORED_USERS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<StoredUser>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(StoredUser(
                    username = o.optString("username", ""),
                    token = o.optString("token", ""),
                    userId = o.optString("userId", ""),
                    role = o.optString("role", "").takeIf { it.isNotEmpty() }
                ))
            }
            list
        } catch (e: Exception) { emptyList() }
    }

    /** 保存/更新一个用户会话到存储列表（按 username 去重） */
    private fun saveStoredUser(user: StoredUser) {
        val users = getStoredUsers().filter { it.username != user.username }.toMutableList()
        users.add(user)
        val arr = JSONArray()
        users.forEach { u ->
            arr.put(JSONObject().apply {
                put("username", u.username)
                put("token", u.token)
                put("userId", u.userId)
                put("role", u.role ?: JSONObject.NULL)
            })
        }
        prefs?.edit()?.putString(KEY_STORED_USERS, arr.toString())?.apply()
    }

    /** 从存储列表中移除一个用户 */
    fun removeStoredUser(username: String) {
        val users = getStoredUsers().filter { it.username != username }
        val arr = JSONArray()
        users.forEach { u ->
            arr.put(JSONObject().apply {
                put("username", u.username)
                put("token", u.token)
                put("userId", u.userId)
                put("role", u.role ?: JSONObject.NULL)
            })
        }
        prefs?.edit()?.putString(KEY_STORED_USERS, arr.toString())?.apply()
    }

    /** 以游客身份进入（清除当前活跃 token，但不删除存储的用户） */
    fun enterAsGuest() {
        token = null
        currentUser = null
        invalidateCache(channels = true, favorites = true, sources = true)
    }

    /* ====================== 上次登录用户名 ====================== */

    /** 获取上次成功登录的用户名（仅供登录界面预填，不参与鉴权） */
    fun getLastLoginUsername(): String? =
        prefs?.getString(KEY_LAST_LOGIN_USERNAME, null)?.takeIf { it.isNotEmpty() }

    /** 保存上次成功登录的用户名 */
    fun saveLastLoginUsername(username: String) {
        prefs?.edit()?.putString(KEY_LAST_LOGIN_USERNAME, username)?.apply()
    }

    /** 清除上次登录用户名 */
    fun clearLastLoginUsername() {
        prefs?.edit()?.remove(KEY_LAST_LOGIN_USERNAME)?.apply()
    }

    /* ====================== 代理 ====================== */

    fun hlsProxyUrl(playUrl: String): String {
        val enc = URLEncoder.encode(playUrl, "UTF-8")
        return "$baseUrl/api/hls?url=$enc"
    }

    /* ====================== 基础请求 ====================== */

    private fun authedGet(path: String): Request.Builder {
        val b = Request.Builder().url("$baseUrl$path").get()
        token?.let { b.header("Authorization", "Bearer $it") }
        return b
    }

    private fun authedSend(path: String, method: String, body: JSONObject?): Request.Builder {
        val b = Request.Builder().url("$baseUrl$path").method(method,
            if (method == "GET" || method == "DELETE") null
            else body?.toString()?.toRequestBody(JSON)
        )
        b.header("Content-Type", "application/json")
        token?.let { b.header("Authorization", "Bearer $it") }
        return b
    }

    private fun exec(builder: Request.Builder): JSONObject {
        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(text).optString("error", text) }.getOrDefault(text)
                throw RuntimeException("HTTP ${resp.code}: $msg")
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private fun execArray(builder: Request.Builder): JSONArray {
        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(text).optString("error", text) }.getOrDefault(text)
                throw RuntimeException("HTTP ${resp.code}: $msg")
            }
            return parseArray(text)
        }
    }

    private fun parseStatus(v: Any?): Int = when (v) {
        is Int -> v
        is String -> v.toIntOrNull() ?: -1
        else -> -1
    }

    private fun parseArray(body: String): JSONArray {
        val json = body.trim()
        return if (json.startsWith("[")) {
            JSONArray(body)
        } else {
            val obj = JSONObject(body)
            val data = if (obj.has("data")) obj.get("data") else obj
            when (data) {
                is JSONArray -> data
                else -> JSONArray().also { it.put(data) }
            }
        }
    }

    /* ====================== 公开接口 ====================== */

    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        try { getPublicSources(); true } catch (e: Exception) {
            Log.w(TAG, "ping failed: ${e.message}"); false
        }
    }

    suspend fun getPublicSources(): List<ApiSource> = withContext(Dispatchers.IO) {
        // 1. 内存缓存
        cachedPublicSources?.let { return@withContext it }
        // 2. 每日磁盘缓存
        val today = todayStr()
        val cacheFile = "sources_public_$today.json"
        readDailyCache(cacheFile)?.let { arr ->
            val list = parseSourcesFromJson(arr)
            if (list.isNotEmpty()) {
                cachedPublicSources = list
                Log.d(TAG, "命中每日缓存: $cacheFile (${list.size} 个源)")
                return@withContext list
            }
        }
        // 3. 网络拉取
        val json = execArray(authedGet("/api/public-sources"))
        val list = parseSourcesFromJson(json)
        cachedPublicSources = list
        // 写入每日缓存
        writeDailyCache(cacheFile, json)
        list
    }

    private fun parseSourcesFromJson(arr: JSONArray): List<ApiSource> {
        val list = mutableListOf<ApiSource>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(ApiSource(
                id = o.optString("id", ""),
                name = o.optString("name", o.optString("id", "未命名源")),
                url = o.optString("url", ""),
                local = o.optBoolean("local", false),
                public = o.optBoolean("public", true)
            ))
        }
        return list
    }

    suspend fun getChannels(sourceId: String): List<ApiChannel> = withContext(Dispatchers.IO) {
        // 1. 内存缓存
        cachedChannels[sourceId]?.let { return@withContext it }
        // 2. 每日磁盘缓存
        val today = todayStr()
        val cacheFile = "channels_${safeFileName(sourceId)}_$today.json"
        readDailyCache(cacheFile)?.let { arr ->
            val list = parseChannelsFromJson(arr)
            if (list.isNotEmpty()) {
                cachedChannels[sourceId] = list
                Log.d(TAG, "命中每日缓存: $cacheFile (${list.size} 个频道, sourceId=$sourceId)")
                return@withContext list
            }
        }
        // 3. 网络拉取
        val path = "/api/channels?sourceId=${URLEncoder.encode(sourceId, "UTF-8")}"
        val json = execArray(authedGet(path))
        val list = parseChannelsFromJson(json)
        cachedChannels[sourceId] = list
        // 写入每日缓存
        writeDailyCache(cacheFile, json)
        list
    }

    private fun parseChannelsFromJson(arr: JSONArray): List<ApiChannel> {
        val total = arr.length()
        val list = ArrayList<ApiChannel>(total)
        for (i in 0 until total) {
            val o = arr.getJSONObject(i)
            list.add(ApiChannel(
                id = o.optString("id", "ch_$i"),
                name = o.optString("n", "未命名频道"),
                group = o.optString("g", ""),
                logo = o.optString("l", ""),
                url = o.optString("u", ""),
                status = o.optString("s", ""),
                checkedAt = o.optLong("t", 0L),
                checkError = o.optString("e", ""),
                lastStatus = parseStatus(o.opt("k")),
                country = o.optString("c", ""),
                countryAttr = o.optString("a", ""),
                langs = run {
                    val larr = o.optJSONArray("g2") ?: run {
                        o.optJSONArray("langs") ?: o.optString("langs", "")
                            .takeIf { it.isNotEmpty() }?.split(",")?.map { it.trim() }
                    }
                    when (larr) {
                        is org.json.JSONArray -> (0 until larr.length()).map { larr.getString(it) }
                        is List<*> -> larr.mapNotNull { it?.toString() }
                        else -> emptyList()
                    }
                }
            ))
        }
        return list
    }

    suspend fun getGuestStart(): ApiChannel? = withContext(Dispatchers.IO) {
        try {
            val o = exec(authedGet("/api/guest-start"))
            if (o.optString("sourceId", "").isNotEmpty() && o.optString("url", "").isNotEmpty()) {
                ApiChannel(
                    id = o.optString("sourceId", "guest"),
                    name = o.optString("name", "起始频道"),
                    group = "",
                    logo = "",
                    url = o.optString("url", ""),
                    status = "", checkedAt = 0, checkError = "",
                    lastStatus = -1, country = o.optString("country", "")
                )
            } else null
        } catch (e: Exception) { null }
    }

    /* ====================== 需登录接口 ====================== */

    suspend fun getMySources(): List<ApiSource> = withContext(Dispatchers.IO) {
        // 1. 内存缓存
        cachedMySources?.let { return@withContext it }
        // 2. 每日磁盘缓存
        val today = todayStr()
        val cacheFile = "sources_my_$today.json"
        readDailyCache(cacheFile)?.let { arr ->
            val list = parseMySourcesFromJson(arr)
            if (list.isNotEmpty()) {
                cachedMySources = list
                Log.d(TAG, "命中每日缓存: $cacheFile (${list.size} 个我的源)")
                return@withContext list
            }
        }
        // 3. 网络拉取
        val json = execArray(authedGet("/api/mysources"))
        val list = parseMySourcesFromJson(json)
        cachedMySources = list
        writeDailyCache(cacheFile, json)
        list
    }

    private fun parseMySourcesFromJson(arr: JSONArray): List<ApiSource> {
        val list = mutableListOf<ApiSource>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(ApiSource(
                id = o.optString("id", ""),
                name = o.optString("name", o.optString("id", "未命名源")),
                url = o.optString("url", ""),
                local = o.optBoolean("local", false),
                public = o.optBoolean("public", false),
                ownerId = o.optString("ownerId", ""),
                ownerName = o.optString("ownerName", "")
            ))
        }
        return list
    }

    /**
     * 获取所有可用源：登录用户 = 我的源 + 公开源；游客 = 公开源
     * 按 sourceId 去重。
     */
    suspend fun getAllSources(): List<ApiSource> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, ApiSource>()
        if (isLoggedIn) {
            try {
                getMySources().forEach { result[it.id] = it }
            } catch (e: Exception) {
                Log.w(TAG, "getMySources failed: ${e.message}")
            }
        }
        try {
            getPublicSources().forEach { result.putIfAbsent(it.id, it) }
        } catch (e: Exception) {
            Log.w(TAG, "getPublicSources failed: ${e.message}")
        }
        result.values.toList()
    }

    suspend fun getFavorites(): List<ApiFavorite> = withContext(Dispatchers.IO) {
        cachedFavorites?.let { return@withContext it }
        val json = execArray(authedGet("/api/favorites"))
        val items = if (json.length() == 1 && json.getJSONObject(0).has("favorites")) {
            json.getJSONObject(0).getJSONArray("favorites")
        } else {
            json
        }
        val list = mutableListOf<ApiFavorite>()
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            val ch = when {
                o.has("channel") && o.get("channel") is JSONObject -> o.getJSONObject("channel")
                o.has("item") && o.get("item") is JSONObject -> o.getJSONObject("item")
                o.has("source") && o.get("source") is JSONObject -> o.getJSONObject("source")
                else -> o
            }
            val name = o.optString("name", "").takeIf { it.isNotEmpty() }
                ?: ch.optString("name", "").takeIf { it.isNotEmpty() }
                ?: ch.optString("title", "").takeIf { it.isNotEmpty() }
                ?: ch.optString("channelName", "").takeIf { it.isNotEmpty() }
                ?: "未命名频道"
            val url = o.optString("url", "").takeIf { it.isNotEmpty() }
                ?: ch.optString("url", "").takeIf { it.isNotEmpty() }
                ?: ch.optString("channelUrl", "").takeIf { it.isNotEmpty() }
                ?: ch.optString("streamUrl", "").takeIf { it.isNotEmpty() }
                ?: ""
            val logo = o.optString("logo", "").takeIf { it.isNotEmpty() }
                ?: ch.optString("logo", "").takeIf { it.isNotEmpty() }
                ?: ch.optString("logoUrl", "").takeIf { it.isNotEmpty() }
            val country = o.optString("country", "").takeIf { it.isNotEmpty() }
                ?: ch.optString("country", "").takeIf { it.isNotEmpty() }
            val sid = o.optString("sourceId", "").takeIf { it.isNotEmpty() }
                ?: ch.optString("sourceId", "").takeIf { it.isNotEmpty() }
            list.add(ApiFavorite(
                // hls4iptv 后端不返回 id，用 url 作为唯一标识（后端按 url 去重/删除）
                id = o.optString("id", "").takeIf { it.isNotEmpty() } ?: url,
                url = url,
                country = country ?: "",
                sourceId = sid,
                name = name,
                logo = logo,
                status = o.optString("status", "").takeIf { it.isNotEmpty() }
                    ?: ch.optString("status", "").takeIf { it.isNotEmpty() }
            ))
        }
        cachedFavorites = list
        list
    }

    suspend fun addFavorite(url: String, name: String, logo: String?, country: String): String {
        val result = withContext(Dispatchers.IO) {
            // hls4iptv 后端要求 body 嵌套在 channel 字段下
            val channel = JSONObject().apply {
                put("url", url)
                put("name", name)
                put("country", country)
                if (!logo.isNullOrEmpty()) put("logo", logo)
            }
            val body = JSONObject().apply { put("channel", channel) }
            exec(authedSend("/api/favorites", "POST", body))
        }
        invalidateCache(favorites = true)
        // hls4iptv 后端不返回单独的 id，用 url 作为唯一标识
        // 后端返回 { favorites: [...], added: true }
        Log.d("ApiClient", "addFavorite 响应: ${result.optString("added", "?")} url=$url")
        return url
    }

    suspend fun removeFavorite(url: String) {
        withContext(Dispatchers.IO) {
            // hls4iptv 后端用 ?url= 查询参数删除，不是 ?id=
            exec(authedSend("/api/favorites?url=${URLEncoder.encode(url, "UTF-8")}", "DELETE", null))
        }
        invalidateCache(favorites = true)
    }

    /* ====================== 认证 ====================== */

    suspend fun auth(action: String, params: Map<String, String> = emptyMap()): AuthResult =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply { put("action", action); params.forEach { put(it.key, it.value) } }
            return@withContext try {
                val o = exec(authedSend("/api/auth", "POST", body))
                val okField = o.optBoolean("ok", o.optBoolean("success", false))
                val t = o.optString("token", "").takeIf { it.isNotEmpty() }
                val userObj = if (o.has("user")) o.getJSONObject("user") else null
                val user = userObj?.let {
                    ApiUser(
                        id = it.optString("id", ""),
                        username = it.optString("username", ""),
                        role = it.optString("role", "").takeIf { r -> r.isNotEmpty() },
                        needsDefaultSource = it.optBoolean("needsDefaultSource", false)
                    )
                }
                val ok = if (action == "logout") okField else (t != null)
                if (ok) {
                    if (action != "logout" && t != null && user != null) {
                        // 登录/注册成功：保存活跃会话 + 存储到用户列表 + 记住用户名
                        token = t
                        currentUser = user
                        saveStoredUser(StoredUser(
                            username = user.username,
                            token = t,
                            userId = user.id,
                            role = user.role
                        ))
                        saveLastLoginUsername(user.username)
                    } else if (action == "logout" && okField) {
                        val uname = currentUser?.username
                        token = null
                        currentUser = null
                        if (uname != null) removeStoredUser(uname)
                        clearLastLoginUsername()
                    }
                }
                val msg = o.optString("message", "").takeIf { it.isNotEmpty() }
                    ?: o.optString("error", "").takeIf { it.isNotEmpty() }
                AuthResult(ok, t, user, msg)
            } catch (e: Exception) {
                AuthResult(false, null, null, e.message ?: e.toString())
            }
        }

    fun logoutLocal() {
        val uname = currentUser?.username
        token = null
        currentUser = null
        if (uname != null) removeStoredUser(uname)
        invalidateCache(channels = true, favorites = true, sources = true)
    }
}
