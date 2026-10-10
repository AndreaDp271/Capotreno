package io.github.andreadp.trenolibero.ui

import android.graphics.Color as AColor
import android.graphics.Paint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.andreadp.trenolibero.R
import io.github.andreadp.trenolibero.data.Graph
import io.github.andreadp.trenolibero.data.StationGeo
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.simplefastpoint.LabelledGeoPoint
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlay
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlayOptions
import org.osmdroid.views.overlay.simplefastpoint.SimplePointTheme

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
        modifier = modifier.padding(4.dp),
    )
}

// ---------------- Mappa stazioni ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationsMapScreen(nav: Nav, focusId: String?) {
    val state = rememberLoader(Unit) { Graph.stations.all() }
    val map = rememberMapView()
    var selected by remember { mutableStateOf<StationGeo?>(null) }
    val primary = MaterialTheme.colorScheme.primary.toArgb()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mappa stazioni") },
                navigationIcon = {
                    if (focusId != null) IconButton(onClick = { nav.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro")
                    }
                },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            AndroidView(factory = { map }, modifier = Modifier.fillMaxSize())
            OsmAttribution(Modifier.align(Alignment.BottomStart))

            val stations = state.data
            if (stations != null) {
                // Overlay costruito una sola volta per lista di stazioni
                LaunchedEffect(stations) {
                    map.overlays.removeAll { it is SimpleFastPointOverlay }
                    val points = stations.map { LabelledGeoPoint(it.lat, it.lon, it.name) }
                    val opts = SimpleFastPointOverlayOptions.getDefaultStyle()
                        .setAlgorithm(SimpleFastPointOverlayOptions.RenderingAlgorithm.MAXIMUM_OPTIMIZATION)
                        .setRadius(9f)
                        .setIsClickable(true)
                        .setCellSize(14)
                        .setSymbol(SimpleFastPointOverlayOptions.Shape.CIRCLE)
                        .setPointStyle(Paint().apply { color = primary; style = Paint.Style.FILL; isAntiAlias = true })
                        .setSelectedPointStyle(Paint().apply { color = AColor.rgb(255, 160, 0); style = Paint.Style.FILL })
                        .setTextStyle(Paint().apply { color = AColor.BLACK; textSize = 30f; textAlign = Paint.Align.CENTER; isAntiAlias = true })
                        .setMinZoomShowLabels(11)
                    val overlay = SimpleFastPointOverlay(SimplePointTheme(points, true), opts)
                    overlay.setOnClickListener { _, i -> selected = stations[i] }
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
        }
    }
}

// ---------------- Mappa percorso treno ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainMapScreen(nav: Nav, number: String, originId: String, dateMs: Long) {
    val state = rememberLoader(number, originId, dateMs, refreshMs = 60_000) {
        val (_, a) = resolveTrain(number, originId, dateMs)
        val coords = a.fermate.map { f -> f to Graph.stations.get(f.id) }
        a to coords
    }
    val map = rememberMapView()
    val ctx = LocalContext.current
    val lineColor = MaterialTheme.colorScheme.primary.toArgb()

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
            if (data != null) {
                LaunchedEffect(data) {
                    val (a, stops) = data
                    val first = map.overlays.none { it is Polyline }
                    map.overlays.clear()
                    val known = stops.filter { it.second != null }
                    val pts = known.map { GeoPoint(it.second!!.lat, it.second!!.lon) }
                    if (pts.size >= 2) {
                        map.overlays.add(Polyline(map).apply {
                            setPoints(pts)
                            outlinePaint.color = lineColor
                            outlinePaint.strokeWidth = 10f
                            outlinePaint.strokeCap = Paint.Cap.ROUND
                        })
                    }
                    val lastReached = a.fermate.indexOfLast { it.reached && !it.suppressed }
                    stops.forEachIndexed { i, (f, geo) ->
                        geo ?: return@forEachIndexed
                        map.overlays.add(Marker(map).apply {
                            position = GeoPoint(geo.lat, geo.lon)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            title = f.stazione
                            snippet = buildString {
                                if (f.arrivoTeorico != null) append("Arr. ${fmtTime(f.arrivoTeorico)} ${f.arrivoReale?.let { "→ ${fmtTime(it)}" } ?: ""}\n")
                                if (f.partenzaTeorica != null) append("Part. ${fmtTime(f.partenzaTeorica)} ${f.partenzaReale?.let { "→ ${fmtTime(it)}" } ?: ""}\n")
                                if (f.suppressed) append("Fermata soppressa\n")
                                if (i == lastReached && a.arrivato != true) append("Ultimo rilevamento ${fmtTime(a.oraUltimoRilevamento)}")
                            }.trim()
                            if (i != lastReached) {
                                icon = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.stop_dot)
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                alpha = if (f.reached) 1f else 0.6f
                            }
                            setOnMarkerClickListener { m, _ -> m.showInfoWindow(); true }
                        })
                    }
                    if (first && pts.isNotEmpty()) {
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
                Card(Modifier.align(Alignment.TopCenter).padding(12.dp)) {
                    val a = data.first
                    Column(Modifier.padding(12.dp)) {
                        Text("${a.origine.orEmpty()} → ${a.destinazione.orEmpty()}", fontWeight = FontWeight.Bold)
                        Row {
                            if (!a.stazioneUltimoRilevamento.isNullOrBlank() && a.stazioneUltimoRilevamento != "--") {
                                Text(
                                    "Ultimo: ${a.stazioneUltimoRilevamento} ${fmtTime(a.oraUltimoRilevamento)}  ",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (!a.cancelled && a.nonPartito != true) DelayBadge(a.ritardo)
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
