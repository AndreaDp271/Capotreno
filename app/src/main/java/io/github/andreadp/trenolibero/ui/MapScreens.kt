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
import io.github.andreadp.trenolibero.data.StationGeo
import io.github.andreadp.trenolibero.data.Tratta
import io.github.andreadp.trenolibero.data.ViaggiaTrenoApi
import kotlinx.coroutines.delay
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
    const val ROUTE_TODO = 0x730D47A1            // blu trasparente (tratto da percorrere)
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
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
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
    var viewport by remember { mutableStateOf<Viewport?>(null) }
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

    // Disegno delle linee (sotto le stazioni)
    val showRegional = (viewport?.zoom ?: 6.0) >= 9
    LaunchedEffect(national.data, regional, showRegional, state.data) {
        val names = state.data?.associateBy({ it.codiceStazione }, { it.name }).orEmpty()
        map.overlays.removeAll(lineOverlays.toSet())
        lineOverlays.clear()
        val sets = buildList {
            national.data?.let { add(0 to it) }
            if (showRegional) regional.forEach { (r, l) -> add(r to l) }
        }
        for ((region, list) in sets) for (t in list) {
            val p = Polyline(map).apply {
                setPoints(listOf(GeoPoint(t.latitudineA, t.longitudineA), GeoPoint(t.latitudineB, t.longitudineB)))
                outlinePaint.color = if (t.occupata) MapColors.LINE_BUSY else MapColors.LINE_FREE
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
            Legend(Modifier.align(Alignment.TopStart).padding(8.dp))

            val stations = state.data
            LaunchedEffect(stations) {
                if (stations == null) return@LaunchedEffect
                map.overlays.removeAll { it is SimpleFastPointOverlay }
                val points = stations.map { LabelledGeoPoint(it.lat, it.lon, it.name) }
                val opts = SimpleFastPointOverlayOptions.getDefaultStyle()
                    .setAlgorithm(SimpleFastPointOverlayOptions.RenderingAlgorithm.MAXIMUM_OPTIMIZATION)
                    .setRadius(8f)
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
                overlay.setOnClickListener { _, i -> selectedLine = null; selected = stations[i] }
                map.overlays.add(overlay)
                focusId?.let { id ->
                    val i = stations.indexOfFirst { it.codiceStazione == id }
                    if (i >= 0) {
                        selected = stations[i]
                        overlay.selectedPoint = i
                        map.controller.setZoom(14.0)
                        map.controller.setCenter(GeoPoint(stations[i].lat, stations[i].lon))
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

            selected?.let { s ->
                Card(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, fontWeight = FontWeight.Bold)
                            Text(s.codiceStazione, style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = { nav.station(s.codiceStazione, s.name) }) { Text("Tabellone") }
                        IconButton(onClick = { selected = null }) { Icon(Icons.Default.Close, "Chiudi") }
                    }
                }
            }
            selectedLine?.let { sl -> LineCard(nav, sl, Modifier.align(Alignment.BottomCenter)) { selectedLine = null } }
        }
    }
}

@Composable
private fun Legend(modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            LegendRow(Color(MapColors.LINE_BUSY), "Treni in transito")
            LegendRow(Color(MapColors.LINE_FREE), "Linea libera")
            LegendRow(Color(MapColors.STATION), "Stazione", dot = true)
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
    val route = rememberLoader(stopKey) {
        val pts = knownStops.orEmpty().map { LatLon(it.lat, it.lon) }
        if (pts.size >= 2) Graph.router.route(pts) else emptyList()
    }
    val map = rememberMapView()
    val ctx = LocalContext.current

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
            val data = state.data
            val segments = route.data
            LaunchedEffect(data, segments) {
                if (data == null) return@LaunchedEffect
                val (a, stops, detections) = data
                val firstDraw = map.overlays.isEmpty()
                map.overlays.clear()
                val known = stops.filter { it.second != null }
                val pts = known.map { GeoPoint(it.second!!.lat, it.second!!.lon) }
                val lastReachedKnown = known.indexOfLast { it.first.reached && !it.first.suppressed }
                // Tratti tra fermate consecutive: binari OSM se disponibili, altrimenti linea retta
                val legs = (0 until (pts.size - 1).coerceAtLeast(0)).map { i ->
                    segments?.getOrNull(i)?.map { GeoPoint(it.lat, it.lon) } ?: listOf(pts[i], pts[i + 1])
                }
                legs.forEachIndexed { i, leg ->
                    map.overlays.add(Polyline(map).apply {
                        setPoints(leg)
                        outlinePaint.color = if (i < lastReachedKnown || a.arrivato == true) MapColors.ROUTE else MapColors.ROUTE_TODO
                        outlinePaint.strokeWidth = 10f
                        outlinePaint.strokeCap = Paint.Cap.ROUND
                        outlinePaint.strokeJoin = Paint.Join.ROUND
                        infoWindow = null
                    })
                }
                val lastReached = a.fermate.indexOfLast { it.reached && !it.suppressed }
                stops.forEachIndexed { i, (f, geo) ->
                    geo ?: return@forEachIndexed
                    map.overlays.add(Marker(map).apply {
                        position = GeoPoint(geo.lat, geo.lon)
                        title = f.stazione
                        snippet = buildString {
                            if (f.arrivoTeorico != null) append("Arr. ${fmtTime(f.arrivoTeorico)} ${f.arrivoReale?.let { "→ ${fmtTime(it)}" } ?: ""}\n")
                            if (f.partenzaTeorica != null) append("Part. ${fmtTime(f.partenzaTeorica)} ${f.partenzaReale?.let { "→ ${fmtTime(it)}" } ?: ""}\n")
                            if (f.suppressed) append("Fermata soppressa\n")
                        }.trim()
                        icon = ContextCompat.getDrawable(ctx, R.drawable.stop_dot)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        alpha = if (f.reached || i <= lastReached) 1f else 0.6f
                        setOnMarkerClickListener { m, _ -> m.showInfoWindow(); true }
                    })
                }
                // Rilevamenti: i precedenti come scia, l'ultimo con l'icona del treno
                val allRoute = legs.flatten()
                detections.forEachIndexed { i, ld ->
                    var p = GeoPoint(ld.lat, ld.lon)
                    if (ld.precision == Precision.ESTIMATED && allRoute.isNotEmpty()) {
                        p = allRoute.minBy { DetectionLocator.distanceKm(it.latitude, it.longitude, ld.lat, ld.lon) }
                    }
                    val last = i == detections.lastIndex
                    map.overlays.add(Marker(map).apply {
                        position = p
                        title = if (last) "Ultimo rilevamento" else "Rilevamento"
                        snippet = buildString {
                            append("${ld.detection.name} alle ${fmtTime(ld.detection.time)}")
                            when (ld.precision) {
                                Precision.NEAR -> append("\n(posto tecnico, posizione indicativa)")
                                Precision.ESTIMATED -> append("\n(posizione stimata dagli orari)")
                                Precision.EXACT -> Unit
                            }
                            if (last && a.ritardo != null) append("\n${delayText(a.ritardo)}")
                        }
                        icon = ContextCompat.getDrawable(ctx, if (last) R.drawable.train_pin else R.drawable.detection_dot)
                        setAnchor(Marker.ANCHOR_CENTER, if (last) Marker.ANCHOR_BOTTOM else Marker.ANCHOR_CENTER)
                        setOnMarkerClickListener { m, _ -> m.showInfoWindow(); true }
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
                        when {
                            route.loading -> Text("Calcolo del percorso sui binari OSM…", style = MaterialTheme.typography.labelSmall)
                            route.error != null -> Text("Percorso OSM non disponibile: linee rette", style = MaterialTheme.typography.labelSmall)
                            segments != null && segments.any { it == null } ->
                                Text("Alcuni tratti in linea retta (binari non trovati)", style = MaterialTheme.typography.labelSmall)
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
