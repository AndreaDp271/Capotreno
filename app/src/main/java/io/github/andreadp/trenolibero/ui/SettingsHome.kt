package io.github.andreadp.trenolibero.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.andreadp.trenolibero.BuildConfig
import io.github.andreadp.trenolibero.R
import io.github.andreadp.trenolibero.data.AppSettings
import io.github.andreadp.trenolibero.data.Backup
import io.github.andreadp.trenolibero.data.CacheInfo
import io.github.andreadp.trenolibero.data.NotifySettings
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val REPO = "https://github.com/AndreaDp271/Capotreno"

private val startTabs = listOf(
    "home" to "Treni", "stations" to "Stazioni", "map" to "Mappa", "tickets" to "Biglietti", "info" to "Info",
)

/** Colori delle icone delle voci, uno per gruppo. */
private object SetColors {
    val look = Color(0xFF8E24AA)
    val general = Color(0xFF1565C0)
    val alerts = Color(0xFFFF8F00)
    val data = Color(0xFF2E7D32)
    val about = Color(0xFF546E7A)
}

/** Impostazioni dell'app, divise per gruppi. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val theme by AppSettings.theme.collectAsState()
    val dynamic by AppSettings.dynamicColor.collectAsState()
    var startTab by remember { mutableStateOf(AppSettings.startTab) }
    var refresh by remember { mutableStateOf(AppSettings.refresh) }
    var screenOn by remember { mutableStateOf(AppSettings.keepScreenOn) }
    var qrBright by remember { mutableStateOf(AppSettings.qrBrightness) }
    var startDialog by remember { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var cacheDialog by remember { mutableStateOf(false) }
    var cacheSize by remember { mutableStateOf<Long?>(null) }
    var cacheTick by remember { mutableStateOf(0) }
    LaunchedEffect(cacheTick) { cacheSize = CacheInfo.size(ctx) }

    val notifyCount = remember {
        NotifySettings(ctx).run {
            listOf(tickets, favorites, strikes, subscriptions, subscriptionTrains, disruptions, digest).count { it }
        }
    }
    val widgetCount = remember { countWidgets(ctx) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val msg = runCatching { Backup.export(ctx, uri) }
                .fold({ "Backup salvato: $it" }, { "Backup non riuscito: ${it.message}" })
            snackbar.showSnackbar(msg)
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        restoreUri = uri
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            LargeTopAppBar(
                title = { Text("Impostazioni") },
                scrollBehavior = scroll,
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AppCard()

            Group("Aspetto") {
                Item(painterResource(R.drawable.ic_set_moon), SetColors.look, "Tema", "Chiaro, scuro o come il sistema")
                Choice(AppSettings.Theme.entries, theme, { it.label }) { AppSettings.setTheme(it) }
                if (Build.VERSION.SDK_INT >= 31) {
                    Divider()
                    Item(
                        painterResource(R.drawable.ic_set_palette), SetColors.look, "Colori dinamici",
                        "Usa i colori dello sfondo del telefono (Material You)",
                        trailing = { Switch(dynamic, { AppSettings.setDynamicColor(it) }) },
                        onClick = { AppSettings.setDynamicColor(!dynamic) },
                    )
                }
            }

            Group("Generale") {
                Item(
                    painterResource(R.drawable.ic_set_start), SetColors.general, "Schermata iniziale",
                    startTabs.firstOrNull { it.first == startTab }?.second ?: "Treni",
                    trailing = { Chevron() }, onClick = { startDialog = true },
                )
                Divider()
                Item(rememberVectorPainter(Icons.Default.Refresh), SetColors.general, "Aggiornamento automatico", refreshHint(refresh))
                Choice(AppSettings.Refresh.entries, refresh, { it.label }) { AppSettings.refresh = it; refresh = it }
                Divider()
                Item(
                    painterResource(R.drawable.ic_set_phone), SetColors.general, "Schermo sempre acceso",
                    "Mentre segui un treno, nella pagina e sulla mappa",
                    trailing = { Switch(screenOn, { AppSettings.keepScreenOn = it; screenOn = it }) },
                    onClick = { AppSettings.keepScreenOn = !screenOn; screenOn = !screenOn },
                )
                Divider()
                Item(
                    painterResource(R.drawable.ic_set_brightness), SetColors.general, "Luminosità massima sui codici",
                    "Quando mostri il QR di un biglietto o abbonamento a schermo intero",
                    trailing = { Switch(qrBright, { AppSettings.qrBrightness = it; qrBright = it }) },
                    onClick = { AppSettings.qrBrightness = !qrBright; qrBright = !qrBright },
                )
            }

            Group("Notifiche e widget") {
                Item(
                    rememberVectorPainter(Icons.Default.Notifications), SetColors.alerts, "Notifiche",
                    if (notifyCount == 0) "Nessuna attiva" else "$notifyCount attive · ritardi, scioperi, biglietti…",
                    trailing = { Chevron() }, onClick = { nav.notificationSettings() },
                )
                Divider()
                Item(
                    painterResource(R.drawable.ic_set_widgets), SetColors.alerts, "Widget",
                    if (widgetCount == 0) "Aggiungi treni, partenze e biglietti alla home" else "$widgetCount sulla schermata home",
                    trailing = { Chevron() }, onClick = { nav.widgetSettings() },
                )
            }

            Group("Dati") {
                Item(
                    painterResource(R.drawable.ic_set_backup), SetColors.data, "Esporta backup",
                    "Preferiti, biglietti, abbonamenti, sveglie e impostazioni in un file .zip",
                    onClick = {
                        val day = SimpleDateFormat("yyyyMMdd", Locale.ITALY).format(Date())
                        exportLauncher.launch("capotreno-backup-$day.zip")
                    },
                )
                Divider()
                Item(
                    painterResource(R.drawable.ic_set_restore), SetColors.data, "Ripristina backup",
                    "Sostituisce i dati attuali con quelli di un backup",
                    onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                )
                Divider()
                Item(
                    painterResource(R.drawable.ic_set_cleanup), SetColors.data, "Svuota cache",
                    cacheSize?.let { "${formatSize(it)} di mappe e stazioni scaricate" } ?: "Calcolo…",
                    onClick = { cacheDialog = true },
                )
            }

            Group("Informazioni") {
                Item(
                    painterResource(R.drawable.ic_set_code), SetColors.about, "Codice sorgente",
                    "github.com/AndreaDp271/Capotreno", onClick = { open(ctx, REPO) },
                )
                Divider()
                Item(
                    painterResource(R.drawable.ic_set_bug), SetColors.about, "Segnala un problema",
                    "Apri una segnalazione su GitHub", onClick = { open(ctx, "$REPO/issues") },
                )
                Divider()
                Item(
                    painterResource(R.drawable.ic_set_license), SetColors.about, "Licenza",
                    "GNU GPL 3.0 o successiva", onClick = { open(ctx, "$REPO/blob/main/LICENSE") },
                )
            }
            Text(
                "Dati di circolazione da ViaggiaTreno (RFI) e LeFrecce (Trenitalia). Mappe © contributori di OpenStreetMap.\n" +
                    "Capotreno non è un'app ufficiale e non è collegata a Trenitalia, RFI o al Gruppo FS.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    if (startDialog) {
        AlertDialog(
            onDismissRequest = { startDialog = false },
            title = { Text("Schermata iniziale") },
            text = {
                Column {
                    startTabs.forEach { (route, label) ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .selectable(route == startTab, role = Role.RadioButton) {
                                    AppSettings.startTab = route; startTab = route; startDialog = false
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(route == startTab, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
                            Text(label)
                        }
                    }
                    Text(
                        "Vale dal prossimo avvio dell'app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { startDialog = false }) { Text("Chiudi") } },
        )
    }

    restoreUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { restoreUri = null },
            title = { Text("Ripristinare il backup?") },
            text = { Text("Preferiti, biglietti, abbonamenti, sveglie e impostazioni attuali verranno sostituiti. Poi l'app si riavvia.") },
            confirmButton = {
                TextButton(onClick = {
                    restoreUri = null
                    scope.launch {
                        runCatching { Backup.import(ctx, uri) }
                            .onSuccess { restart(ctx) }
                            .onFailure { snackbar.showSnackbar(it.message ?: "Ripristino non riuscito") }
                    }
                }) { Text("Ripristina") }
            },
            dismissButton = { TextButton(onClick = { restoreUri = null }) { Text("Annulla") } },
        )
    }

    if (cacheDialog) {
        AlertDialog(
            onDismissRequest = { cacheDialog = false },
            title = { Text("Svuotare la cache?") },
            text = { Text("Le tessere della mappa e l'elenco delle stazioni verranno scaricati di nuovo quando servono. Biglietti, preferiti e abbonamenti restano.") },
            confirmButton = {
                TextButton(onClick = {
                    cacheDialog = false
                    scope.launch {
                        CacheInfo.clear(ctx)
                        cacheTick++
                        snackbar.showSnackbar("Cache svuotata")
                    }
                }) { Text("Svuota") }
            },
            dismissButton = { TextButton(onClick = { cacheDialog = false }) { Text("Annulla") } },
        )
    }
}

/** Intestazione con icona, nome e versione dell'app. */
@Composable
private fun AppCard() {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF0B5D3B), Color(0xFF1565C0)))),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(18.dp))) {
                Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize())
                Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
            }
            Column(Modifier.padding(start = 16.dp)) {
                Text("Capotreno", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
                Text("Versione ${BuildConfig.VERSION_NAME}", color = Color.White.copy(alpha = 0.9f))
                Text(
                    "Software libero, senza pubblicità né tracciamento",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.75f),
                )
            }
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
    )
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 1.dp) {
        Column(Modifier.padding(vertical = 4.dp)) { content() }
    }
}

@Composable
private fun Item(
    icon: Painter,
    color: Color,
    title: String,
    subtitle: String?,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    // nel tema scuro le icone schiarite restano leggibili sul fondo
    val tint = if (isDarkTheme()) androidx.compose.ui.graphics.lerp(color, Color.White, 0.35f) else color
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp)) }
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.let { Spacer(Modifier.width(8.dp)); it() }
    }
}

/** Pulsanti a segmenti sotto una voce, allineati al testo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choice(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
        options.forEachIndexed { i, o ->
            SegmentedButton(
                selected = o == selected,
                onClick = { onSelect(o) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                icon = {},
            ) { Text(label(o), maxLines = 1, style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
private fun Divider() = HorizontalDivider(
    Modifier.padding(start = 72.dp, end = 16.dp),
    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
)

@Composable
private fun Chevron() = Icon(
    Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
    tint = MaterialTheme.colorScheme.onSurfaceVariant,
)

private fun refreshHint(r: AppSettings.Refresh): String = when (val f = r.factor) {
    null -> "Solo a mano o riaprendo la schermata"
    else -> "Treno ogni ${(30 * f).toInt()} s, stazioni ogni ${(60 * f).toInt()} s"
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format(Locale.ITALY, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} kB"
    else -> "$bytes B"
}

private fun countWidgets(ctx: android.content.Context): Int {
    val m = android.appwidget.AppWidgetManager.getInstance(ctx)
    return listOf(
        io.github.andreadp.trenolibero.widget.TrainWidgetReceiver::class.java,
        io.github.andreadp.trenolibero.widget.DeparturesWidgetReceiver::class.java,
        io.github.andreadp.trenolibero.widget.SubscriptionWidgetReceiver::class.java,
        io.github.andreadp.trenolibero.widget.TicketWidgetReceiver::class.java,
        io.github.andreadp.trenolibero.widget.InfoWidgetReceiver::class.java,
    ).sumOf { m.getAppWidgetIds(android.content.ComponentName(ctx, it)).size }
}

private fun open(ctx: android.content.Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Riavvia l'app da zero, così i dati ripristinati vengono riletti. */
private fun restart(ctx: android.content.Context) {
    val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)!!
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    ctx.startActivity(intent)
    Runtime.getRuntime().exit(0)
}
