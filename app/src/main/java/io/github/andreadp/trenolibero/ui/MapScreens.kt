package io.github.andreadp.trenolibero.ui

import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.andreadp.trenolibero.R
import io.github.andreadp.trenolibero.data.DetectionHistory
import io.github.andreadp.trenolibero.data.DetectionLocator
import io.github.andreadp.trenolibero.data.Graph
import io.github.andreadp.trenolibero.data.LatLon
import io.github.andreadp.trenolibero.data.Precision
import io.github.andreadp.trenolibero.data.projectOnPolyline
import io.github.andreadp.trenolibero.data.RailNetwork
import io.github.andreadp.trenolibero.data.StationGeo
import io.github.andreadp.trenolibero.data.Tratta
import io.github.andreadp.trenolibero.data.TrainOnMap
import io.github.andreadp.trenolibero.data.TrainsOnMap
import io.github.andreadp.trenolibero.data.ViaggiaTrenoApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.simplefastpoint.LabelledGeoPoint
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlay
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlayOptions
import org.osmdroid.views.overlay.simplefastpoint.SimplePointTheme

/** Colori fissi della mappa: ben leggibili sopra lo stile OSM standard. */
object MapColors {
    const val STATION = 0xFF0D2F8C.toInt()       // blu scuro
    const val ROUTE = 0xFF0D47A1.toInt()         // blu
    const val ROUTE_TODO = 0xFF9E9E9E.toInt()    // grigio: tratto ancora da percorrere
    const val TRANSIT = 0xFFFF8F00.toInt()       // arancione: tratto in corso (come nella schermata del treno)
    const val LINE_BUSY = 0xFFE65100.toInt()     // arancione: linea con treni in transito
    const val LINE_FREE = 0xFF757575.toInt()     // grigio
}

/** MapView di osmdroid legata al ciclo di vita della schermata. */
@Composable
private fun rememberMapView(): MapView {
    val ctx = LocalContext.current
    val map = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER) // zoom con i pulsanti laterali
            isTilesScaledToDpi = true
            minZoomLevel = 5.0
            controller.setZoom(6.0)
            controller.setCenter(GeoPoint(42.3, 12.6))
        }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        map.onResume()
        onDispose {
            owner.lifecycle.removeObserver(obs)
            map.onPause()
            map.onDetach()
        }
    }
    return map
}

@Composable
private fun OsmAttribution(modifier: Modifier) {
    Text(
        "© OpenStreetMap contributors",
        style = MaterialTheme.typography.labelSmall,
        color = Color.Black,
        modifier = modifier.background(Color.White.copy(alpha = 0.8f)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// ---------------- Mappa stazioni + linee ----------------

private data class Viewport(val zoom: Double, val box: BoundingBox)
private data class SelectedLine(val region: Int, val tratta: Tratta, val name: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationsMapScreen(nav: Nav, focusId: String?) {
    val state = rememberLoader(Unit) { Graph.stations.all() }
    val map = rememberMapView()
    var selected by remember { mutableStateOf<StationGeo?>(null) }
    var selectedLine by remember { mutableStateOf<SelectedLine?>(null) }
    var viewport by remember { mutableStateOf<Viewport?>(if (focusId != null) Viewport(14.0, BoundingBox()) else null) }
    var focusDone by remember { mutableStateOf(false) }
    // Linee nazionali (regione 0), aggiornate ogni 2 minuti
    val national = rememberLoader(Unit, refreshMs = 120_000) { ViaggiaTrenoApi.lines(0) }
    var regional by remember { mutableStateOf<Map<Int, List<Tratta>>>(emptyMap()) }
    val regionalTime = remember { mutableMapOf<Int, Long>() }
    val lineOverlays = remember { mutableListOf<Overlay>() }

    DisposableEffect(map) {
        val l = object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                viewport = Viewport(map.zoomLevelDouble, map.boundingBox); return false
            }
            override fun onZoom(event: ZoomEvent?): Boolean {
                viewport = Viewport(map.zoomLevelDouble, map.boundingBox); return false
            }
        }
        map.addMapListener(l)
        onDispose { map.removeMapListener(l) }
    }

    // Tratte regionali di dettaglio per le regioni inquadrate (da zoom 9)
    LaunchedEffect(viewport, state.data) {
        val v = viewport ?: return@LaunchedEffect
        val stations = state.data ?: return@LaunchedEffect
        delay(500) // debounce
        if (v.zoom < 9) return@LaunchedEffect
        val regions = stations.asSequence().filter { v.box.contains(it.lat, it.lon) }.mapNotNull { it.codReg }
            .filter { it in 1..22 }.toSet()
        val now = System.currentTimeMillis()
        val updated = regional.toMutableMap()
        for (r in regions) {
            if (now - (regionalTime[r] ?: 0) < 120_000) continue
            runCatching { ViaggiaTrenoApi.lines(r) }.onSuccess { updated[r] = it; regionalTime[r] = now }
        }
        if (updated != regional) regional = updated
    }

    // Disegno delle linee (sotto le stazioni), sul tracciato reale dei binari OSM
    val showRegional = (viewport?.zoom ?: 6.0) >= 9
    LaunchedEffect(national.data, regional, showRegional, state.data) {
        val names = state.data?.associateBy({ it.codiceStazione }, { it.name }).orEmpty()
        val sets = buildList {
            national.data?.let { add(0 to it) }
            if (showRegional) regional.forEach { (r, l) -> add(r to l) }
        }
        val rail = runCatching { Graph.rail() }.getOrNull()
        val shapes = withContext(Dispatchers.Default) {
            sets.flatMap { (_, list) -> list }.associate { t ->
                val a = LatLon(t.latitudineA, t.longitudineA)
                val b = LatLon(t.latitudineB, t.longitudineB)
                t to (rail?.path(t.nodoA, a, t.nodoB, b) ?: listOf(a, b)).map { GeoPoint(it.lat, it.lon) }
            }
        }
        map.overlays.removeAll(lineOverlays.toSet())
        lineOverlays.clear()
        for ((region, list) in sets) for (t in list) {
            val p = Polyline(map).apply {
                setPoints(shapes[t])
                outlinePaint.color = if (t.occupata && !showRegional) MapColors.LINE_BUSY else MapColors.LINE_FREE
                outlinePaint.strokeWidth = 7f
                outlinePaint.strokeCap = Paint.Cap.ROUND
                infoWindow = null
            }
            val name = listOfNotNull(names[t.nodoA], names[t.nodoB]).joinToString(" – ")
            p.setOnClickListener { _, _, _ -> selected = null; selectedLine = SelectedLine(region, t, name); true }
            lineOverlays.add(p)
        }
        map.overlays.addAll(0, lineOverlays)
        map.invalidate()
    }

    // Da zoom 9: posizione dei treni in transito nella zona inquadrata, colorando solo il tratto
    // tra l'ultimo rilevamento e la prossima fermata (non l'intera linea)
    var trains by remember { mutableStateOf<List<TrainOnMap>>(emptyList()) }
    var selectedTrain by remember { mutableStateOf<TrainOnMap?>(null) }
    var trainsLoading by remember { mutableStateOf(false) }
    val trainOverlays = remember { mutableListOf<Overlay>() }
    val ctx = LocalContext.current
    val area = viewport?.takeIf { showRegional }?.let { v ->
        // area arrotondata: piccoli spostamenti non ricaricano i treni
        listOf(v.zoom.toInt(), (v.box.centerLatitude * 20).toInt(), (v.box.centerLongitude * 20).toInt())
    }
    LaunchedEffect(area, regional, national.data != null) {
        if (area == null) { trains = emptyList(); return@LaunchedEffect }
        delay(700)
        val index = state.data?.associateBy { it.codiceStazione } ?: return@LaunchedEffect
        while (true) {
            val box = viewport?.box ?: break
            val visible = buildList {
                national.data?.forEach { add(0 to it) }
                regional.forEach { (r, l) -> l.forEach { add(r to it) } }
            }.filter { (_, t) ->
                box.contains(t.latitudineA, t.longitudineA) || box.contains(t.latitudineB, t.longitudineB)
            }
            trainsLoading = true
            val rail = runCatching { Graph.rail() }.getOrNull()
            trains = withContext(Dispatchers.Default) { TrainsOnMap.load(visible, index, rail) }
            trainsLoading = false
            delay(90_000)
        }
    }
    LaunchedEffect(trains) {
        map.overlays.removeAll(trainOverlays.toSet())
        trainOverlays.clear()
        val segments = trains.map { tr ->
            Polyline(map).apply {
                setPoints(tr.segment.map { GeoPoint(it.lat, it.lon) })
                outlinePaint.color = MapColors.LINE_BUSY
                outlinePaint.strokeWidth = 10f
                outlinePaint.strokeCap = Paint.Cap.ROUND
                infoWindow = null
                setOnClickListener { _, _, _ -> selected = null; selectedLine = null; selectedTrain = tr; true }
            }
        }
        val markers = trains.map { tr ->
            Marker(map).apply {
                // il tratto parte dal punto proiettato sui binari
                val at = tr.segment.firstOrNull() ?: LatLon(tr.detection.lat, tr.detection.lon)
                position = GeoPoint(at.lat, at.lon)
                icon = TrainMarker.icon(ctx, TrainMarker.bearingAlong(tr.segment))
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                infoWindow = null
                setOnMarkerClickListener { _, _ -> selected = null; selectedLine = null; selectedTrain = tr; true }
            }
        }
        trainOverlays.addAll(segments)
        trainOverlays.addAll(markers)
        // sopra le linee, sotto le stazioni e i segnaposto
        map.overlays.addAll(lineOverlays.size.coerceAtMost(map.overlays.size), segments)
        map.overlays.addAll(markers)
        map.invalidate()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mappa") },
                navigationIcon = {
                    if (focusId != null) IconButton(onClick = { nav.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro")
                    }
                },
                actions = { IconButton(onClick = national.reload) { Icon(Icons.Default.Refresh, "Aggiorna linee") } },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            AndroidView(factory = { map }, modifier = Modifier.fillMaxSize())
            OsmAttribution(Modifier.align(Alignment.BottomStart))
            MapControls(map, Modifier.align(Alignment.CenterEnd).padding(end = 12.dp))
            Legend(Modifier.align(Alignment.TopStart).padding(8.dp), detailed = showRegional, loading = trainsLoading)

            val stations = state.data
            // Da lontano solo i nodi delle linee nazionali, da vicino tutte le stazioni
            val detailed = (viewport?.zoom ?: 6.0) >= 8.5
            val hubs = national.data?.flatMap { listOfNotNull(it.nodoA, it.nodoB) }?.toSet()
            LaunchedEffect(stations, detailed, hubs) {
                if (stations == null) return@LaunchedEffect
                val shown = if (detailed || hubs == null) stations else stations.filter { it.codiceStazione in hubs }
                map.overlays.removeAll { it is SimpleFastPointOverlay }
                val points = shown.map { LabelledGeoPoint(it.lat, it.lon, it.name) }
                val opts = SimpleFastPointOverlayOptions.getDefaultStyle()
                    .setAlgorithm(SimpleFastPointOverlayOptions.RenderingAlgorithm.MAXIMUM_OPTIMIZATION)
                    .setRadius(if (detailed) 8f else 7f)
                    .setIsClickable(true)
                    .setCellSize(14)
                    .setSymbol(SimpleFastPointOverlayOptions.Shape.CIRCLE)
                    .setPointStyle(Paint().apply { color = MapColors.STATION; style = Paint.Style.FILL; isAntiAlias = true })
                    .setSelectedPointStyle(Paint().apply { color = MapColors.LINE_BUSY; style = Paint.Style.FILL; isAntiAlias = true })
                    .setTextStyle(Paint().apply {
                        color = MapColors.STATION; textSize = 30f; textAlign = Paint.Align.CENTER; isAntiAlias = true
                        isFakeBoldText = true
                    })
                    .setMinZoomShowLabels(11)
                val overlay = SimpleFastPointOverlay(SimplePointTheme(points, true), opts)
                overlay.setOnClickListener { _, i -> selectedLine = null; selected = shown[i] }
                map.overlays.add(overlay)
                if (focusId != null && !focusDone) {
                    focusDone = true
                    val i = shown.indexOfFirst { it.codiceStazione == focusId }
                    if (i >= 0) {
                        selected = shown[i]
                        overlay.selectedPoint = i
                        map.controller.setZoom(14.0)
                        map.controller.setCenter(GeoPoint(shown[i].lat, shown[i].lon))
                    }
                }
                map.invalidate()
            }

            when {
                stations == null && state.error != null -> Card(Modifier.align(Alignment.Center).padding(24.dp)) {
                    ErrorBox(state.error, onRetry = state.reload)
                }
                stations == null -> Card(Modifier.align(Alignment.Center)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.padding(end = 12.dp))
                        Text("Caricamento stazioni…")
                    }
                }
            }

            MapSheet(selected, Modifier.align(Alignment.BottomCenter)) { s ->
                SheetCard(Color(MapColors.STATION), "Stazione", s.name, onClose = { selected = null }, icon = R.drawable.ic_tab_station) {
                    androidx.compose.material3.FilledTonalButton(
                        onClick = { nav.station(s.codiceStazione, s.name) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Tabellone partenze e arrivi") }
                }
            }
            selectedLine?.let { sl -> LineCard(nav, sl, Modifier.align(Alignment.BottomCenter)) { selectedLine = null } }
            MapSheet(selectedTrain, Modifier.align(Alignment.BottomCenter)) { tr ->
                SheetCard(
                    StateColors.transit, tr.title, "→ ${tr.destination}", onClose = { selectedTrain = null },
                    icon = R.drawable.ic_tab_train,
                    subtitle = "Rilevato a ${tr.detection.detection.name} alle ${fmtTime(tr.detection.detection.time)}",
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("Prossima fermata", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(tr.nextStop, fontWeight = FontWeight.Bold)
                        }
                        DelayBadge(tr.delay)
                    }
                    androidx.compose.material3.FilledTonalButton(
                        onClick = { nav.train(tr.number, tr.originId, tr.dateMs) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Apri il treno") }
                }
            }
        }
    }
}

@Composable
private fun RouteLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
        LegendRow(Color(MapColors.ROUTE), "percorso")
        DashedLegend()
        LegendRow(Color(MapColors.ROUTE_TODO), "da percorrere")
    }
}

@Composable
private fun DashedLegend() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(3) { Box(Modifier.size(width = 5.dp, height = 4.dp).background(Color(MapColors.TRANSIT))) }
        }
        Text("in transito", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun Legend(modifier: Modifier, detailed: Boolean, loading: Boolean) {
    Card(modifier) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (detailed) {
                LegendRow(Color(MapColors.LINE_BUSY), "Treno: ultimo rilevamento → prossima fermata")
                LegendRow(Color(MapColors.LINE_FREE), "Linea")
            } else {
                LegendRow(Color(MapColors.LINE_BUSY), "Linea con treni in transito")
                LegendRow(Color(MapColors.LINE_FREE), "Linea libera")
                Text("Avvicina per vedere i singoli treni", style = MaterialTheme.typography.labelSmall)
            }
            LegendRow(Color(MapColors.STATION), "Stazione", dot = true)
            if (loading) Text("Aggiornamento treni…", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun LegendRow(color: Color, label: String, dot: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.size(width = if (dot) 10.dp else 18.dp, height = if (dot) 10.dp else 4.dp)
                .background(color, if (dot) CircleShape else MaterialTheme.shapes.extraSmall)
        )
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/** Scheda di una linea toccata: quanti treni la percorrono ora, per direzione. */
@Composable
private fun LineCard(nav: Nav, sl: SelectedLine, modifier: Modifier, onClose: () -> Unit) {
    val t = sl.tratta
    val state = rememberLoader(sl.region, t.trattaAB, t.trattaBA) { ViaggiaTrenoApi.lineTrains(sl.region, t.trattaAB, t.trattaBA) }
    Card(modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.data?.firstOrNull()?.tratta ?: sl.name.ifEmpty { "Linea" },
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Chiudi") }
            }
            val dirs = state.data
            when {
                dirs != null -> {
                    if (dirs.isEmpty()) Text("Nessun dato per questa linea")
                    dirs.forEach { d ->
                        Text("${d.tratta}: ${d.treni.size} ${if (d.treni.size == 1) "treno" else "treni"} in transito")
                    }
                    if (dirs.any { it.treni.isNotEmpty() }) {
                        TextButton(onClick = { nav.line(sl.region, t.trattaAB, t.trattaBA) }) { Text("Vedi i treni") }
                    }
                }
                state.error != null -> Text(state.error, color = MaterialTheme.colorScheme.error)
                else -> CircularProgressIndicator(Modifier.size(24.dp))
            }
        }
    }
}

// ---------------- Mappa percorso treno ----------------

/** Frecce, Italo ed Eurostar usano l'alta velocità; regionali e Intercity no. */
fun railProfile(title: String): RailNetwork.Profile {
    val cat = title.trim().substringBefore(' ').uppercase()
    return when (cat) {
        "FR", "FA", "ITA", "AV", "ES", "ES*" -> RailNetwork.Profile.HIGH_SPEED
        else -> RailNetwork.Profile.REGIONAL
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainMapScreen(nav: Nav, number: String, originId: String, dateMs: Long) {
    val state = rememberLoader(number, originId, dateMs, refreshMs = 60_000) {
        val (m, a) = resolveTrain(number, originId, dateMs)
        val stops = a.fermate.map { f -> f to Graph.stations.get(f.id) }
        val history = DetectionHistory.record(DetectionHistory.key(m.originId, m.number, m.dateMs), a)
        val index = runCatching { Graph.stations.all() }.getOrDefault(emptyList())
        val located = history.mapNotNull { DetectionLocator.locate(it, a, stops, index) }
        Triple(a, stops, located)
    }
    // Traccia sui binari OSM: dipende solo dalla sequenza delle fermate
    val knownStops = state.data?.second?.mapNotNull { it.second }
    val stopKey = knownStops?.joinToString(",") { it.codiceStazione }
    val profile = state.data?.first?.let { railProfile(it.title) } ?: RailNetwork.Profile.NEUTRAL
    val route = rememberLoader(stopKey, profile) {
        val pts = knownStops.orEmpty().map { it.codiceStazione to LatLon(it.lat, it.lon) }
        if (pts.size < 2) emptyList() else withContext(Dispatchers.Default) { Graph.rail().route(pts, profile) }
    }
    val map = rememberMapView()
    val ctx = LocalContext.current
    var sel by remember { mutableStateOf<TrainMapSel?>(null) }
    // tratteggio del tratto in corso che scorre nel verso di marcia, come nella schermata del treno
    val transitLines = remember { mutableListOf<Polyline>() }
    LaunchedEffect(Unit) {
        var phase = 0f
        while (true) {
            delay(40)
            phase = (phase - 2f) % 46f
            if (transitLines.isNotEmpty()) {
                transitLines.forEach { it.outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(28f, 18f), phase) }
                map.invalidate()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.data?.first?.title?.let { "Percorso $it" } ?: "Percorso treno $number") },
                navigationIcon = {
                    IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
                },
                actions = { IconButton(onClick = state.reload) { Icon(Icons.Default.Refresh, "Aggiorna") } },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            AndroidView(factory = { map }, modifier = Modifier.fillMaxSize())
            OsmAttribution(Modifier.align(Alignment.BottomStart))
            MapControls(map, Modifier.align(Alignment.CenterEnd).padding(end = 12.dp))
            val data = state.data
            val segments = route.data
            LaunchedEffect(data, segments) {
                if (data == null) return@LaunchedEffect
                val (a, stops, detections) = data
                val firstDraw = map.overlays.isEmpty()
                // la posizione dell'utente resta
                map.overlays.removeAll { it !is org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay }
                // tocco sulla mappa vuota: chiude la scheda
                map.overlays.add(0, org.osmdroid.views.overlay.MapEventsOverlay(object : org.osmdroid.events.MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean { sel = null; return false }
                    override fun longPressHelper(p: GeoPoint?) = false
                }))
                val known = stops.filter { it.second != null }
                val pts = known.map { GeoPoint(it.second!!.lat, it.second!!.lon) }
                val lastReachedKnown = known.indexOfLast { it.first.reached && !it.first.suppressed }
                // Tratti tra fermate consecutive: binari OSM se disponibili, altrimenti linea retta
                val legs = (0 until (pts.size - 1).coerceAtLeast(0)).map { i ->
                    segments?.getOrNull(i)?.map { GeoPoint(it.lat, it.lon) } ?: listOf(pts[i], pts[i + 1])
                }
                // Tre stati: già percorso (pieno), in transito teorico dall'ultimo rilevamento alla prossima fermata
                // (tratteggiato), ancora da percorrere (grigio)
                val lastDet = detections.lastOrNull()
                transitLines.clear()
                fun line(points: List<GeoPoint>, kind: Int) {
                    if (points.size < 2) return
                    if (kind == 1) {
                        // tratto in corso: alone arancione tenue sotto il tratteggio animato
                        map.overlays.add(Polyline(map).apply {
                            setPoints(points)
                            outlinePaint.color = MapColors.TRANSIT and 0x00FFFFFF or 0x40000000
                            outlinePaint.strokeWidth = 10f
                            outlinePaint.strokeCap = Paint.Cap.ROUND
                            infoWindow = null
                        })
                    }
                    map.overlays.add(Polyline(map).apply {
                        setPoints(points)
                        outlinePaint.color = when (kind) { 1 -> MapColors.TRANSIT; 2 -> MapColors.ROUTE_TODO; else -> MapColors.ROUTE }
                        outlinePaint.strokeWidth = 10f
                        outlinePaint.strokeCap = if (kind == 1) Paint.Cap.BUTT else Paint.Cap.ROUND
                        outlinePaint.strokeJoin = Paint.Join.ROUND
                        if (kind == 1) {
                            outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(28f, 18f), 0f)
                            transitLines.add(this)
                        }
                        infoWindow = null
                    })
                }
                val running = a.arrivato != true && a.nonPartito != true && !a.cancelled
                legs.forEachIndexed { i, leg ->
                    when {
                        a.arrivato == true || i < lastReachedKnown -> line(leg, 0)
                        running && i == lastReachedKnown -> {
                            // tratto in corso: diviso nel punto dell'ultimo rilevamento, se cade al suo interno
                            val proj = lastDet?.takeIf { d -> DetectionLocator.normalize(d.detection.name) != DetectionLocator.normalize(known[lastReachedKnown].first.stazione) }?.let { d ->
                                projectOnPolyline(leg.map { LatLon(it.latitude, it.longitude) }, LatLon(d.lat, d.lon))
                            }
                            if (proj == null) {
                                line(leg, 1)
                            } else {
                                val q = GeoPoint(proj.second.lat, proj.second.lon)
                                line(leg.subList(0, proj.first + 1) + q, 0)
                                line(listOf(q) + leg.subList(proj.first + 1, leg.size), 1)
                            }
                        }
                        else -> line(leg, 2)
                    }
                }
                val lastReached = a.fermate.indexOfLast { it.reached && !it.suppressed }
                stops.forEachIndexed { i, (f, geo) ->
                    geo ?: return@forEachIndexed
                    map.overlays.add(Marker(map).apply {
                        position = GeoPoint(geo.lat, geo.lon)
                        icon = ContextCompat.getDrawable(ctx, R.drawable.stop_dot)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        alpha = if (f.reached || i <= lastReached) 1f else 0.6f
                        infoWindow = null
                        setOnMarkerClickListener { _, _ -> sel = TrainMapSel.Stop(i); true }
                    })
                }
                // Rilevamenti: i precedenti come scia, l'ultimo con l'icona del treno
                val allRoute = legs.flatten()
                detections.forEachIndexed { i, ld ->
                    // sempre sul percorso: le coordinate di stazioni e posti tecnici non cadono sui binari
                    var p = GeoPoint(ld.lat, ld.lon)
                    projectOnPolyline(allRoute.map { LatLon(it.latitude, it.longitude) }, LatLon(ld.lat, ld.lon))
                        ?.second?.takeIf { DetectionLocator.distanceKm(it.lat, it.lon, ld.lat, ld.lon) < 15 }
                        ?.let { p = GeoPoint(it.lat, it.lon) }
                    val last = i == detections.lastIndex
                    map.overlays.add(Marker(map).apply {
                        position = p
                        icon = if (last) {
                            // direzione di marcia: lungo il percorso a partire dal punto del rilevamento
                            val from = allRoute.indices.minByOrNull {
                                DetectionLocator.distanceKm(allRoute[it].latitude, allRoute[it].longitude, p.latitude, p.longitude)
                            }
                            val bearing = if (a.arrivato == true || from == null) null
                            else TrainMarker.bearingAlong(allRoute.drop(from).map { LatLon(it.latitude, it.longitude) })
                            TrainMarker.icon(ctx, bearing)
                        } else ContextCompat.getDrawable(ctx, R.drawable.detection_dot)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        infoWindow = null
                        setOnMarkerClickListener { _, _ -> sel = TrainMapSel.Detection(i); true }
                    })
                }
                if (firstDraw && pts.isNotEmpty()) {
                    map.post {
                        if (pts.size == 1) {
                            map.controller.setZoom(12.0); map.controller.setCenter(pts[0])
                        } else {
                            map.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts).increaseByScale(1.2f), false)
                        }
                    }
                }
                map.invalidate()
            }
            if (data != null) {
                Card(Modifier.align(Alignment.TopCenter).padding(12.dp)) {
                    val a = data.first
                    Column(Modifier.padding(12.dp)) {
                        Text("${a.origine.orEmpty()} → ${a.destinazione.orEmpty()}", fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            data.third.lastOrNull()?.let { ld ->
                                Text(
                                    "Ultimo: ${ld.detection.name} ${fmtTime(ld.detection.time)}  ",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (!a.cancelled && a.nonPartito != true) DelayBadge(a.ritardo)
                        }
                        RouteLegend()
                        if (segments != null && segments.any { it == null }) {
                            Text("Alcuni tratti in linea retta (binari non trovati)", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                MapSheet(sel, Modifier.align(Alignment.BottomCenter)) { s ->
                    val a = data.first
                    when (s) {
                        is TrainMapSel.Stop -> StopSheet(a, s.index, onStation = { nav.station(it.id, it.stazione) }, onClose = { sel = null })
                        is TrainMapSel.Detection -> data.third.getOrNull(s.index)?.let { ld ->
                            DetectionSheet(
                                a, ld.detection.name, ld.detection.time, ld.precision,
                                last = s.index == data.third.lastIndex,
                                onOpenTrain = { nav.train(number, originId, dateMs) },
                                onClose = { sel = null },
                            )
                        }
                    }
                }
            } else if (state.error != null) {
                Card(Modifier.align(Alignment.Center).padding(24.dp)) { ErrorBox(state.error, onRetry = state.reload) }
            } else {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
}
