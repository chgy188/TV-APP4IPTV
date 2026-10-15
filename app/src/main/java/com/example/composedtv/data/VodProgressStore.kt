package com.example.composedtv.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 点播（VOD）播放进度持久化：按内容 id（外部视频即其 Uri 字符串）记录上次播放位置，
 * 实现「同一个片子记忆继续播放」。采用普通 SharedPreferences，无额外依赖。
 */
object VodProgressStore {
    private const val PREF_NAME = "vod_progress"
    private const val KEY_PREFIX = "pos_"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** 读取已保存的播放位置（ms），无记录返回 0 */
    fun load(context: Context, id: String): Long =
        prefs(context).getLong(KEY_PREFIX + id, 0L)

    /** 保存播放位置（ms）。过短（<5s）不保存，避免刚打开就记录 0 把续播误触发 */
    fun save(context: Context, id: String, positionMs: Long) {
        if (positionMs < 5_000) return
        prefs(context).edit().putLong(KEY_PREFIX + id, positionMs).apply()
    }

    /** 播放完成后清除记录，下次从头开始 */
    fun clear(context: Context, id: String) {
        prefs(context).edit().remove(KEY_PREFIX + id).apply()
    }
}
