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
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.runtime.remember
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
    val favLines by Graph.favorites.lines.collectAsState()

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

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Cerca treno") },
        )
    }) { pad ->
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
            if (favLines.isNotEmpty()) {
                item { SectionTitle("Linee preferite") }
                items(favLines) { l ->
                    ListItem(
                        headlineContent = { Text(l.name) },
                        supportingContent = { Text("Avvisi di circolazione") },
                        leadingContent = { Icon(Icons.Default.Favorite, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { nav.line(l.region, l.ab, l.ba) },
                    )
                }
            }
            if (favTrains.isEmpty() && favLines.isEmpty() && results == null && error == null) {
                item {
                    EmptyText(
                        "Inserisci il numero del treno per vederne l'andamento in tempo reale: " +
                            "fermate, ritardi, binari e ultimo rilevamento.\n\n" +
                            "Tocca il cuore per aggiungere treni, stazioni e linee ai preferiti."
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
    KeepScreenOnIfEnabled()
    val state = rememberLoader(number, originId, dateMs, refreshMs = 30_000) {
        resolveTrain(number, originId, dateMs).also { (m, a) ->
            DetectionHistory.record(DetectionHistory.key(m.originId, m.number, m.dateMs), a)
        }
    }
    val favTrains by Graph.favorites.trains.collectAsState()
    val loaded = state.data
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var alarmDialog by remember { mutableStateOf(false) }
    val notifPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    fun askNotifications() {
        if (!io.github.andreadp.trenolibero.data.Notifier.canNotify(ctx) && android.os.Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    var heartSheet by remember { mutableStateOf(false) }
    val liveTrain by io.github.andreadp.trenolibero.data.LiveTrain.followedFlow.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { io.github.andreadp.trenolibero.data.LiveTrain.all(ctx) }
    var alarmTick by remember { mutableStateOf(0) }
    val alarm = remember(loaded?.first, alarmTick) {
        loaded?.first?.let { m -> io.github.andreadp.trenolibero.data.StopAlarms.forTrain(ctx, m.number, m.originId, m.dateMs) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(loaded?.second?.title ?: "Treno $number")
                        RefreshStatus(state)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
                },
                actions = {
                    if (loaded != null) {
                        val (m, a) = loaded
                        val value = "${m.number}|${m.originId}|${m.dateMs}"
                        val fav = favTrains.any { it.number == m.number && it.originId == m.originId }
                        val following = value in liveTrain       // banner attivo: cuore rosso
                        val active = fav || following || alarm != null
                        IconButton(onClick = {
                            if (!fav) {
                                Graph.favorites.toggleTrain(
                                    Favorites.Train(m.number, m.originId, "${a.title} ${a.origine.orEmpty()} → ${a.destinazione.orEmpty()}")
                                )
                                askNotifications()
                            }
                            heartSheet = true
                        }) {
                            Box {
                                Icon(
                                    if (active) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Preferito e avvisi",
                                    tint = if (following) androidx.compose.ui.graphics.Color(0xFFE53935) else LocalContentColor.current,
                                )
                                if (alarm != null) ClockBadge(Modifier.align(Alignment.Center))      // avviso prima di una fermata
                            }
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
            if (heartSheet && loaded != null) {
                val (m, a) = loaded
                val value = "${m.number}|${m.originId}|${m.dateMs}"
                val fav = favTrains.any { it.number == m.number && it.originId == m.originId }
                val canFollow = a.arrivato != true && !a.cancelled
                androidx.compose.material3.ModalBottomSheet(onDismissRequest = { heartSheet = false }) {
                    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                        Text("${a.title} · ${a.destinazione.orEmpty()}", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Ricevi gli avvisi di ritardo, cambio binario e soppressione.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                        )
                        androidx.compose.material3.ListItem(
                            headlineContent = { Text("Banner del viaggio") },
                            supportingContent = { Text("Notifica fissa con la linea delle fermate e il widget") },
                            trailingContent = {
                                androidx.compose.material3.Switch(checked = value in liveTrain, enabled = canFollow || value in liveTrain, onCheckedChange = { on ->
                                    if (on) { io.github.andreadp.trenolibero.data.LiveTrain.start(ctx, value); askNotifications() }
                                    else io.github.andreadp.trenolibero.data.LiveTrain.stop(ctx, value)
                                })
                            },
                        )
                        androidx.compose.material3.ListItem(
                            headlineContent = { Text("Avviso prima di una fermata") },
                            supportingContent = { Text(alarm?.let { "${it.minutesBefore} min prima di ${it.stopName}" } ?: "Scegli la stazione e l'anticipo") },
                            trailingContent = {
                                androidx.compose.material3.Switch(checked = alarm != null, enabled = canFollow || alarm != null, onCheckedChange = { on ->
                                    if (on) { heartSheet = false; alarmDialog = true }
                                    else alarm?.let { io.github.andreadp.trenolibero.data.StopAlarms.cancel(ctx, it); alarmTick++ }
                                })
                            },
                        )
                        androidx.compose.material3.TextButton(
                            onClick = {
                                heartSheet = false
                                if (fav) Graph.favorites.toggleTrain(Favorites.Train(m.number, m.originId, ""))
                                if (value in liveTrain) io.github.andreadp.trenolibero.data.LiveTrain.stop(ctx, value)
                                alarm?.let { io.github.andreadp.trenolibero.data.StopAlarms.cancel(ctx, it); alarmTick++ }
                            },
                            modifier = Modifier.align(Alignment.End),
                        ) { Text("Togli dai preferiti e spegni tutto") }
                    }
                }
            }
            if (alarmDialog && loaded != null) {
                AlarmDialog(loaded.first, loaded.second, onDismiss = { alarmDialog = false }) { al ->
                    io.github.andreadp.trenolibero.data.StopAlarms.set(ctx, al)
                    alarmDialog = false; alarmTick++
                    askNotifications()
                }
            }
        }
    }
}

/** Scelta della fermata e dell'anticipo per la sveglia. */
@Composable
private fun AlarmDialog(m: TrainMatch, a: Andamento, onDismiss: () -> Unit, onSet: (io.github.andreadp.trenolibero.data.StopAlarm) -> Unit) {
    val lastReached = a.fermate.indexOfLast { it.reached }
    val stops = a.fermate.drop(lastReached + 1).filter { !it.suppressed && it.tipoFermata != "P" }
    var stop by remember { mutableStateOf(stops.lastOrNull()) }
    var minutes by remember { mutableStateOf(5) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Avvisami prima di arrivare") },
        text = {
            if (stops.isEmpty()) Text("Non ci sono fermate ancora da raggiungere.")
            else Column {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(2, 5, 10, 15).forEach { v ->
                        androidx.compose.material3.FilterChip(selected = minutes == v, onClick = { minutes = v }, label = { Text("$v min") })
                    }
                }
                LazyColumn(Modifier.height(320.dp)) {
                    items(stops) { f ->
                        Row(Modifier.fillMaxWidth().clickable { stop = f }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.RadioButton(selected = stop == f, onClick = { stop = f })
                            Column {
                                Text(f.stazione)
                                Text("arrivo ${fmtTime(f.arrivoTeorico)}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = stop != null, onClick = {
                val f = stop!!
                onSet(io.github.andreadp.trenolibero.data.StopAlarm(m.number, m.originId, m.dateMs, f.id, f.stazione, minutes, a.title))
            }) { Text("Imposta") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}


/** Orologino disegnato dentro il cuoricino: c'è un banner o un avviso attivo per questo treno. */
@Composable
private fun ClockBadge(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.surface
    androidx.compose.foundation.Canvas(modifier.size(11.dp)) {
        val c = center
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawCircle(color, radius = size.minDimension / 2 - 0.7.dp.toPx(), style = stroke)
        drawLine(color, c, androidx.compose.ui.geometry.Offset(c.x, c.y - size.height * 0.3f), strokeWidth = stroke.width, cap = stroke.cap)
        drawLine(color, c, androidx.compose.ui.geometry.Offset(c.x + size.width * 0.22f, c.y), strokeWidth = stroke.width, cap = stroke.cap)
    }
}
