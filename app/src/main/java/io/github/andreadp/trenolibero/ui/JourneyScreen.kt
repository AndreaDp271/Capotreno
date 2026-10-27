package io.github.andreadp.trenolibero.ui

import androidx.compose.material.icons.filled.LocationOn
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import io.github.andreadp.trenolibero.data.JourneyLeg
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

/** Ultima ricerca, conservata finché l'app resta aperta. */
private object JourneyCache {
    var results: List<Journey>? = null
    var notice: String? = null
    var error: String? = null
}

/** Ricerca viaggio da stazione a stazione, con filtri per tipo di treno e compagnia. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JourneyScreen(nav: Nav) {
    var from by rememberSaveable { mutableStateOf<String?>(null) } // "id|nome"
    var to by rememberSaveable { mutableStateOf<String?>(null) }
    var timeMs by rememberSaveable { mutableStateOf<Long?>(null) }
    // risultati conservati per la sessione: tornando indietro da un treno la lista resta
    var results by remember { mutableStateOf(JourneyCache.results) }
    var error by remember { mutableStateOf(JourneyCache.error) }
    var notice by remember { mutableStateOf(JourneyCache.notice) }
    var loading by remember { mutableStateOf(false) }
    var kinds by rememberSaveable { mutableStateOf(TrainKind.entries.map { it.name }.toSet()) }
    var directOnly by rememberSaveable { mutableStateOf(false) }
    var hiddenCompanies by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val listState = rememberLazyListState()
    var frequent by remember { mutableStateOf<io.github.andreadp.trenolibero.data.FrequentTrip?>(null) }
    frequent?.let { FrequentTripDialog(it, isNew = true) { frequent = null } }

    fun ref(s: String?) = s?.split("|", limit = 2)?.let { StationRef(it[0], it[1]) }

    fun search() {
        val a = ref(from) ?: return
        val b = ref(to) ?: return
        scope.launch {
            loading = true; error = null; notice = null
            try {
                val r = JourneySearch.search(a, b, Date(timeMs ?: System.currentTimeMillis()))
                results = r.journeys
                notice = r.notice
                if (r.journeys.isEmpty()) error = "Nessuna soluzione trovata"
            } catch (e: Exception) {
                results = null
                error = errorMessage(e)
            }
            JourneyCache.notice = notice
            JourneyCache.results = results
            JourneyCache.error = error
            loading = false
        }
    }

    fun pickDateTime() {
        val cal = Calendar.getInstance(ViaggiaTrenoApi.ROME).apply { timeInMillis = timeMs ?: System.currentTimeMillis() }
        DatePickerDialog(ctx, { _, y, m, d ->
            fun apply(h: Int, min: Int) {
                timeMs = Calendar.getInstance(ViaggiaTrenoApi.ROME).apply { set(y, m, d, h, min, 0) }.timeInMillis
                search()
            }
            TimePickerDialog(ctx, { _, h, min -> apply(h, min) }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).apply {
                // chiudendo l'orologio il giorno scelto resta valido, con l'ora di prima
                setOnCancelListener { apply(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)) }
            }.show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).apply {
            datePicker.minDate = System.currentTimeMillis() - 1000
        }.show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cerca viaggio") },
                navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
                actions = {
                    val a = ref(from)
                    val b = ref(to)
                    if (a != null && b != null) {
                        IconButton(onClick = {
                            // fascia proposta: l'ora della ricerca (o adesso) arrotondata, per un'ora e mezza
                            val cal = Calendar.getInstance(ViaggiaTrenoApi.ROME).apply { timeInMillis = timeMs ?: System.currentTimeMillis() }
                            val h = cal.get(Calendar.HOUR_OF_DAY)
                            frequent = io.github.andreadp.trenolibero.data.FrequentTrip(
                                id = java.util.UUID.randomUUID().toString(),
                                fromId = a.id, fromName = a.name, toId = b.id, toName = b.name,
                                start = "%02d:00".format(h), end = "%02d:30".format((h + 1) % 24),
                            )
                        }) { Icon(Icons.Default.DateRange, "Salva come viaggio frequente") }
                    }
                },
            )
        },
    ) { pad ->
        val all = results.orEmpty()
        val companies = all.flatMap { it.companies }.distinct().sorted()
        val shown = all.filter { j ->
            j.kinds.all { it.name in kinds } && (!directOnly || j.changes == 0) && j.companies.none { it in hiddenCompanies }
        }
        LazyColumn(Modifier.padding(pad).fillMaxSize(), state = listState) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StationField("Da", ref(from)) { from = it?.let { s -> "${s.id}|${s.name}" } }
                    NearbyChips(prefill = from == null) { s -> from = "${s.codiceStazione}|${s.name}" }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            StationField("A", ref(to)) { to = it?.let { s -> "${s.id}|${s.name}" } }
                        }
                        IconButton(onClick = { val t = from; from = to; to = t }) {
                            Icon(androidx.compose.ui.res.painterResource(io.github.andreadp.trenolibero.R.drawable.ic_swap_vert), "Scambia partenza e arrivo")
                        }
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
            notice?.let { n ->
                item {
                    androidx.compose.material3.Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(
                            n,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
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
fun midnight(ms: Long): Long = Calendar.getInstance(ViaggiaTrenoApi.ROME).apply {
    timeInMillis = ms
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun fmtDateTime(ms: Long): String =
    SimpleDateFormat("EEE d MMM, HH:mm", Locale.ITALIAN).apply { timeZone = ViaggiaTrenoApi.ROME }.format(Date(ms))

private fun duration(ms: Long): String {
    val min = ms / 60_000
    return if (min >= 60) "${min / 60}h ${"%02d".format(min % 60)}" else "$min min"
}

/** Stazioni più vicine come scelta rapida per la partenza; la più vicina viene proposta se il campo è vuoto. */
@Composable
private fun NearbyChips(prefill: Boolean, onPick: (io.github.andreadp.trenolibero.data.StationGeo) -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val perm = rememberLocationPermission { if (it) tick++ }
    if (!perm.granted) {
        AssistChip(onClick = { perm.request() }, label = { Text("Stazioni vicino a me") },
            leadingIcon = { Icon(androidx.compose.material.icons.Icons.Default.LocationOn, null) })
        return
    }
    val near = rememberLoader(tick) { UserLocation.current(ctx)?.let { UserLocation.nearestStations(it) } }
    var prefilled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(near.data) {
        val first = near.data?.firstOrNull()?.first
        if (prefill && !prefilled && first != null) { prefilled = true; onPick(first) }
    }
    near.data?.let { list ->
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            list.forEach { (s, km) ->
                AssistChip(
                    onClick = { onPick(s) },
                    label = { Text("${s.name} · ${formatKm(km)}", maxLines = 1) },
                    leadingIcon = { Icon(androidx.compose.material.icons.Icons.Default.LocationOn, null) },
                )
            }
        }
    }
}

/** Campo stazione con suggerimenti durante la digitazione. */
@Composable
fun StationField(label: String, value: StationRef?, onSelect: (StationRef?) -> Unit) {
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

/** Colore del badge per categoria, ispirato ai colori dei treni. */
fun categoryColor(category: String): Color = when (category.uppercase()) {
    "FR" -> Color(0xFFC8102E)
    "FA" -> Color(0xFF6E7B85)
    "FB" -> Color(0xFF0E7FB8)
    "IC", "ICN", "EC", "EN" -> Color(0xFF1F4E9C)
    "BU" -> Color(0xFF616161)
    else -> Color(0xFF2E7D32)
}

@Composable
private fun CategoryBadge(leg: JourneyLeg) = TrainBadge(leg.title, leg.category)

/** Badge del treno colorato per categoria (es. "FR 9600" rosso, "REG 2938" verde). */
@Composable
fun TrainBadge(title: String, category: String = title.trim().substringBefore(' ')) {
    Surface(color = categoryColor(category), shape = RoundedCornerShape(6.dp)) {
        Text(
            title.trim(),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** Sito di vendita per compagnia (non è possibile precompilare la ricerca: si apre la pagina di acquisto). */
private data class Seller(val name: String, val url: String)

private fun sellerFor(company: String): Seller = when (company) {
    "Trenord" -> Seller("Trenord", "https://www.trenord.it/")
    "ÖBB" -> Seller("ÖBB", "https://shop.oebbtickets.at/")
    else -> Seller("Trenitalia", "https://www.trenitalia.com/")
}

private fun dayOf(l: JourneyLeg) = l.dateMs ?: midnight(l.departure)

@Composable
fun JourneyCard(nav: Nav, j: Journey, buy: Boolean = true) {
    var open by rememberSaveable { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).animateContentSize().clickable { open = !open },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = if (open) 6.dp else 2.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            // Orari e linea del viaggio
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(fmtTime(j.departure), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(j.legs.first().from, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 110.dp))
                }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(duration(j.arrival - j.departure), style = MaterialTheme.typography.labelMedium)
                    TripLine(j.changes)
                    Text(
                        if (j.changes == 0) "diretto" else "${j.changes} ${if (j.changes == 1) "cambio" else "cambi"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (j.changes == 0) Palette.onTime else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(fmtTime(j.arrival), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(j.legs.last().to, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 110.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            // Treni, compagnie, prezzo
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    j.legs.forEach { CategoryBadge(it) }
                    if (j.cancelled) {
                        Surface(color = Palette.veryLate, shape = RoundedCornerShape(6.dp)) {
                            Text("SOPPRESSO", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    } else if (j.delay != null) DelayBadge(j.delay)
                    j.platform?.let { PlatformBadge(it, null) }
                }
                j.price?.let {
                    Text(
                        "%.2f €".format(it),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            Text(
                j.companies.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            if (open) {
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                j.legs.forEachIndexed { i, l ->
                    if (i > 0) {
                        val wait = (l.departure - j.legs[i - 1].arrival) / 60_000
                        Text(
                            "Cambio a ${l.from} · $wait min",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(start = 26.dp, top = 2.dp, bottom = 2.dp),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).background(categoryColor(l.category), CircleShape))
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                CategoryBadge(l)
                                Text(l.company, style = MaterialTheme.typography.labelSmall)
                            }
                            Text("${fmtTime(l.departure)}  ${l.from}", style = MaterialTheme.typography.bodyMedium)
                            Text("${fmtTime(l.arrival)}  ${l.to}", style = MaterialTheme.typography.bodyMedium)
                        }
                        if (j.legs.size > 1) {
                            TextButton(onClick = { nav.train(l.number, l.originId, dayOf(l)) }) { Text("Visualizza") }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                val sellers = j.legs.filter { it.category.uppercase() != "BU" }.map { sellerFor(it.company) }.distinct()
                    .ifEmpty { listOf(sellerFor("Trenitalia")) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (j.legs.size == 1) {
                        OutlinedButton(
                            onClick = { j.legs.single().let { l -> nav.train(l.number, l.originId, dayOf(l)) } },
                            modifier = Modifier.weight(1f),
                        ) { Text("Visualizza treno") }
                    }
                    if (buy) sellers.forEach { s ->
                        Button(onClick = {
                            if (s.name == "Trenitalia") {
                                // ricerca già compilata sul sito Trenitalia, a partire dall'ora di questa soluzione
                                scope.launch { uri.openUri(runCatching { JourneySearch.trenitaliaBuyUrl(j) }.getOrDefault(s.url)) }
                            } else uri.openUri(s.url)
                        }, modifier = Modifier.weight(1f)) {
                            Text(if (sellers.size == 1) "Acquista" else "Acquista ${s.name}", maxLines = 1)
                        }
                    }
                }
                if (buy) Text(
                    if (sellers.any { it.name == "Trenitalia" })
                        "Si apre la ricerca Trenitalia per questa tratta e orario: scegli la soluzione e acquista."
                    else "L'acquisto avviene sul sito di ${sellers.joinToString(" / ") { it.name }}.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** Linea orizzontale con un pallino per ogni cambio. */
@Composable
private fun TripLine(changes: Int) {
    val color = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        repeat(changes + 1) { i ->
            Box(Modifier.weight(1f).height(2.dp).background(color))
            if (i < changes) Box(Modifier.size(8.dp).border(2.dp, color, CircleShape))
        }
        Box(Modifier.size(8.dp).background(color, CircleShape))
    }
}
