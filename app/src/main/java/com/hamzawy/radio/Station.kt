package com.hamzawy.radio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * One radio station. [urls] are tried in order: if the first stream is down the player
 * moves on to the next one by itself.
 */
data class Station(
    val id: String,
    val name: String,
    val subtitle: String,
    val badge: String,
    val category: String,
    val urls: List<String>,
    val custom: Boolean = false
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("name", name)
        o.put("subtitle", subtitle)
        o.put("badge", badge)
        o.put("category", category)
        o.put("urls", JSONArray(urls))
        return o
    }

    companion object {
        fun fromJson(o: JSONObject, custom: Boolean): Station? {
            val id = o.optString("id")
            val name = o.optString("name")
            val arr = o.optJSONArray("urls") ?: return null
            val urls = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val u = arr.optString(i).trim()
                if (u.startsWith("http://") || u.startsWith("https://")) urls.add(u)
            }
            if (id.isEmpty() || name.isEmpty() || urls.isEmpty()) return null
            return Station(
                id = id,
                name = name,
                subtitle = o.optString("subtitle"),
                badge = o.optString("badge"),
                category = o.optString("category", "other"),
                urls = urls,
                custom = custom
            )
        }
    }
}

/** Display order and titles of the station groups on the home screen. */
object Categories {
    private val order = listOf("news", "music", "quran", "other", "custom")

    fun rank(category: String): Int {
        val i = order.indexOf(category)
        return if (i < 0) order.size else i
    }

    fun title(ctx: Context, category: String): String {
        val res = when (category) {
            "news" -> R.string.cat_news
            "music" -> R.string.cat_music
            "quran" -> R.string.cat_quran
            "custom" -> R.string.cat_custom
            else -> R.string.cat_other
        }
        return ctx.getString(res)
    }
}
