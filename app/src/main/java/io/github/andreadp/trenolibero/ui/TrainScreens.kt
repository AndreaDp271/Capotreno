package io.github.andreadp.trenolibero.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.andreadp.trenolibero.data.Andamento
import io.github.andreadp.trenolibero.data.DetectionHistory
import io.github.andreadp.trenolibero.data.DetectionLocator
import io.github.andreadp.trenolibero.data.ApiException
import io.github.andreadp.trenolibero.data.Favorites
import io.github.andreadp.trenolibero.data.Fermata
import io.github.andreadp.trenolibero.data.Graph
import io.github.andreadp.trenolibero.data.TrainMatch
import io.github.andreadp.trenolibero.data.ViaggiaTrenoApi
import kotlinx.coroutines.launch

// ---------------- Ricerca treno + preferiti ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainSearchScreen(nav: Nav) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by rememberSaveable { mutableStateOf<List<String>?>(null) } // serializzati per il salvataggio
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var loading by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val favTrains by Graph.favorites.trains.collectAsState()
    val favStations by Graph.favorites.stations.collectAsState()

    fun search() {
        val q = query.trim()
        if (q.isEmpty()) return
        scope.launch {
            loading = true; error = null; results = null
            try {
                val found = ViaggiaTrenoApi.searchTrain(q)
                when (found.size) {
                    0 -> error = "Nessun treno $q trovato per oggi"
                    1 -> found[0].let { nav.train(it.number, it.originId, it.dateMs) }
                    else -> results = found.map { "${it.label}|${it.number}|${it.originId}|${it.dateMs}" }
                }
            } catch (e: Exception) {
                error = errorMessage(e)
            }
            loading = false
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Cerca treno") }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { v -> query = v.filter { it.isDigit() }.take(6) },
                    label = { Text("Numero del treno") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                    trailingIcon = {
                        IconButton(onClick = { search() }) { Icon(Icons.Default.Search, "Cerca") }
                    },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                error?.let { EmptyText(it) }
                androidx.compose.material3.OutlinedButton(
                    onClick = { nav.journey() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Default.Search, null)
                    Text("  Cerca un viaggio da una stazione a un'altra")
                }
            }
            results?.let { list ->
                item { SectionTitle("Più treni con questo numero") }
                items(list) { r ->
                    val p = r.split("|")
                    ListItem(
                        headlineContent = { Text(p[0]) },
                        modifier = Modifier.clickable { nav.train(p[1], p[2], p[3].toLong()) },
                    )
                }
            }
            if (favTrains.isNotEmpty()) {
                item { SectionTitle("Treni preferiti") }
                items(favTrains) { t ->
                    ListItem(
                        headlineContent = { Text(t.label) },
                        supportingContent = { Text("Treno ${t.number}") },
                        leadingContent = { Icon(Icons.Default.Favorite, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { nav.train(t.number, t.originId, null) },
                    )
                }
            }
            if (favStations.isNotEmpty()) {
                item { SectionTitle("Stazioni preferite") }
                items(favStations) { s ->
                    ListItem(
                        headlineContent = { Text(s.name) },
                        leadingContent = { Icon(Icons.Default.Place, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { nav.station(s.id, s.name) },
                    )
                }
            }
            if (favTrains.isEmpty() && favStations.isEmpty() && results == null && error == null) {
                item {
                    EmptyText(
                        "Inserisci il numero del treno per vederne l'andamento in tempo reale: " +
                            "fermate, ritardi, binari e ultimo rilevamento.\n\n" +
                            "Tocca il cuore per aggiungere treni e stazioni ai preferiti."
                    )
                }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

// ---------------- Andamento treno ----------------

/** Risolve origine/data se mancanti (es. preferiti: la data cambia ogni giorno). */
suspend fun resolveTrain(number: String, originId: String?, dateMs: Long?): Pair<TrainMatch, Andamento> {
    val match = if (originId != null && dateMs != null) {
        TrainMatch("", number, originId, dateMs)
    } else {
        val found = ViaggiaTrenoApi.searchTrain(number)
        val m = found.firstOrNull { it.originId == originId } ?: found.firstOrNull()
            ?: throw ApiException("Treno $number non trovato per oggi")
        // Da un biglietto si conosce la data ma non la stazione d'origine
        if (dateMs != null) m.copy(dateMs = dateMs) else m
    }
    val status = ViaggiaTrenoApi.trainStatus(match.originId, match.number, match.dateMs)
        ?: throw ApiException(
            if (match.dateMs > System.currentTimeMillis()) "I dati in tempo reale saranno disponibili il giorno del viaggio"
            else "Dati non disponibili per il treno $number"
        )
    return match to status
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainScreen(nav: Nav, number: String, originId: String?, dateMs: Long?) {
    val state = rememberLoader(number, originId, dateMs, refreshMs = 60_000) {
        resolveTrain(number, originId, dateMs).also { (m, a) ->
            DetectionHistory.record(DetectionHistory.key(m.originId, m.number, m.dateMs), a)
        }
    }
    val favTrains by Graph.favorites.trains.collectAsState()
    val loaded = state.data

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(loaded?.second?.title ?: "Treno $number") },
                navigationIcon = {
                    IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
                },
                actions = {
                    if (loaded != null) {
                        val (m, a) = loaded
                        val fav = favTrains.any { it.number == m.number && it.originId == m.originId }
                        IconButton(onClick = {
                            Graph.favorites.toggleTrain(
                                Favorites.Train(m.number, m.originId, "${a.title} ${a.origine.orEmpty()} → ${a.destinazione.orEmpty()}")
                            )
                        }) {
                            Icon(if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Preferito")
                        }
                        IconButton(onClick = { nav.trainMap(m.number, m.originId, m.dateMs) }) {
                            Icon(Icons.Default.Place, "Mappa del percorso")
                        }
                    }
                    IconButton(onClick = state.reload) { Icon(Icons.Default.Refresh, "Aggiorna") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (state.loading && loaded != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.error != null && loaded != null) {
                Text(
                    "Aggiornamento non riuscito: ${state.error}",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            LoadedContent(state) { (_, a) -> TrainDetail(nav, a) }
        }
    }
}

@Composable
private fun TrainDetail(nav: Nav, a: Andamento) {
    val lastReached = a.fermate.indexOfLast { it.reached && !it.suppressed }
    LazyColumn(Modifier.fillMaxSize()) {
        item { TrainHeader(a) }
        item { SectionTitle("Fermate") }
        // Rilevamento in un punto che non è una fermata (stazione di transito, posto di comunicazione, bivio…)
        val det = a.stazioneUltimoRilevamento?.takeIf { it.isNotBlank() && it != "--" && a.arrivato != true }
        val detIsStop = det != null && a.fermate.any { DetectionLocator.normalize(it.stazione) == DetectionLocator.normalize(det) }
        itemsIndexed(a.fermate) { i, f ->
            StopRow(
                f = f,
                first = i == 0,
                last = i == a.fermate.lastIndex,
                current = i == lastReached && a.arrivato != true && (det == null || detIsStop),
                trainDelay = a.ritardo,
                trainStarted = a.nonPartito != true,
                onClick = { nav.station(f.id, f.stazione) },
            )
            if (det != null && !detIsStop && i == lastReached) DetectionRow(det, a.oraUltimoRilevamento)
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun TrainHeader(a: Andamento) {
    Card(
        Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "${a.origine.orEmpty()} → ${a.destinazione.orEmpty()}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Partenza ${fmtTime(a.orarioPartenza)} · Arrivo ${fmtTime(a.orarioArrivo)}" +
                    (a.compDurata?.takeIf { it.isNotBlank() }?.let { " · Durata $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
            )
            val status = when {
                a.cancelled -> "Treno soppresso"
                a.nonPartito == true -> "Non ancora partito"
                a.arrivato == true -> "Arrivato a destinazione"
                else -> "In viaggio"
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    status,
                    fontWeight = FontWeight.Bold,
                    color = if (a.cancelled) Palette.veryLate else MaterialTheme.colorScheme.onPrimaryContainer,
                )
                if (!a.cancelled && a.nonPartito != true) DelayBadge(a.ritardo)
            }
            if (a.partiallyCancelled) Text("Soppresso parzialmente", color = Palette.veryLate, fontWeight = FontWeight.Bold)
            if (a.deviated) Text("Treno deviato", color = Palette.late, fontWeight = FontWeight.Bold)
            if (a.oraUltimoRilevamento != null && !a.stazioneUltimoRilevamento.isNullOrBlank() &&
                a.stazioneUltimoRilevamento != "--"
            ) {
                Text("Ultimo rilevamento: ${a.stazioneUltimoRilevamento} alle ${fmtTime(a.oraUltimoRilevamento)}")
            }
            a.subTitle?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            a.motivoRitardoPrevalente?.takeIf { it.isNotBlank() }?.let {
                Text("Causa: $it", style = MaterialTheme.typography.bodySmall)
            }
            a.descOrientamento?.firstOrNull()?.takeIf { it.isNotBlank() && it != "--" }?.let {
                Text(android.text.Html.fromHtml(it, 0).toString(), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun StopRow(
    f: Fermata,
    first: Boolean,
    last: Boolean,
    current: Boolean,
    trainDelay: Int?,
    trainStarted: Boolean,
    onClick: () -> Unit,
) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val reachedColor = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .padding(end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Linea del percorso
        Box(Modifier.width(48.dp).height(76.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.width(3.dp).weight(1f).background(if (first) Color.Transparent else lineColor))
                Box(Modifier.width(3.dp).weight(1f).background(if (last) Color.Transparent else lineColor))
            }
            val dot = Modifier.size(if (current) 18.dp else 14.dp).padding(0.dp)
            when {
                f.suppressed -> Box(dot.background(Palette.veryLate, CircleShape))
                f.reached -> Box(dot.background(reachedColor, CircleShape))
                else -> Box(
                    dot.background(MaterialTheme.colorScheme.surface, CircleShape)
                        .border(3.dp, reachedColor, CircleShape)
                )
            }
        }
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                f.stazione,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (f.suppressed) TextDecoration.LineThrough else null,
            )
            if (f.suppressed) {
                Text("Fermata soppressa", color = Palette.veryLate, style = MaterialTheme.typography.bodySmall)
            } else {
                // Per le fermate non ancora raggiunte si mostra l'orario previsto (programmato + ritardo attuale)
                val predictDelay = if (trainStarted && !f.reached) (f.ritardo ?: trainDelay) else null
                if (f.tipoFermata != "P" && f.arrivoTeorico != null) {
                    TimeLine("Arr.", f.arrivoTeorico, f.arrivoReale, f.ritardoArrivo, predictDelay)
                }
                if (f.tipoFermata != "A" && f.partenzaTeorica != null) {
                    TimeLine("Part.", f.partenzaTeorica, f.partenzaReale, f.ritardoPartenza, predictDelay)
                }
                if (current) {
                    Text(
                        "Ultimo rilevamento",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            if (f.tipoFermata == "A") {
                PlatformBadge(f.binarioProgrammatoArrivoDescrizione, f.binarioEffettivoArrivoDescrizione)
            } else {
                PlatformBadge(
                    f.binarioProgrammatoPartenzaDescrizione ?: f.binarioProgrammatoArrivoDescrizione,
                    f.binarioEffettivoPartenzaDescrizione ?: f.binarioEffettivoArrivoDescrizione,
                )
            }
        }
    }
    HorizontalDivider(Modifier.padding(start = 48.dp), color = lineColor.copy(alpha = 0.4f))
}

/** Riga tra due fermate: il treno è stato rilevato in un punto intermedio. */
@Composable
private fun DetectionRow(name: String, time: Long?) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(48.dp).height(44.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
            Box(Modifier.size(12.dp).background(Color(0xFFE65100), CircleShape))
        }
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text("Rilevato a $name", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                "alle ${fmtTime(time)} · in transito, non è una fermata",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun TimeLine(label: String, programmed: Long, actual: Long?, delay: Int?, predictDelay: Int?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$label ${fmtTime(programmed)}", style = MaterialTheme.typography.bodyMedium)
        when {
            actual != null -> {
                Text("→ ${fmtTime(actual)}", style = MaterialTheme.typography.bodyMedium, color = delayColor(delay), fontWeight = FontWeight.Bold)
            }
            predictDelay != null && predictDelay != 0 -> {
                Text(
                    "prev. ${fmtTime(programmed + predictDelay * 60_000L)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = delayColor(predictDelay),
                )
            }
        }
    }
}
