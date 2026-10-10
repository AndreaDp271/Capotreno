package io.github.andreadp.trenolibero.ui

import androidx.compose.material.icons.filled.LocationOn
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.andreadp.trenolibero.data.DetectionLocator
import io.github.andreadp.trenolibero.data.Graph
import io.github.andreadp.trenolibero.data.StationGeo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Posizione dal servizio di localizzazione di Android (GPS e rete), senza Google Play Services.
 */
object UserLocation {
    fun granted(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Ultima posizione recente (≤ 5 minuti) oppure una nuova lettura, entro [timeoutMs]. */
    @SuppressLint("MissingPermission")
    suspend fun current(ctx: Context, timeoutMs: Long = 10_000): Location? {
        if (!granted(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = lm.getProviders(true)
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 5 * 60_000) return last
        val provider = when {
            LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
            LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
            else -> return last
        }
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                if (Build.VERSION.SDK_INT >= 30) {
                    lm.getCurrentLocation(provider, null, ctx.mainExecutor) { loc -> if (cont.isActive) cont.resume(loc) }
                } else {
                    val l = object : LocationListener {
                        override fun onLocationChanged(loc: Location) { lm.removeUpdates(this); if (cont.isActive) cont.resume(loc) }
                    }
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(provider, l, Looper.getMainLooper())
                    cont.invokeOnCancellation { lm.removeUpdates(l) }
                }
            }
        } ?: last
    }

    /** Stazioni più vicine con la distanza in km. */
    suspend fun nearestStations(loc: Location, count: Int = 4): List<Pair<StationGeo, Double>> =
        Graph.stations.all()
            .map { it to DetectionLocator.distanceKm(loc.latitude, loc.longitude, it.lat, it.lon) }
            .sortedBy { it.second }
            .take(count)
}

/** Stato del permesso di posizione con la funzione per chiederlo. */
class LocationPermission(val granted: Boolean, val request: () -> Unit)

@Composable
fun rememberLocationPermission(onResult: (Boolean) -> Unit = {}): LocationPermission {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(UserLocation.granted(ctx)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        granted = res.values.any { it }
        onResult(granted)
    }
    return LocationPermission(granted) {
        launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
}

fun formatKm(km: Double): String = if (km < 1) "${(km * 1000).toInt()} m" else "%.1f km".format(km)


/** Sezione "Vicino a te": le stazioni più vicine con la distanza. */
@androidx.compose.runtime.Composable
fun NearbyStations(onPick: (io.github.andreadp.trenolibero.data.StationGeo) -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val perm = rememberLocationPermission { if (it) tick++ }
    if (!perm.granted) {
        androidx.compose.material3.ListItem(
            headlineContent = { androidx.compose.material3.Text("Mostra le stazioni vicine a te") },
            supportingContent = { androidx.compose.material3.Text("Usa la posizione del telefono (resta sul dispositivo)") },
            leadingContent = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.LocationOn, null) },
            modifier = androidx.compose.ui.Modifier.clickable { perm.request() },
        )
        return
    }
    val near = rememberLoader(tick) { UserLocation.current(ctx)?.let { UserLocation.nearestStations(it) } }
    SectionTitle("Vicino a te")
    val list = near.data
    when {
        list != null -> list.forEach { (s, km) ->
            androidx.compose.material3.ListItem(
                headlineContent = { androidx.compose.material3.Text(s.name) },
                trailingContent = { androidx.compose.material3.Text(formatKm(km)) },
                leadingContent = {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.LocationOn, null,
                        tint = androidx.compose.material3.MaterialTheme.colorScheme.primary)
                },
                modifier = androidx.compose.ui.Modifier.clickable { onPick(s) },
            )
        }
        near.loading -> androidx.compose.material3.LinearProgressIndicator(
            androidx.compose.ui.Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        else -> EmptyText("Posizione non disponibile: attiva la localizzazione")
    }
}

/** Pallino della posizione sulla mappa e pulsante per centrarla. */
@androidx.compose.runtime.Composable
fun MyLocationButton(map: org.osmdroid.views.MapView, modifier: androidx.compose.ui.Modifier) {
    val ctx = LocalContext.current
    var centerNext by remember { mutableStateOf(false) }
    val perm = rememberLocationPermission { if (it) centerNext = true }
    val overlay = remember {
        PulsingLocationOverlay(ctx, map)
    }
    androidx.compose.runtime.DisposableEffect(perm.granted) {
        if (perm.granted) {
            overlay.enableMyLocation()
            if (overlay !in map.overlays) map.overlays.add(overlay)
            map.invalidate()
        }
        onDispose { overlay.disableMyLocation() }
    }
    androidx.compose.runtime.LaunchedEffect(centerNext, perm.granted) {
        if (!centerNext || !perm.granted) return@LaunchedEffect
        centerNext = false
        val p = overlay.myLocation ?: UserLocation.current(ctx)?.let { org.osmdroid.util.GeoPoint(it.latitude, it.longitude) }
        if (p != null) {
            if (overlay !in map.overlays) map.overlays.add(overlay)
            map.controller.animateTo(p, maxOf(map.zoomLevelDouble, 13.0), 600L)
        }
    }
    androidx.compose.material3.SmallFloatingActionButton(
        onClick = { if (perm.granted) centerNext = true else perm.request() },
        modifier = modifier,
    ) {
        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.LocationOn, "La mia posizione")
    }
}

/**
 * Posizione dell'utente: pallino blu con bordo bianco, alone pulsante e cerchio di precisione
 * (al posto dell'omino predefinito di osmdroid).
 */
class PulsingLocationOverlay(ctx: Context, private val map: org.osmdroid.views.MapView) :
    org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay(
        org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider(ctx), map,
    ) {
    private val density = ctx.resources.displayMetrics.density
    private val blue = 0xFF1A73E8.toInt()
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val point = android.graphics.Point()
    private val start = android.os.SystemClock.uptimeMillis()

    init { isDrawAccuracyEnabled = false }

    override fun drawMyLocation(canvas: android.graphics.Canvas, pj: org.osmdroid.views.Projection, lastFix: android.location.Location) {
        pj.toPixels(org.osmdroid.util.GeoPoint(lastFix.latitude, lastFix.longitude), point)
        val x = point.x.toFloat(); val y = point.y.toFloat()
        // cerchio di precisione
        val accPx = pj.metersToPixels(lastFix.accuracy, lastFix.latitude, pj.zoomLevel)
        if (accPx > 12 * density) {
            fill.color = 0x221A73E8
            canvas.drawCircle(x, y, accPx, fill)
        }
        // alone pulsante: si espande e svanisce ogni 1,8 s
        val phase = ((android.os.SystemClock.uptimeMillis() - start) % 1800L) / 1800f
        val r = 9 * density
        fill.color = blue
        fill.alpha = ((1f - phase) * 90).toInt()
        canvas.drawCircle(x, y, r + phase * 18 * density, fill)
        // pallino con bordo bianco e leggera ombra
        fill.alpha = 255
        fill.color = 0x33000000
        canvas.drawCircle(x, y + density, r + 3 * density, fill)
        fill.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(x, y, r + 2.5f * density, fill)
        fill.color = blue
        canvas.drawCircle(x, y, r, fill)
        // ridisegno continuo solo mentre la posizione è attiva
        map.postInvalidateDelayed(40)
    }
}
