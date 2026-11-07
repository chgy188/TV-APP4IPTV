package com.example.composedtv.data

import android.content.Context
import android.content.SharedPreferences
import com.example.composedtv.data.remote.ApiClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 点播（VOD）播放进度持久化：按内容 id（频道原始 URL / 外部视频 Uri）记录**当天**播放位置，
 * 实现「同一个片子当天记忆、下次进入自动续播」。真直播流不写入（由 PlayerEngine 判定）。
 *
 * 「当天」语义：每条记录附带保存日期（yyyyMMdd），读取时只有日期等于今天才有效；
 * 隔天记录视为过期并自动清除，避免隔很久再进入时突然跳到旧位置。
 *
 * 记录按用户隔离（复用 [ApiClient.userSpName]），多账号互不干扰。旧版本 SP（无用户后缀、
 * 只有位置没有日期）在本语义下本就应作废，故不做迁移，读取时按过期处理。
 */
object VodProgressStore {
    private const val PREF_NAME = "vod_progress"
    private const val KEY_PREFIX = "pos_"
    private const val DAY_PREFIX = "day_"
    /** 小于该位置不保存，避免刚起播就把续播记录写成几秒 */
    private const val MIN_SAVE_MS = 5_000L

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(ApiClient.userSpName(PREF_NAME), Context.MODE_PRIVATE)

    private fun todayStr(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    /**
     * 读取当天有效的播放位置（ms）。无记录、记录非当天（含旧版无日期数据）返回 0 并清除。
     */
    fun load(context: Context, id: String): Long {
        val sp = prefs(context)
        if (sp.getString(DAY_PREFIX + id, null) != todayStr()) {
            if (sp.contains(KEY_PREFIX + id) || sp.contains(DAY_PREFIX + id)) clear(context, id)
            return 0L
        }
        return try {
            sp.getLong(KEY_PREFIX + id, 0L)
        } catch (_: ClassCastException) {
            // 兼容早期非 Long 类型的脏数据
            clear(context, id)
            0L
        }
    }

    /** 保存播放位置（ms），同时写入当天日期。过短（<5s）不保存。 */
    fun save(context: Context, id: String, positionMs: Long) {
        if (positionMs < MIN_SAVE_MS) return
        prefs(context).edit()
            .putLong(KEY_PREFIX + id, positionMs)
            .putString(DAY_PREFIX + id, todayStr())
            .apply()
    }

    /** 播放完成后清除记录，下次从头开始 */
    fun clear(context: Context, id: String) {
        prefs(context).edit()
            .remove(KEY_PREFIX + id)
            .remove(DAY_PREFIX + id)
            .apply()
    }

    /**
     * 清理所有非当天的历史记录，避免 SharedPreferences 随使用天数无限膨胀。
     * 在 PlayerEngine 创建时调用一次即可；同一天内的记录全部保留。
     */
    fun pruneStale(context: Context) {
        val sp = prefs(context)
        val today = todayStr()
        val snapshot = sp.all
        val ed = sp.edit()
        var changed = false
        for (key in snapshot.keys) {
            when {
                key.startsWith(DAY_PREFIX) -> {
                    val id = key.removePrefix(DAY_PREFIX)
                    // 日期已过期，或只剩日期没有位置（孤儿键）
                    if (snapshot[key] != today || !snapshot.containsKey(KEY_PREFIX + id)) {
                        ed.remove(key)
                        ed.remove(KEY_PREFIX + id)
                        changed = true
                    }
                }
                key.startsWith(KEY_PREFIX) -> {
                    // 旧版记录 / 脏数据：有位置但没有当天的日期键，一律作废
                    val id = key.removePrefix(KEY_PREFIX)
                    if (snapshot[DAY_PREFIX + id] != today) {
                        ed.remove(key)
                        changed = true
                    }
                }
            }
        }
        if (changed) ed.apply()
    }

    /** 把毫秒格式化成用于续播提示的 mm:ss / H:mm:ss */
    fun formatPosition(positionMs: Long): String {
        val total = positionMs / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }
}
