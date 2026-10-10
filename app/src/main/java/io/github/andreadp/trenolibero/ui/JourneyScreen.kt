package io.github.andreadp.trenolibero.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.andreadp.trenolibero.data.Journey
import io.github.andreadp.trenolibero.data.JourneySearch
import io.github.andreadp.trenolibero.data.StationRef
import io.github.andreadp.trenolibero.data.TrainKind
import io.github.andreadp.trenolibero.data.ViaggiaTrenoApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Ricerca viaggio da stazione a stazione, con filtri per tipo di treno e compagnia. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JourneyScreen(nav: Nav) {
    var from by rememberSaveable { mutableStateOf<String?>(null) } // "id|nome"
    var to by rememberSaveable { mutableStateOf<String?>(null) }
    var timeMs by rememberSaveable { mutableStateOf<Long?>(null) }
    var results by remember { mutableStateOf<List<Journey>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var kinds by rememberSaveable { mutableStateOf(TrainKind.entries.map { it.name }.toSet()) }
    var directOnly by rememberSaveable { mutableStateOf(false) }
    var hiddenCompanies by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    fun ref(s: String?) = s?.split("|", limit = 2)?.let { StationRef(it[0], it[1]) }

    fun search() {
        val a = ref(from) ?: return
        val b = ref(to) ?: return
        scope.launch {
            loading = true; error = null
            try {
                results = JourneySearch.search(a, b, Date(timeMs ?: System.currentTimeMillis()))
                if (results!!.isEmpty()) error = "Nessuna soluzione trovata"
            } catch (e: Exception) {
                error = errorMessage(e)
            }
            loading = false
        }
    }

    fun pickDateTime() {
        val cal = Calendar.getInstance(ViaggiaTrenoApi.ROME).apply { timeInMillis = timeMs ?: System.currentTimeMillis() }
        DatePickerDialog(ctx, { _, y, m, d ->
            TimePickerDialog(ctx, { _, h, min ->
                timeMs = Calendar.getInstance(ViaggiaTrenoApi.ROME).apply { set(y, m, d, h, min, 0) }.timeInMillis
                search()
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cerca viaggio") },
                navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
            )
        },
    ) { pad ->
        val all = results.orEmpty()
        val companies = all.flatMap { it.companies }.distinct().sorted()
        val shown = all.filter { j ->
            j.kinds.all { it.name in kinds } && (!directOnly || j.changes == 0) && j.companies.none { it in hiddenCompanies }
        }
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StationField("Da", ref(from)) { from = it?.let { s -> "${s.id}|${s.name}" } }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            StationField("A", ref(to)) { to = it?.let { s -> "${s.id}|${s.name}" } }
                        }
                        IconButton(onClick = { val t = from; from = to; to = t }) { Icon(Icons.Default.Refresh, "Inverti") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AssistChip(
                            onClick = { pickDateTime() },
                            label = { Text(timeMs?.let { fmtDateTime(it) } ?: "Partenza: adesso") },
                            leadingIcon = { Icon(Icons.Default.DateRange, null) },
                        )
                        Button(onClick = { search() }, enabled = from != null && to != null && !loading) {
                            Icon(Icons.Default.Search, null); Text(" Cerca")
                        }
                    }
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            if (all.isNotEmpty()) {
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TrainKind.entries.forEach { k ->
                            FilterChip(
                                selected = k.name in kinds,
                                onClick = { kinds = if (k.name in kinds) kinds - k.name else kinds + k.name },
                                label = { Text(k.label) },
                            )
                        }
                        FilterChip(selected = directOnly, onClick = { directOnly = !directOnly }, label = { Text("Solo diretti") })
                    }
                }
                if (companies.size > 1) item {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        companies.forEach { c ->
                            FilterChip(
                                selected = c !in hiddenCompanies,
                                onClick = { hiddenCompanies = if (c in hiddenCompanies) hiddenCompanies - c else hiddenCompanies + c },
                                label = { Text(c) },
                            )
                        }
                    }
                }
                item {
                    Text(
                        "${shown.size} soluzioni su ${all.size}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            error?.let { item { EmptyText(it) } }
            items(shown) { j -> JourneyCard(nav, j) }
            if (results == null && !loading) item {
                EmptyText(
                    "Scegli le stazioni di partenza e di arrivo.\n\n" +
                        "Le soluzioni con cambio e i prezzi arrivano dal servizio di vendita Trenitalia; i treni diretti " +
                        "di tutte le imprese presenti su ViaggiaTreno (Trenord, TPER, ÖBB…) includono ritardo e binario in " +
                        "tempo reale. I treni Italo non sono pubblicati su questi servizi."
                )
            }
        }
    }
}

/** Mezzanotte (Roma) del giorno di partenza: serve ad andamentoTreno. */
private fun midnight(ms: Long): Long = Calendar.getInstance(ViaggiaTrenoApi.ROME).apply {
    timeInMillis = ms
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun fmtDateTime(ms: Long): String =
    SimpleDateFormat("EEE d MMM, HH:mm", Locale.ITALIAN).apply { timeZone = ViaggiaTrenoApi.ROME }.format(Date(ms))

private fun duration(ms: Long): String {
    val min = ms / 60_000
    return if (min >= 60) "${min / 60}h ${"%02d".format(min % 60)}" else "$min min"
}

/** Campo stazione con suggerimenti durante la digitazione. */
@Composable
private fun StationField(label: String, value: StationRef?, onSelect: (StationRef?) -> Unit) {
    var text by remember(value) { mutableStateOf(value?.name.orEmpty()) }
    var suggestions by remember { mutableStateOf<List<StationRef>>(emptyList()) }
    LaunchedEffect(text) {
        if (value != null && text == value.name || text.trim().length < 2) { suggestions = emptyList(); return@LaunchedEffect }
        delay(250)
        suggestions = runCatching { ViaggiaTrenoApi.searchStation(text) }.getOrDefault(emptyList()).take(6)
    }
    Column {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; if (value != null) onSelect(null) },
            label = { Text(label) },
            singleLine = true,
            trailingIcon = { if (text.isNotEmpty()) IconButton(onClick = { text = ""; onSelect(null) }) { Icon(Icons.Default.Clear, "Cancella") } },
            modifier = Modifier.fillMaxWidth(),
        )
        suggestions.forEach { s ->
            ListItem(
                headlineContent = { Text(s.name) },
                modifier = Modifier.clickable { suggestions = emptyList(); text = s.name; onSelect(s) },
            )
        }
    }
}

@Composable
private fun JourneyCard(nav: Nav, j: Journey) {
    var open by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).animateContentSize()
            .clickable {
                if (j.legs.size == 1) j.legs.single().let { l -> nav.train(l.number, l.originId, l.dateMs ?: midnight(l.departure)) } else open = !open
            },
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${fmtTime(j.departure)} → ${fmtTime(j.arrival)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(duration(j.arrival - j.departure), style = MaterialTheme.typography.bodySmall)
                if (j.delay != null) DelayBadge(j.delay)
            }
            Text(
                j.legs.joinToString(" + ") { it.title } + " · " + if (j.changes == 0) "diretto" else "${j.changes} ${if (j.changes == 1) "cambio" else "cambi"}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(j.companies.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                j.platform?.let { PlatformBadge(it, null) }
                j.price?.let { Text("da %.2f €".format(it), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
            }
            if (open) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                j.legs.forEach { l ->
                    Column(Modifier.fillMaxWidth().clickable { nav.train(l.number, l.originId, l.dateMs ?: midnight(l.departure)) }.padding(vertical = 4.dp)) {
                        Text("${l.title} · ${l.company}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        Text("${fmtTime(l.departure)} ${l.from} → ${fmtTime(l.arrival)} ${l.to}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else if (j.legs.size > 1) {
                Text("Tocca per i dettagli dei cambi", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
