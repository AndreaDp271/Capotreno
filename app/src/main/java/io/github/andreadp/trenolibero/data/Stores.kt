package io.github.andreadp.trenolibero.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * Anagrafica delle stazioni con coordinate, scaricata per regione (1..22)
 * e salvata in cache per 30 giorni.
 */
class StationIndex(context: Context) {
    private val file = File(context.filesDir, "stations.json")
    private val mutex = Mutex()
    private var cache: Map<String, StationGeo>? = null

    suspend fun all(): List<StationGeo> = load().values.toList()

    suspend fun get(id: String): StationGeo? {
        load()[id]?.let { return it }
        return runCatching { ViaggiaTrenoApi.stationDetail(id) }.getOrNull()?.takeIf { it.hasCoords }
    }

    private suspend fun load(): Map<String, StationGeo> = mutex.withLock {
        cache?.let { return it }
        val serializer = ListSerializer(StationGeo.serializer())
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < 30L * 24 * 3600 * 1000
        val list = if (fresh) {
            withContext(Dispatchers.IO) {
                runCatching { ViaggiaTrenoApi.json.decodeFromString(serializer, file.readText()) }.getOrNull()
            }
        } else null
        val stations = list ?: download().also { downloaded ->
            withContext(Dispatchers.IO) { file.writeText(ViaggiaTrenoApi.json.encodeToString(serializer, downloaded)) }
        }
        stations.associateBy { it.codiceStazione }.also { cache = it }
    }

    private suspend fun download(): List<StationGeo> = coroutineScope {
        val sem = Semaphore(6)
        (1..22).map { r -> async { sem.withPermit { ViaggiaTrenoApi.stationsOfRegion(r) } } }
            .awaitAll()
            .flatten()
            .filter { it.hasCoords }
            .distinctBy { it.codiceStazione }
    }
}

/** Preferiti salvati in locale (treni per numero+origine, stazioni per codice). */
class Favorites(context: Context) {
    private val prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)

    data class Train(val number: String, val originId: String, val label: String)

    private val _trains = MutableStateFlow(readTrains())
    val trains: StateFlow<List<Train>> = _trains
    private val _stations = MutableStateFlow(readStations())
    val stations: StateFlow<List<StationRef>> = _stations

    /** Linea (tratta) della mappa salvata con il cuoricino: avvisi di circolazione sulle sue località. */
    data class Line(val region: Int, val ab: Int, val ba: Int, val name: String) {
        val id get() = "$region|$ab|$ba"
        /** Località della linea per cercarla negli avvisi: i due capi del nome ("Padova - Venezia"). */
        val places: Set<String> get() = name.split(Regex("[-–—→>/]")).mapNotNull { placeKey(it.trim()) }.toSet()
    }

    private val _lines = MutableStateFlow(readLines())
    val lines: StateFlow<List<Line>> = _lines

    private fun readLines() = prefs.getStringSet("lines", emptySet())!!.mapNotNull {
        val p = it.split("|", limit = 4)
        val r = p.getOrNull(0)?.toIntOrNull(); val a = p.getOrNull(1)?.toIntOrNull(); val b = p.getOrNull(2)?.toIntOrNull()
        if (r != null && a != null && b != null && p.size == 4) Line(r, a, b, p[3]) else null
    }.sortedBy { it.name }

    fun isLineFav(region: Int, ab: Int, ba: Int) = _lines.value.any { it.id == "$region|$ab|$ba" }

    fun toggleLine(l: Line) {
        val set = prefs.getStringSet("lines", emptySet())!!.toMutableSet()
        val existing = set.firstOrNull { it.startsWith("${l.id}|") }
        if (existing != null) set.remove(existing) else set.add("${l.id}|${l.name}")
        prefs.edit().putStringSet("lines", set).apply()
        _lines.value = readLines()
    }

    private fun readTrains() = prefs.getStringSet("trains", emptySet())!!.mapNotNull {
        val p = it.split("|", limit = 3)
        if (p.size == 3) Train(p[0], p[1], p[2]) else null
    }.sortedBy { it.number.toIntOrNull() ?: Int.MAX_VALUE }

    private fun readStations() = prefs.getStringSet("stations", emptySet())!!.mapNotNull {
        val p = it.split("|", limit = 2)
        if (p.size == 2) StationRef(p[0], p[1]) else null
    }.sortedBy { it.name }

    fun isTrainFav(number: String, originId: String) = _trains.value.any { it.number == number && it.originId == originId }
    fun isStationFav(id: String) = _stations.value.any { it.id == id }

    fun toggleTrain(t: Train) {
        val set = prefs.getStringSet("trains", emptySet())!!.toMutableSet()
        val existing = set.firstOrNull { it.startsWith("${t.number}|${t.originId}|") }
        if (existing != null) set.remove(existing) else set.add("${t.number}|${t.originId}|${t.label}")
        prefs.edit().putStringSet("trains", set).apply()
        _trains.value = readTrains()
    }

    fun toggleStation(s: StationRef) {
        val set = prefs.getStringSet("stations", emptySet())!!.toMutableSet()
        val existing = set.firstOrNull { it.startsWith("${s.id}|") }
        if (existing != null) set.remove(existing) else set.add("${s.id}|${s.name}")
        prefs.edit().putStringSet("stations", set).apply()
        _stations.value = readStations()
    }
}

/** Contenitore delle dipendenze dell'app. */
object Graph {
    lateinit var stations: StationIndex
    lateinit var favorites: Favorites
    lateinit var tickets: TicketStore
    lateinit var subscriptions: SubscriptionStore
    private lateinit var app: Context

    fun init(context: Context) {
        if (::stations.isInitialized) return
        app = context.applicationContext
        stations = StationIndex(app)
        favorites = Favorites(app)
        tickets = TicketStore(app)
        subscriptions = SubscriptionStore(app)
    }

    private val railMutex = Mutex()
    private var railNetwork: RailNetwork? = null

    /** Rete ferroviaria OSM inclusa negli asset, caricata alla prima richiesta. */
    suspend fun rail(): RailNetwork = railMutex.withLock {
        railNetwork ?: withContext(Dispatchers.IO) {
            app.assets.open("rail_graph.bin").use { RailNetwork.load(it) }
        }.also { railNetwork = it }
    }
}
