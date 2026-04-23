package com.wllo.bsmaintain.camera

import android.content.Context
import android.location.Location
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.wllo.bsmaintain.Constants
import java.io.File

/**
 * Reads the cached site_info.json (fetched from Drive by [sync.StationSyncWorker] /
 * StationCameraActivity) and exposes coordinate→siteName lookups.
 *
 * JSON key names vary — Excel VSTO export uses 中/英 mixed column names. We detect by
 * keyword matching (same pattern as StationCameraActivity.findNearestStation).
 */
object StationRepository {

    private const val TAG = "StationRepository"

    // site_info.xlsx has multiple CJK columns that match the naïve 「contains 名/站」 heuristic
    // — `台名` is a long verbose "台名|地址|備註" concatenation that silently won a
    // key-ordering race, so photos ended up grouped under strings that looked like 亂碼.
    // Prefer exact canonical keys; fall back to CJK fuzzy match but exclude known bad ones.
    private val PREFERRED_NAME_KEYS = listOf("siteName", "站名", "site_name")
    private val EXCLUDED_NAME_TOKENS = listOf("編號", "Number", "組態", "台名")

    fun pickNameKey(obj: JsonObject): String? =
        PREFERRED_NAME_KEYS.firstOrNull { obj.has(it) }
            ?: obj.keySet().firstOrNull { k ->
                (k.contains("名") || k.contains("name", ignoreCase = true)) &&
                    EXCLUDED_NAME_TOKENS.none { k.contains(it, ignoreCase = true) }
            }

    data class Station(val siteName: String, val lat: Double, val lng: Double)

    fun load(context: Context): List<Station> {
        val f = File(context.cacheDir, "drive_cache_${Constants.DRIVE_FILE_ID}.json")
        if (!f.exists()) return emptyList()
        val root = try {
            Gson().fromJson(f.readText(), JsonElement::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "parse cache failed", e)
            return emptyList()
        }
        if (!root.isJsonArray) return emptyList()

        val out = mutableListOf<Station>()
        for (el in root.asJsonArray) {
            val obj = el.asJsonObject
            val latKey = obj.keySet().find {
                it.contains("緯") || it.contains("lat", true) || it.contains("latitude", true)
            } ?: continue
            val lngKey = obj.keySet().find {
                it.contains("經") || it.contains("lng", true) || it.contains("longitude", true)
            } ?: continue
            val nameKey = pickNameKey(obj) ?: continue
            val lat = obj.get(latKey)?.asDouble ?: continue
            val lng = obj.get(lngKey)?.asDouble ?: continue
            val name = obj.get(nameKey)?.asString ?: continue
            if (name.isNotBlank()) out.add(Station(name, lat, lng))
        }
        return out
    }

    fun findNearest(lat: Double, lng: Double, maxMeters: Float, stations: List<Station>): Station? {
        if (stations.isEmpty()) return null
        var best: Station? = null
        var bestDist = Float.MAX_VALUE
        val distOut = FloatArray(1)
        for (s in stations) {
            Location.distanceBetween(lat, lng, s.lat, s.lng, distOut)
            if (distOut[0] < bestDist) {
                bestDist = distOut[0]
                best = s
            }
        }
        return if (best != null && bestDist <= maxMeters) best else null
    }
}
