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

    val state = rememberLoader(stationId, tab, timeMs, refreshMs = if (timeMs == null) 60_000 else null) {
        val t = timeMs?.let { Date(it) } ?: Date()
        if (tab == 0) ViaggiaTrenoApi.departures(stationId, t) else ViaggiaTrenoApi.arrivals(stationId, t)
    }

    fun pickTime() {
        val cal = Calendar.getInstance(ViaggiaTrenoApi.ROME).apply { timeInMillis = timeMs ?: System.currentTimeMillis() }
        TimePickerDialog(ctx, { _, h, m ->
            val c = Calendar.getInstance(ViaggiaTrenoApi.ROME)
            c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, m); c.set(Calendar.SECOND, 0)
            timeMs = c.timeInMillis
        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stationName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
                },
                actions = {
                    val fav = favStations.any { it.id == stationId }
                    IconButton(onClick = { Graph.favorites.toggleStation(StationRef(stationId, stationName)) }) {
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
                    onClick = { pickTime() },
                    label = { Text(if (timeMs == null) "Adesso" else "Dalle ${fmtTime(timeMs)}") },
                    leadingIcon = { Icon(Icons.Default.DateRange, null) },
                )
                if (timeMs != null) AssistChip(onClick = { timeMs = null }, label = { Text("Torna ad adesso") })
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
