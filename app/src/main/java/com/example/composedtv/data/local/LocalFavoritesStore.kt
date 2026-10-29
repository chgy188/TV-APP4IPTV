package com.example.composedtv.data.local

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 本地收藏存储：用于「未登录 / 外部 App 唤起播放」场景下保存用户收藏的频道。
 *
 * 选择本地而非后端：外部播放为 guest 态（多未登录），后端收藏需鉴权；
 * 本地存储零依赖、可离线，并与后端收藏在「收藏」分类中合并展示。
 */
data class LocalFavorite(
    val name: String,
    val url: String,
    val country: String = "",
    val ua: String = "",
    val rf: String = ""
)

object LocalFavoritesStore {

    private const val FILE_NAME = "local_favorites.json"

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE_NAME)

    fun getAll(ctx: Context): List<LocalFavorite> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                LocalFavorite(
                    name = o.optString("name", ""),
                    url = o.optString("url", ""),
                    country = o.optString("country", ""),
                    ua = o.optString("ua", ""),
                    rf = o.optString("rf", "")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun hasAny(ctx: Context): Boolean = getAll(ctx).isNotEmpty()

    /** 保存；若同一 url 已存在则更新其名称（避免重复条目） */
    fun add(ctx: Context, fav: LocalFavorite) {
        val list = getAll(ctx).toMutableList()
        val idx = list.indexOfFirst { it.url == fav.url }
        if (idx >= 0) list[idx] = fav else list.add(fav)
        write(ctx, list)
    }

    fun remove(ctx: Context, url: String) {
        write(ctx, getAll(ctx).filter { it.url != url })
    }

    private fun write(ctx: Context, list: List<LocalFavorite>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { f ->
                arr.put(JSONObject().apply {
                    put("name", f.name)
                    put("url", f.url)
                    put("country", f.country)
                    put("ua", f.ua)
                    put("rf", f.rf)
                })
            }
            file(ctx).writeText(arr.toString())
        }
    }
}
