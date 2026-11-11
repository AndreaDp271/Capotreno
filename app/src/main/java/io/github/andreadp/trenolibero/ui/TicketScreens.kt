package io.github.andreadp.trenolibero.ui

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import io.github.andreadp.trenolibero.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.andreadp.trenolibero.data.Graph
import io.github.andreadp.trenolibero.data.Ticket
import io.github.andreadp.trenolibero.data.TicketCode
import io.github.andreadp.trenolibero.data.TicketTrain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Importa un PDF o un'immagine; usato sia dal pulsante sia dalla condivisione da altre app.
 * Un PDF con più viaggi restituisce un biglietto per viaggio.
 */
suspend fun importTicket(uri: Uri): Result<List<Ticket>> = runCatching { Graph.tickets.import(uri, Graph.stations) }

/** Dopo l'importazione: un solo viaggio si apre subito, più viaggi si vedono nell'elenco dei biglietti. */
fun Nav.openImported(tickets: List<Ticket>) {
    if (tickets.size == 1) ticket(tickets[0].id) else tickets()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TicketsScreen(nav: Nav) {
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val allTickets by Graph.tickets.tickets.collectAsState()
    val subs by Graph.subscriptions.items.collectAsState()
    // i documenti allegati agli abbonamenti non compaiono tra i biglietti
    val attached = subs.mapNotNull { it.ticketId }.toSet()
    val tickets = allTickets.filter { it.id !in attached }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var newSub by remember { mutableStateOf(false) }
    // abbonamento ricavato da un PDF, da confermare nella finestra di modifica
    var draftSub by remember { mutableStateOf<io.github.andreadp.trenolibero.data.Subscription?>(null) }
    val subScope = rememberCoroutineScope()
    val subPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) subScope.launch {
            importing = true; error = null
            importTicket(uri).onSuccess { t -> draftSub = subscriptionFromTicket(t.first()) }.onFailure { error = errorMessage(it) }
            importing = false
        }
    }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true; error = null
            importTicket(uri).onSuccess { nav.openImported(it) }.onFailure { error = errorMessage(it) }
            importing = false
        }
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text(if (tab == 0) "Biglietti" else "Abbonamenti") }) },
        floatingActionButton = {
            if (tab == 1) Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExtendedFloatingActionButton(
                    onClick = { subPicker.launch(arrayOf("application/pdf", "image/*")) },
                    // etichetta esplicita per TalkBack
                    modifier = Modifier.semantics { contentDescription = "Importa PDF" },
                    icon = { Icon(painterResource(R.drawable.ic_tab_ticket), null) },
                    text = { Text("Importa PDF") },
                )
                ExtendedFloatingActionButton(
                    onClick = { newSub = true },
                    // etichetta esplicita per TalkBack
                    modifier = Modifier.semantics { contentDescription = "Nuovo abbonamento" },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Nuovo abbonamento") },
                )
            } else Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExtendedFloatingActionButton(
                    onClick = { nav.scan() },
                    // etichetta esplicita per TalkBack
                    modifier = Modifier.semantics { contentDescription = "Scansiona" },
                    icon = { Icon(painterResource(R.drawable.ic_qr_scan), null) },
                    text = { Text("Scansiona") },
                )
                ExtendedFloatingActionButton(
                    onClick = { picker.launch(arrayOf("application/pdf", "image/*")) },
                    // etichetta esplicita per TalkBack
                    modifier = Modifier.semantics { contentDescription = "Importa file" },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Importa file") },
                )
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Biglietti") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Abbonamenti (${subs.size})") })
            }
            if (tab == 1) {
                SubscriptionsList(nav)
                if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
                if (newSub) SubscriptionDialog(null, onDismiss = { newSub = false }) { s ->
                    Graph.subscriptions.save(s); newSub = false; nav.subscription(s.id)
                }
                draftSub?.let { d ->
                    SubscriptionDialog(d, title = "Abbonamento dal PDF", onDismiss = {
                        // annullato: il documento importato non serve più
                        d.ticketId?.let { Graph.tickets.delete(it) }; draftSub = null
                    }) { s -> Graph.subscriptions.save(s); draftSub = null; nav.subscription(s.id) }
                }
                return@Column
            }
            if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            if (tickets.isEmpty()) {
                EmptyText(
                    "Importa il biglietto in PDF (Trenitalia, Italo, regionale…) oppure uno screenshot o una foto, " +
                        "anche condividendolo con Capotreno dall'app di posta o dalla galleria.\n\n" +
                        "L'app estrae il QR code da mostrare al controllore, riconosce treno, data, stazioni, " +
                        "carrozza e posto (dai PDF) e ti porta all'andamento del treno. Tutto resta sul telefono."
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(tickets, key = { it.id }) { t -> TicketCard(t) { nav.ticket(t.id) } }
            }
        }
    }
}

private fun isToday(t: Ticket): Boolean {
    val f = SimpleDateFormat("dd/MM/yyyy", Locale.ITALY).apply { timeZone = io.github.andreadp.trenolibero.data.ViaggiaTrenoApi.ROME }
    return t.date == f.format(Date())
}

/** Bozza di abbonamento dai dati riconosciuti nel PDF (stazioni ricondotte ai codici ViaggiaTreno). */
private suspend fun subscriptionFromTicket(t: io.github.andreadp.trenolibero.data.Ticket): io.github.andreadp.trenolibero.data.Subscription {
    val stations = runCatching { Graph.stations.all() }.getOrDefault(emptyList())
    fun find(name: String) = stations.firstOrNull {
        io.github.andreadp.trenolibero.data.DetectionLocator.normalize(it.name) == io.github.andreadp.trenolibero.data.DetectionLocator.normalize(name)
    }
    val a = find(t.from)
    val b = find(t.to)
    return io.github.andreadp.trenolibero.data.Subscription(
        id = io.github.andreadp.trenolibero.data.Subscription.newId(),
        name = if (t.from.isNotEmpty() && t.to.isNotEmpty()) "${t.from} – ${t.to}" else "Abbonamento",
        fromId = a?.codiceStazione.orEmpty(), fromName = a?.name.orEmpty(),
        toId = b?.codiceStazione.orEmpty(), toName = b?.name.orEmpty(),
        type = runCatching { io.github.andreadp.trenolibero.data.SubscriptionType.valueOf(t.subscriptionType) }
            .getOrDefault(io.github.andreadp.trenolibero.data.SubscriptionType.REGIONAL),
        operator = runCatching { io.github.andreadp.trenolibero.data.SubscriptionOperator.valueOf(t.operator) }
            .getOrDefault(io.github.andreadp.trenolibero.data.SubscriptionOperator.ANY),
        validFrom = t.validFrom.ifEmpty { t.date },
        validTo = t.validTo,
        holder = t.passenger,
        ticketId = t.id,
    )
}

@Composable
private fun TicketCard(t: Ticket, onClick: () -> Unit) {
    val today = isToday(t)
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable(onClick = onClick),
        colors = if (today) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (today) Text("OGGI", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text(t.title, fontWeight = FontWeight.Bold)
            Text(
                listOf(t.date, listOf(t.departure, t.arrival).filter { it.isNotEmpty() }.joinToString(" – "))
                    .filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
            )
            val extra = listOfNotNull(
                t.trains.takeIf { it.isNotEmpty() }?.joinToString(" + ") { "${it.category} ${it.number}".trim() },
                t.coach.takeIf { it.isNotEmpty() }?.let { "Carrozza $it" },
                t.seat.takeIf { it.isNotEmpty() }?.let { "Posto $it" },
            )
            if (extra.isNotEmpty()) Text(extra.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TicketScreen(nav: Nav, id: String) {
    val tickets by Graph.tickets.tickets.collectAsState()
    val t = tickets.firstOrNull { it.id == id }
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf<Bitmap?>(null) }
    // pagina del biglietto aperta a schermo intero (indice tra quelle mostrate), ingrandibile
    var zoomPage by remember { mutableStateOf<Int?>(null) }
    // rilettura del PDF in corso (con la ricerca dei codici di ogni viaggio richiede qualche secondo)
    var reparsing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // si ricarica quando cambiano le pagine (es. biglietto diviso per viaggio con ↻)
    val pages = rememberLoader(id, t?.pages) { withContext(Dispatchers.IO) { ticketPages(id) } }
    // Codice da mostrare al controllore: salvato all'importazione, altrimenti cercato ora
    val code = rememberLoader(id, t?.codeText) {
        withContext(Dispatchers.IO) {
            val f = Graph.tickets.code(id)
            if (f.exists()) BitmapFactory.decodeFile(f.path) else Graph.tickets.findCode(id)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t?.title ?: "Biglietto") },
                navigationIcon = {
                    IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
                },
                actions = {
                    if (t?.kind == "pdf") {
                        IconButton(enabled = !reparsing, onClick = {
                            scope.launch {
                                reparsing = true
                                runCatching { Graph.tickets.reparse(id, Graph.stations) }
                                reparsing = false
                            }
                        }) {
                            Icon(Icons.Default.Refresh, "Rileggi i dati dal PDF")
                        }
                    }
                    IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, "Modifica") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Elimina") }
                },
            )
        },
    ) { pad ->
        if (t == null) {
            EmptyText("Biglietto non trovato"); return@Scaffold
        }
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            if (reparsing) item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Rilettura del PDF e ricerca dei codici…", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
            item { CodeSection(code, t, onShow = { fullscreen = it }, fallback = pages.data?.firstOrNull()) }
            item {
                Card(
                    Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Field("Da", t.from); Field("A", t.to)
                        Field("Data", t.date)
                        Field("Orario", listOf(t.departure, t.arrival).filter { it.isNotEmpty() }.joinToString(" – "))
                        Field("Carrozza", t.coach); Field("Posto", t.seat)
                        Field("PNR / codice", t.pnr)
                        Field(if (", " in t.passenger) "Passeggeri" else "Passeggero", t.passenger)
                        if (t.trains.isEmpty() && t.from.isEmpty() && t.date.isEmpty()) {
                            Text(
                                if (t.isImage || t.isScan) "Dal codice non si leggono i dati del viaggio: tocca la matita per inserire treno e data."
                                else "Non sono riuscito a riconoscere i dati: tocca la matita per inserirli.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            if (t.trains.isNotEmpty()) {
                item { SectionTitle("Andamento in tempo reale") }
                items(t.trains) { tr ->
                    Button(
                        onClick = { nav.train(tr.number, null, t.dateMsOf(tr)) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    ) { Text("Segui il treno ${"${tr.category} ${tr.number}".trim()}") }
                }
            }
            if (!t.isScan) item { SectionTitle(if (t.isImage) "Immagine" else "Biglietto") }
            if (!t.isScan) item {
                Text(
                    "Tocca una pagina per vederla a schermo intero: pizzica o tocca due volte per ingrandire.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            val bitmaps = pages.data
            when {
                bitmaps != null -> items(bitmaps.size) { i ->
                    Image(
                        bitmaps[i].asImageBitmap(),
                        contentDescription = "Pagina del biglietto",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().padding(16.dp).background(Color.White).clickable { zoomPage = i },
                    )
                }
                pages.error != null -> item { EmptyText(pages.error) }
                t.isScan -> Unit
                else -> item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
            }
        }
    }

    if (editing && t != null) EditTicketDialog(t, onDismiss = { editing = false }) { Graph.tickets.save(it); editing = false }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Eliminare il biglietto?") },
            text = { Text("Il PDF verrà rimosso dall'app.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; Graph.tickets.delete(id); nav.back() }) { Text("Elimina") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annulla") } },
        )
    }
    fullscreen?.let { b -> FullscreenPage(b) { fullscreen = null } }
    zoomPage?.let { i ->
        val preview = pages.data?.getOrNull(i)
        if (preview == null) zoomPage = null
        else {
            // la pagina si rende di nuovo ad alta risoluzione, così resta nitida anche ingrandita
            val sharp = rememberLoader(id, i) { withContext(Dispatchers.IO) { ticketPageSharp(id, i) } }
            FullscreenPage(sharp.data ?: preview, smooth = true) { zoomPage = null }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    if (value.isEmpty()) return
    Row {
        Text("$label: ", style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

/**
 * Pagina a schermo intero su fondo bianco, con luminosità massima per la lettura del codice.
 * Si ingrandisce col pizzico (fino a 6×) o col doppio tocco, si sposta trascinando; un tocco chiude
 * quando non è ingrandita. [smooth]: filtro per le pagine di testo; senza filtro i moduli del codice restano netti.
 */
@Composable
private fun FullscreenPage(b: Bitmap, smooth: Boolean = false, onClose: () -> Unit) {
    val activity = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        val w = activity?.window
        val old = w?.attributes?.screenBrightness
        if (io.github.andreadp.trenolibero.data.AppSettings.qrBrightness) w?.attributes = w?.attributes?.apply { screenBrightness = 1f }
        onDispose { w?.attributes = w?.attributes?.apply { screenBrightness = old ?: -1f } }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(Color.White).clipToBounds()
                .pointerInput(Unit) {
                    // spostamento massimo: il bordo della pagina ingrandita non entra nello schermo
                    fun clamp(o: Offset, s: Float) = Offset(
                        o.x.coerceIn(-(s - 1) * size.width / 2, (s - 1) * size.width / 2),
                        o.y.coerceIn(-(s - 1) * size.height / 2, (s - 1) * size.height / 2),
                    )
                    detectTapGestures(
                        onTap = { if (scale <= 1.01f) onClose() },
                        onDoubleTap = { tap ->
                            if (scale > 1.01f) { scale = 1f; offset = Offset.Zero }
                            else {
                                // ingrandisce attorno al punto toccato
                                val c = tap - Offset(size.width / 2f, size.height / 2f)
                                scale = 3f; offset = clamp(c * (1 - 3f), 3f)
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val s = (scale * zoom).coerceIn(1f, 6f)
                        val c = centroid - Offset(size.width / 2f, size.height / 2f)
                        // il punto sotto le dita resta fermo mentre si ingrandisce
                        val o = c - (c - offset) * (s / scale) + pan
                        offset = Offset(
                            o.x.coerceIn(-(s - 1) * size.width / 2, (s - 1) * size.width / 2),
                            o.y.coerceIn(-(s - 1) * size.height / 2, (s - 1) * size.height / 2),
                        )
                        scale = s
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                b.asImageBitmap(),
                contentDescription = "Biglietto",
                contentScale = ContentScale.Fit,
                filterQuality = if (smooth) FilterQuality.High else FilterQuality.None,
                modifier = Modifier.fillMaxSize().padding(if (smooth) 8.dp else 24.dp).graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                },
            )
        }
    }
}

@Composable
private fun EditTicketDialog(t: Ticket, onDismiss: () -> Unit, onSave: (Ticket) -> Unit) {
    var trains by remember { mutableStateOf(t.trains.joinToString(", ") { "${it.category} ${it.number}".trim() }) }
    var date by remember { mutableStateOf(t.date) }
    var from by remember { mutableStateOf(t.from) }
    var to by remember { mutableStateOf(t.to) }
    var dep by remember { mutableStateOf(t.departure) }
    var arr by remember { mutableStateOf(t.arrival) }
    var coach by remember { mutableStateOf(t.coach) }
    var seat by remember { mutableStateOf(t.seat) }
    var pnr by remember { mutableStateOf(t.pnr) }
    var passenger by remember { mutableStateOf(t.passenger) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dati del biglietto") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(trains, { trains = it }, label = { Text("Treni (es. FR 9600, REG 2938)") }, singleLine = true)
                OutlinedTextField(date, { date = it }, label = { Text("Data (gg/mm/aaaa)") }, singleLine = true)
                OutlinedTextField(from, { from = it }, label = { Text("Da") }, singleLine = true)
                OutlinedTextField(to, { to = it }, label = { Text("A") }, singleLine = true)
                OutlinedTextField(dep, { dep = it }, label = { Text("Partenza (hh:mm)") }, singleLine = true)
                OutlinedTextField(arr, { arr = it }, label = { Text("Arrivo (hh:mm)") }, singleLine = true)
                OutlinedTextField(coach, { coach = it }, label = { Text("Carrozza") }, singleLine = true)
                OutlinedTextField(seat, { seat = it }, label = { Text("Posto") }, singleLine = true)
                OutlinedTextField(pnr, { pnr = it }, label = { Text("PNR / codice") }, singleLine = true)
                OutlinedTextField(passenger, { passenger = it }, label = { Text("Passeggero") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsed = trains.split(",", ";", "+").mapNotNull { part ->
                    val m = Regex("([A-Za-z.]*)\\s*(\\d{2,5})").find(part.trim()) ?: return@mapNotNull null
                    TicketTrain(m.groupValues[2], m.groupValues[1].uppercase())
                }
                onSave(
                    t.copy(
                        trains = parsed, date = date.trim(), from = from.trim(), to = to.trim(), departure = dep.trim(),
                        arrival = arr.trim(), coach = coach.trim(), seat = seat.trim(), pnr = pnr.trim(), passenger = passenger.trim(),
                    )
                )
            }) { Text("Salva") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

/** Pagine da mostrare: PDF renderizzato oppure l'immagine importata (nessuna per i codici scansionati). */
private fun ticketPages(id: String): List<Bitmap> {
    val t = Graph.tickets.get(id)
    if (t?.isScan == true) return emptyList()
    return if (t?.isImage == true) {
        listOfNotNull(BitmapFactory.decodeFile(Graph.tickets.image(id).path, BitmapFactory.Options().apply { inSampleSize = 2 }))
    } else {
        // solo le pagine di questo viaggio, se il PDF ne contiene più d'uno
        TicketCode.renderPdf(Graph.tickets.pdf(id), 1400, maxPages = 20, pages = t?.pages.orEmpty())
    }
}

/** Una pagina (indice tra quelle mostrate) resa ad alta risoluzione per lo zoom; null per le immagini. */
private fun ticketPageSharp(id: String, index: Int): Bitmap? {
    val t = Graph.tickets.get(id) ?: return null
    if (t.kind != "pdf") return null
    val page = t.pages.getOrNull(index) ?: index
    return runCatching { TicketCode.renderPdf(Graph.tickets.pdf(id), 3000, maxPages = 1, pages = listOf(page)).firstOrNull() }.getOrNull()
}

/** Il codice del biglietto in evidenza, con il pulsante per mostrarlo al controllore. */
@Composable
private fun CodeSection(code: Loaded<Bitmap?>, t: Ticket, onShow: (Bitmap) -> Unit, fallback: Bitmap?) {
    val bmp = code.data
    Card(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                bmp != null -> {
                    Image(
                        bmp.asImageBitmap(),
                        contentDescription = "Codice del biglietto",
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.size(220.dp).clickable { onShow(bmp) },
                    )
                    Button(onClick = { onShow(bmp) }, modifier = Modifier.padding(top = 8.dp)) { Text("Mostra al controllore") }
                    val fmt = when (t.codeFormat) {
                        "QR_CODE" -> "QR code"
                        "AZTEC" -> "Codice Aztec"
                        "PDF_417" -> "Codice PDF417"
                        "DATA_MATRIX" -> "DataMatrix"
                        else -> ""
                    }
                    if (fmt.isNotEmpty()) Text(fmt, color = Color.DarkGray, style = MaterialTheme.typography.labelSmall)
                }
                code.loading -> {
                    Text("Ricerca del codice…", color = Color.DarkGray)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                else -> {
                    Text("Nessun QR code trovato nel biglietto", color = Color.DarkGray)
                    if (fallback != null) {
                        Button(onClick = { onShow(fallback) }, modifier = Modifier.padding(top = 8.dp)) {
                            Text("Mostra il biglietto intero")
                        }
                    }
                }
            }
        }
    }
}
