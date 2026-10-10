package io.github.andreadp.trenolibero.ui

import android.app.DatePickerDialog
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.andreadp.trenolibero.R
import io.github.andreadp.trenolibero.data.Graph
import io.github.andreadp.trenolibero.data.StationRef
import io.github.andreadp.trenolibero.data.Subscription
import io.github.andreadp.trenolibero.data.SubscriptionOperator
import io.github.andreadp.trenolibero.data.SubscriptionType
import io.github.andreadp.trenolibero.data.ViaggiaTrenoApi
import io.github.andreadp.trenolibero.data.compatibleTrains
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date

// ---------------- Elenco ----------------

/** Elenco abbonamenti (scheda dentro "Biglietti"). */
@Composable
fun SubscriptionsList(nav: Nav, modifier: Modifier = Modifier) {
    val subs by Graph.subscriptions.items.collectAsState()
    LazyColumn(modifier.fillMaxSize()) {
        if (subs.isEmpty()) item {
            EmptyText(
                "Aggiungi il tuo abbonamento: tratta, tipo e validità.\n\n" +
                    "Vedrai i treni compatibili in tempo reale nei due sensi, i giorni rimanenti e potrai allegare " +
                    "il QR da mostrare al controllore."
            )
        }
        items(subs, key = { it.id }) { s -> SubscriptionCard(s) { nav.subscription(s.id) } }
    }
}

@Composable
private fun ValidityBadge(s: Subscription) {
    val days = s.daysLeft()
    val (text, color) = when {
        days == null -> "validità non indicata" to Color.Gray
        !s.isActive() && (s.fromMs ?: 0) > System.currentTimeMillis() -> "dal ${s.validFrom}" to Palette.early
        days < 0 -> "scaduto il ${s.validTo}" to Palette.veryLate
        days == 0 -> "scade oggi" to Palette.late
        days <= 7 -> "scade tra $days ${if (days == 1) "giorno" else "giorni"}" to Palette.late
        else -> "valido fino al ${s.validTo}" to Palette.onTime
    }
    androidx.compose.material3.Surface(color = color, shape = RoundedCornerShape(6.dp)) {
        Text(text, color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
private fun SubscriptionCard(s: Subscription, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (s.isActive()) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(s.name.ifEmpty { "Abbonamento" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${s.fromName} ⇄ ${s.toName}", style = MaterialTheme.typography.bodyMedium)
            Text("${s.type.label} · ${s.operator.label}", style = MaterialTheme.typography.labelSmall)
            ValidityBadge(s)
        }
    }
}

// ---------------- Dettaglio ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionScreen(nav: Nav, id: String) {
    val subs by Graph.subscriptions.items.collectAsState()
    val s = subs.firstOrNull { it.id == id }
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var reverse by rememberSaveable { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && s != null) scope.launch {
            importTicket(uri).onSuccess { t -> Graph.subscriptions.save(s.copy(ticketId = t.id)) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s?.name?.ifEmpty { null } ?: "Abbonamento") },
                navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
                actions = {
                    IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, "Modifica") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Elimina") }
                },
            )
        },
    ) { pad ->
        if (s == null) { EmptyText("Abbonamento non trovato"); return@Scaffold }
        val (from, to) = if (reverse) s.toName to s.fromName else s.fromName to s.toName
        val trains = rememberLoader(s.id, s.fromId, s.toId, s.type, s.operator, reverse, refreshMs = 60_000) {
            compatibleTrains(s, reverse, Date())
        }
        val code = rememberLoader(s.ticketId) {
            withContext(Dispatchers.IO) {
                val tid = s.ticketId ?: return@withContext null
                val f = Graph.tickets.code(tid)
                if (f.exists()) BitmapFactory.decodeFile(f.path) else Graph.tickets.findCode(tid)
            }
        }
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                Card(
                    Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${s.fromName} ⇄ ${s.toName}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("${s.type.label} · ${s.operator.label}", style = MaterialTheme.typography.bodySmall)
                        if (s.validFrom.isNotEmpty() || s.validTo.isNotEmpty()) {
                            Text("Validità: ${s.validFrom.ifEmpty { "…" }} – ${s.validTo.ifEmpty { "…" }}", style = MaterialTheme.typography.bodyMedium)
                        }
                        if (s.holder.isNotEmpty()) Text("Intestatario: ${s.holder}", style = MaterialTheme.typography.bodyMedium)
                        ValidityBadge(s)
                    }
                }
            }
            // Codice da mostrare al controllore
            item {
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        val bmp = code.data
                        when {
                            bmp != null -> {
                                Image(bmp.asImageBitmap(), "Codice dell'abbonamento", filterQuality = FilterQuality.None,
                                    modifier = Modifier.size(180.dp).clickable { fullscreen = bmp })
                                Button(onClick = { fullscreen = bmp }, modifier = Modifier.padding(top = 8.dp)) { Text("Mostra al controllore") }
                                s.ticketId?.let { tid ->
                                    TextButton(onClick = { nav.ticket(tid) }) { Text("Apri il documento allegato") }
                                }
                            }
                            s.ticketId != null && code.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                            else -> {
                                Text(
                                    if (s.ticketId != null) "Nessun codice trovato nel documento allegato" else "Allega l'abbonamento per avere il QR a portata di mano",
                                    color = Color.DarkGray,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                    OutlinedButton(onClick = { picker.launch(arrayOf("application/pdf", "image/*")) }) { Text("Allega file") }
                                    Button(onClick = { nav.scan(attachTo = s.id) }) {
                                        Icon(painterResource(R.drawable.ic_qr_scan), null); Text(" Scansiona")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Treni compatibili
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        SectionTitle("Treni compatibili")
                        Text("$from → $to", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 16.dp))
                    }
                    AssistChip(onClick = { reverse = !reverse }, label = { Text("Inverti") }, leadingIcon = { Icon(painterResource(R.drawable.ic_swap_horiz), null) })
                }
                Text(
                    "Diretti nelle prossime ore validi per il tuo abbonamento, con ritardo e binario in tempo reale.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                if (trains.loading && trains.data == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
                trains.error?.let { EmptyText(it) }
            }
            val list = trains.data?.compatible.orEmpty()
            val others = trains.data?.others.orEmpty()
            if (trains.data != null && list.isEmpty()) item {
                if (others.isEmpty()) {
                    EmptyText("Nessun treno diretto nelle prossime ore")
                } else {
                    // probabilmente tipo o operatore dell'abbonamento non corrispondono ai treni della tratta
                    val kinds = others.flatMap { it.legs }.map { it.kind.label }.distinct().joinToString(", ")
                    val companies = others.flatMap { it.companies }.distinct().joinToString(", ")
                    Card(
                        Modifier.fillMaxWidth().padding(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Nessun treno compatibile con: ${s.type.label} · ${s.operator.label}", fontWeight = FontWeight.Bold)
                            Text("Sulla tratta ci sono ${others.size} treni diretti di tipo $kinds ($companies). Il tipo o l'operatore dell'abbonamento sono corretti?")
                            Button(onClick = { editing = true }) { Text("Correggi l'abbonamento") }
                        }
                    }
                }
            }
            items(list) { j -> JourneyCard(nav, j, buy = false) }
            item { Box(Modifier.height(24.dp)) }
        }
    }

    if (editing && s != null) SubscriptionDialog(s, onDismiss = { editing = false }) { Graph.subscriptions.save(it); editing = false }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Eliminare l'abbonamento?") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; Graph.subscriptions.delete(id); nav.back() }) { Text("Elimina") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annulla") } },
    )
    fullscreen?.let { b -> FullscreenCode(b) { fullscreen = null } }
}

// ---------------- Creazione / modifica ----------------

@Composable
fun SubscriptionDialog(initial: Subscription?, title: String? = null, onDismiss: () -> Unit, onSave: (Subscription) -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var from by remember { mutableStateOf(initial?.takeIf { it.fromId.isNotEmpty() }?.let { StationRef(it.fromId, it.fromName) }) }
    var to by remember { mutableStateOf(initial?.takeIf { it.toId.isNotEmpty() }?.let { StationRef(it.toId, it.toName) }) }
    var type by remember { mutableStateOf(initial?.type ?: SubscriptionType.REGIONAL) }
    var operator by remember { mutableStateOf(initial?.operator ?: SubscriptionOperator.ANY) }
    var validFrom by remember { mutableStateOf(initial?.validFrom ?: Subscription.formatDate(System.currentTimeMillis())) }
    var validTo by remember { mutableStateOf(initial?.validTo.orEmpty()) }
    var holder by remember { mutableStateOf(initial?.holder.orEmpty()) }
    var outbound by remember { mutableStateOf(initial?.outboundTime.orEmpty()) }
    var back by remember { mutableStateOf(initial?.returnTime.orEmpty()) }
    fun pickTime(current: String, set: (String) -> Unit) {
        val (h, m) = current.split(":").map { it.toIntOrNull() ?: 0 }.let { if (it.size == 2) it else listOf(8, 0) }
        android.app.TimePickerDialog(ctx, { _, hh, mm -> set("%02d:%02d".format(hh, mm)) }, h, m, true).show()
    }

    fun pick(current: String, set: (String) -> Unit) {
        val cal = Calendar.getInstance(ViaggiaTrenoApi.ROME)
        DatePickerDialog(ctx, { _, y, m, d -> set("%02d/%02d/%04d".format(d, m + 1, y)) },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }
    fun startMs() = Subscription("", "", "", "", "", "", validFrom = validFrom).fromMs ?: System.currentTimeMillis()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title ?: if (initial == null) "Nuovo abbonamento" else "Modifica abbonamento") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nome (es. Casa–Lavoro)") }, singleLine = true)
                StationField("Da", from) { from = it }
                StationField("A", to) { to = it }
                Text("Tipo", style = MaterialTheme.typography.labelMedium)
                SubscriptionType.entries.forEach { t ->
                    FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.label) })
                }
                Text("Operatore", style = MaterialTheme.typography.labelMedium)
                SubscriptionOperator.entries.forEach { o ->
                    FilterChip(selected = operator == o, onClick = { operator = o }, label = { Text(o.label) })
                }
                Text("Validità", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(onClick = { pick(validFrom) { validFrom = it } }, label = { Text("Dal ${validFrom.ifEmpty { "…" }}") })
                    AssistChip(onClick = { pick(validTo) { validTo = it } }, label = { Text("Al ${validTo.ifEmpty { "…" }}") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { validTo = Subscription.formatDate(Subscription.endAfter(startMs(), 1)) }) { Text("Mensile") }
                    TextButton(onClick = { validTo = Subscription.formatDate(Subscription.endAfter(startMs(), 3)) }) { Text("Trimestrale") }
                    TextButton(onClick = { validTo = Subscription.formatDate(Subscription.endAfter(startMs(), 12)) }) { Text("Annuale") }
                }
                OutlinedTextField(holder, { holder = it }, label = { Text("Intestatario (facoltativo)") }, singleLine = true)
                Text("Orari abituali (per gli avvisi sui treni dell'abbonamento)", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(onClick = { pickTime(outbound) { outbound = it } }, label = { Text(if (outbound.isEmpty()) "Andata: —" else "Andata ~$outbound") })
                    AssistChip(onClick = { pickTime(back) { back = it } }, label = { Text(if (back.isEmpty()) "Ritorno: —" else "Ritorno ~$back") })
                }
                if (outbound.isNotEmpty() || back.isNotEmpty()) {
                    TextButton(onClick = { outbound = ""; back = "" }) { Text("Rimuovi orari") }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = from != null && to != null, onClick = {
                val a = from!!; val b = to!!
                onSave(
                    (initial ?: Subscription(Subscription.newId(), "", "", "", "", "")).copy(
                        name = name.trim(), fromId = a.id, fromName = a.name, toId = b.id, toName = b.name,
                        type = type, operator = operator, validFrom = validFrom, validTo = validTo, holder = holder.trim(),
                        outboundTime = outbound, returnTime = back,
                    )
                )
            }) { Text("Salva") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

/** Codice a schermo intero, fondo bianco e luminosità massima. */
@Composable
fun FullscreenCode(b: android.graphics.Bitmap, onClose: () -> Unit) {
    val activity = LocalContext.current as? android.app.Activity
    androidx.compose.runtime.DisposableEffect(Unit) {
        val w = activity?.window
        val old = w?.attributes?.screenBrightness
        w?.attributes = w?.attributes?.apply { screenBrightness = 1f }
        onDispose { w?.attributes = w?.attributes?.apply { screenBrightness = old ?: -1f } }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.White).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
            Image(b.asImageBitmap(), "Codice", filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize().padding(24.dp))
        }
    }
}
