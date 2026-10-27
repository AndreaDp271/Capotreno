package io.github.andreadp.trenolibero.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.andreadp.trenolibero.data.BoardEntry
import io.github.andreadp.trenolibero.data.Graph
import io.github.andreadp.trenolibero.data.StationRef
import io.github.andreadp.trenolibero.data.ViaggiaTrenoApi
import io.github.andreadp.trenolibero.data.plainText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.util.Calendar
import java.util.Date

// ---------------- Ricerca stazione ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationSearchScreen(nav: Nav) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<StationRef>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val favStations by Graph.favorites.stations.collectAsState()

    LaunchedEffect(query) {
        error = null
        if (query.trim().length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(300) // debounce
        loading = true
        try {
            results = ViaggiaTrenoApi.searchStation(query)
        } catch (e: Exception) {
            error = errorMessage(e)
        }
        loading = false
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Stazioni") }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Nome della stazione") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "Cancella") }
                    },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                error?.let { EmptyText(it) }
            }
            if (query.trim().length >= 2) {
                if (!loading && error == null && results.isEmpty()) item { EmptyText("Nessuna stazione trovata") }
                items(results) { s ->
                    ListItem(
                        headlineContent = { Text(s.name) },
                        supportingContent = { Text(s.id) },
                        modifier = Modifier.clickable { nav.station(s.id, s.name) },
                    )
                }
            } else {
                item { NearbyStations { s -> nav.station(s.codiceStazione, s.name) } }
            }
            if (query.trim().length >= 2) {
                // risultati già mostrati sopra
            } else if (favStations.isNotEmpty()) {
                item { SectionTitle("Stazioni preferite") }
                items(favStations) { s ->
                    ListItem(
                        headlineContent = { Text(s.name) },
                        leadingContent = { Icon(Icons.Default.Favorite, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { nav.station(s.id, s.name) },
                    )
                }
            } else {
                item { EmptyText("Cerca una stazione per vedere il tabellone partenze e arrivi in tempo reale.") }
            }
        }
    }
}

// ---------------- Tabellone ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationBoardScreen(nav: Nav, stationId: String, stationName: String) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var timeMs by rememberSaveable { mutableStateOf<Long?>(null) } // null = adesso
    val favStations by Graph.favorites.stations.collectAsState()
    val ctx = LocalContext.current
    val notifPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }

    val state = rememberLoader(stationId, tab, timeMs, refreshMs = if (timeMs == null) 60_000 else null) {
        val t = timeMs?.let { Date(it) } ?: Date()
        if (tab == 0) ViaggiaTrenoApi.departures(stationId, t) else ViaggiaTrenoApi.arrivals(stationId, t)
    }
    // avvisi dei monitor RFI della stazione, più quelli di infomobilità che citano la località
    val alerts = rememberLoader(stationName, refreshMs = 5 * 60_000) {
        coroutineScope {
            val monitor = async { runCatching { io.github.andreadp.trenolibero.data.RfiMonitor.notices(ctx, stationName) }.getOrNull().orEmpty() }
            val n = async { runCatching { ViaggiaTrenoApi.infomobility(works = false) }.getOrDefault(emptyList()) }
            val w = async { runCatching { ViaggiaTrenoApi.infomobility(works = true) }.getOrDefault(emptyList()) }
            val news = io.github.andreadp.trenolibero.data.alertsFor(
                n.await() + w.await(), setOfNotNull(io.github.andreadp.trenolibero.data.placeKey(stationName)),
            ).filter { !it.title.lowercase().contains("regolare") }.distinctBy { it.title }
            monitor.await() to news
        }
    }

    fun pickTime() {
        val cal = Calendar.getInstance(ViaggiaTrenoApi.ROME).apply { timeInMillis = timeMs ?: System.currentTimeMillis() }
        TimePickerDialog(ctx, { _, h, m ->
            // mantiene il giorno già scelto
            val c = Calendar.getInstance(ViaggiaTrenoApi.ROME).apply { timeInMillis = timeMs ?: System.currentTimeMillis() }
            c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, m); c.set(Calendar.SECOND, 0)
            timeMs = c.timeInMillis
        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
    }

    /** Giorno del tabellone: da oggi in avanti (ViaggiaTreno non restituisce i giorni passati). */
    fun pickDate() {
        val cal = Calendar.getInstance(ViaggiaTrenoApi.ROME).apply { timeInMillis = timeMs ?: System.currentTimeMillis() }
        android.app.DatePickerDialog(ctx, { _, y, mo, d ->
            val c = Calendar.getInstance(ViaggiaTrenoApi.ROME).apply { timeInMillis = timeMs ?: System.currentTimeMillis() }
            c.set(y, mo, d); c.set(Calendar.SECOND, 0)
            timeMs = c.timeInMillis.coerceAtLeast(System.currentTimeMillis() - 60_000)
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).apply {
            datePicker.minDate = System.currentTimeMillis() - 1000
            datePicker.maxDate = System.currentTimeMillis() + 60L * 24 * 3600 * 1000
        }.show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    androidx.compose.foundation.layout.Column {
                        Text(stationName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        RefreshStatus(state)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
                },
                actions = {
                    val fav = favStations.any { it.id == stationId }
                    IconButton(onClick = {
                        Graph.favorites.toggleStation(StationRef(stationId, stationName))
                        io.github.andreadp.trenolibero.data.Notifier.createChannels(ctx)
                        io.github.andreadp.trenolibero.data.Notifier.schedule(ctx)
                        if (!fav && !io.github.andreadp.trenolibero.data.Notifier.canNotify(ctx) && android.os.Build.VERSION.SDK_INT >= 33) {
                            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }) {
                        Icon(if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Preferito")
                    }
                    IconButton(onClick = { nav.map(stationId) }) { Icon(Icons.Default.Place, "Mostra sulla mappa") }
                    IconButton(onClick = state.reload) { Icon(Icons.Default.Refresh, "Aggiorna") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Partenze") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Arrivi") })
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(
                    onClick = { pickDate() },
                    label = { Text(boardDay(timeMs)) },
                    leadingIcon = { Icon(Icons.Default.DateRange, null) },
                )
                AssistChip(
                    onClick = { pickTime() },
                    label = { Text(if (timeMs == null) "Adesso" else "Dalle ${fmtTime(timeMs)}") },
                )
                if (timeMs != null) AssistChip(onClick = { timeMs = null }, label = { Text("Adesso") })
            }
            alerts.data?.let { (all, news) ->
                // gli avvisi fissi di sicurezza da soli non fanno comparire il riquadro
                val (standing, notices) = all.partition { io.github.andreadp.trenolibero.data.RfiMonitor.isStanding(it) }
                if (notices.isNotEmpty() || news.isNotEmpty()) StationAlerts(nav, stationName, notices, standing, news)
                else standing.firstOrNull()?.let { StandingNotice(it) }
            }
            if (state.loading && state.data != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            LoadedContent(state) { list ->
                if (list.isEmpty()) {
                    EmptyText(if (tab == 0) "Nessuna partenza nei prossimi 90 minuti" else "Nessun arrivo nei prossimi 90 minuti")
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(list) { e ->
                            BoardRow(e, departures = tab == 0) {
                                nav.train(e.numeroTreno.toString(), e.codOrigine, e.dataPartenzaTreno.takeIf { e.codOrigine != null })
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

/** Avviso fisso di sicurezza del monitor RFI ("vietato attraversare i binari…"): in evidenza discreta, non come disagio. */
@Composable
private fun StandingNotice(text: String) {
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text.lowercase().replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

/**
 * Avvisi della stazione (monitor RFI) e di infomobilità che citano la località:
 * chiusi mostrano il primo avviso e quanti sono, aperti tutto il testo.
 */
@Composable
private fun StationAlerts(
    nav: Nav, stationName: String, notices: List<String>, standing: List<String>, news: List<io.github.andreadp.trenolibero.data.InfoNews>,
) {
    var open by rememberSaveable(stationName) { mutableStateOf(false) }
    val place = io.github.andreadp.trenolibero.data.TrainBanner.pretty(
        io.github.andreadp.trenolibero.data.placeKey(stationName) ?: stationName,
    )
    val count = notices.size + news.size
    androidx.compose.material3.Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.clickable { open = !open }.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Text(
                    if (count == 1) "1 avviso per $place" else "$count avvisi per $place",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
                Text(if (open) "Chiudi" else "Mostra", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            // l'avviso della stazione si legge subito, come sul tabellone
            if (!open) notices.firstOrNull()?.let {
                Text(it.lowercase().replaceFirstChar { c -> c.uppercase() }, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(top = 6.dp))
            }
            if (open) {
                if (notices.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.2f))
                    Text("Avvisi della stazione (RFI)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    notices.forEach {
                        Text(it.lowercase().replaceFirstChar { c -> c.uppercase() }, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                standing.forEach {
                    Text(it.lowercase().replaceFirstChar { c -> c.uppercase() }, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.6f), modifier = Modifier.padding(top = 4.dp))
                }
                news.take(5).forEach { n ->
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.2f))
                    Text(n.title, style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer)
                    if (n.date.isNotBlank()) Text(n.date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    n.plainText().takeIf { it.isNotEmpty() && it != n.title }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                androidx.compose.material3.TextButton(onClick = { nav.info() }, modifier = Modifier.align(Alignment.End)) {
                    Text("Tutta l'infomobilità")
                }
            }
        }
    }
}

@Composable
private fun BoardRow(e: BoardEntry, departures: Boolean, onClick: () -> Unit) {
    val cancelled = e.provvedimento == 1
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(64.dp)) {
            Text(
                fmtTime(if (departures) e.orarioPartenza else e.orarioArrivo),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            if (!cancelled && e.nonPartito != true) DelayBadge(e.ritardo)
        }
        Column(Modifier.weight(1f)) {
            Text(
                (if (departures) e.destinazione else e.origine).orEmpty().ifEmpty { "—" },
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.padding(vertical = 2.dp)) { TrainBadge(e.title, e.categoriaDescrizione?.trim()?.ifEmpty { null } ?: e.title.substringBefore(' ')) }
            val status = when {
                cancelled -> "Soppresso"
                e.nonPartito == true -> "Non partito"
                e.inStazione == true -> "In stazione"
                departures && e.arrivato == true -> "Partito"
                !departures && e.arrivato == true -> "Arrivato"
                else -> null
            }
            status?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (cancelled) Palette.veryLate else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (departures) {
            PlatformBadge(e.binarioProgrammatoPartenzaDescrizione, e.binarioEffettivoPartenzaDescrizione)
        } else {
            PlatformBadge(e.binarioProgrammatoArrivoDescrizione, e.binarioEffettivoArrivoDescrizione)
        }
    }
}

/** "Oggi", "Domani" o il giorno abbreviato ("mer 15 ott"). */
private fun boardDay(ms: Long?): String {
    if (ms == null) return "Oggi"
    val day = Calendar.getInstance(ViaggiaTrenoApi.ROME).apply { timeInMillis = ms }
    val today = Calendar.getInstance(ViaggiaTrenoApi.ROME)
    val diff = ((day.apply { set(Calendar.HOUR_OF_DAY, 12) }.timeInMillis -
        today.apply { set(Calendar.HOUR_OF_DAY, 12) }.timeInMillis) / (24 * 3600_000.0)).let { Math.round(it) }
    return when (diff) {
        0L -> "Oggi"
        1L -> "Domani"
        else -> java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.ITALIAN).apply { timeZone = ViaggiaTrenoApi.ROME }.format(java.util.Date(ms))
    }
}
