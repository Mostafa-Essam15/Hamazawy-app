package com.hamzawy.radio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Built-in stations come from assets/stations.json; stations added from the phone are stored in preferences. */
object StationRepository {

    private const val PREFS = "stations"
    private const val KEY_CUSTOM = "custom"

    fun load(ctx: Context): List<Station> {
        val all = ArrayList<Station>()
        try {
            val text = ctx.assets.open("stations.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONObject(text).getJSONArray("stations")
            for (i in 0 until arr.length()) {
                val s = Station.fromJson(arr.getJSONObject(i), false)
                if (s != null) all.add(s)
            }
        } catch (e: Exception) {
            // A broken stations.json must never crash the app; the user's own stations still show up.
        }
        all.addAll(loadCustom(ctx))
        return all.sortedBy { Categories.rank(it.category) }
    }

    fun loadCustom(ctx: Context): List<Station> {
        val raw = prefs(ctx).getString(KEY_CUSTOM, null) ?: return emptyList()
        val out = ArrayList<Station>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val s = Station.fromJson(arr.getJSONObject(i), true)
                if (s != null) out.add(s)
            }
        } catch (e: Exception) {
            // Ignore a corrupt entry list.
        }
        return out
    }

    fun addCustom(ctx: Context, name: String, url: String): Station {
        val list = ArrayList<Station>(loadCustom(ctx))
        val station = Station(
            id = "c" + System.currentTimeMillis(),
            name = name.trim(),
            subtitle = ctx.getString(R.string.custom_station_subtitle),
            badge = "",
            category = "custom",
            urls = listOf(url.trim()),
            custom = true
        )
        list.add(station)
        saveCustom(ctx, list)
        return station
    }

    fun removeCustom(ctx: Context, id: String) {
        saveCustom(ctx, loadCustom(ctx).filter { it.id != id })
    }

    private fun saveCustom(ctx: Context, list: List<Station>) {
        val arr = JSONArray()
        for (s in list) arr.put(s.toJson())
        prefs(ctx).edit().putString(KEY_CUSTOM, arr.toString()).apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
