package io.github.andreadp.trenolibero.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

@Serializable
data class LatLon(val lat: Double, val lon: Double)

/**
 * Traccia del percorso sui binari reali di OpenStreetMap, calcolata dal servizio BRouter
 * (profilo "rail"). I tracciati sono messi in cache su disco perché non cambiano di giorno in giorno.
 */
class RailRouter(context: Context) {
    private val dir = File(context.cacheDir, "routes").apply { mkdirs() }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Restituisce un tratto di binario per ogni coppia di fermate consecutive
     * (null dove il calcolo non è riuscito: lì si disegna la linea retta).
     */
    suspend fun route(stops: List<LatLon>): List<List<LatLon>?> {
        if (stops.size < 2) return emptyList()
        val key = stops.joinToString(";") { String.format(Locale.US, "%.4f,%.4f", it.lat, it.lon) }.hashCode()
        val file = File(dir, "r$key.json")
        val ser = ListSerializer(ListSerializer(LatLon.serializer()))
        val cached = withContext(Dispatchers.IO) {
            if (file.exists()) runCatching { ViaggiaTrenoApi.json.decodeFromString(ser, file.readText()) }.getOrNull() else null
        }
        if (cached != null) return cached.map { it.ifEmpty { null } }

        // Blocchi di al massimo CHUNK fermate, sovrapposti di una per continuità
        val segments = MutableList<List<LatLon>?>(stops.size - 1) { null }
        var start = 0
        var complete = true
        while (start < stops.size - 1) {
            val end = minOf(start + CHUNK, stops.size - 1)
            val legs = runCatching { request(stops.subList(start, end + 1)) }.getOrNull()
            if (legs != null) legs.forEachIndexed { i, leg -> segments[start + i] = leg } else complete = false
            start = end
            if (start < stops.size - 1) delay(400) // gentile con il server pubblico
        }
        if (complete || segments.any { it != null }) {
            withContext(Dispatchers.IO) {
                runCatching { file.writeText(ViaggiaTrenoApi.json.encodeToString(ser, segments.map { it ?: emptyList() })) }
            }
        }
        return segments
    }

    /** Una richiesta BRouter con più punti di passaggio; divide la traccia in tratti tra fermate. */
    private suspend fun request(points: List<LatLon>): List<List<LatLon>?> = withContext(Dispatchers.IO) {
        val lonlats = points.joinToString("|") { String.format(Locale.US, "%.6f,%.6f", it.lon, it.lat) }
        val url = "https://brouter.de/brouter?lonlats=$lonlats&profile=rail&alternativeidx=0&format=geojson"
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        val body = client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ApiException("BRouter ${r.code}")
            r.body!!.string()
        }
        val coords = ViaggiaTrenoApi.json.parseToJsonElement(body).jsonObject["features"]!!.jsonArray[0]
            .jsonObject["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
            .map { c -> (c as JsonArray).let { LatLon(it[1].jsonPrimitive.double, it[0].jsonPrimitive.double) } }
        splitAtStops(coords, points)
    }

    companion object {
        private const val CHUNK = 12
        private const val USER_AGENT = "TrenoLibero/1.0 (Android; https://f-droid.org)"

        /** Taglia la traccia nei punti più vicini a ciascuna fermata (in ordine). */
        fun splitAtStops(track: List<LatLon>, stops: List<LatLon>): List<List<LatLon>?> {
            val cuts = IntArray(stops.size)
            var from = 0
            stops.forEachIndexed { i, s ->
                var best = from
                var bestD = Double.MAX_VALUE
                for (j in from until track.size) {
                    val d = DetectionLocator.distanceKm(s.lat, s.lon, track[j].lat, track[j].lon)
                    if (d < bestD) { bestD = d; best = j }
                }
                cuts[i] = best
                from = best
            }
            return (0 until stops.size - 1).map { i ->
                val seg = track.subList(cuts[i], cuts[i + 1] + 1)
                if (seg.size >= 2) seg.toList() else null
            }
        }
    }
}
