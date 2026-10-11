package io.github.andreadp.trenolibero.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.andreadp.trenolibero.data.Notifier
import io.github.andreadp.trenolibero.data.NotifySettings

/** Impostazioni delle notifiche. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(nav: Nav) {
    val ctx = LocalContext.current
    val settings = remember { NotifySettings(ctx) }
    var favorites by remember { mutableStateOf(settings.favorites) }
    var tickets by remember { mutableStateOf(settings.tickets) }
    var strikes by remember { mutableStateOf(settings.strikes) }
    var subs by remember { mutableStateOf(settings.subscriptions) }
    var digest by remember { mutableStateOf(settings.digest) }
    var digestStation by remember { mutableStateOf(settings.digestStation) }
    var digestDest by remember { mutableStateOf(settings.digestDestination) }
    var digestTime by remember { mutableStateOf(settings.digestTime) }
    var digestWeekdays by remember { mutableStateOf(settings.digestWeekdays) }
    var disruptions by remember { mutableStateOf(settings.disruptions) }
    var subTrains by remember { mutableStateOf(settings.subscriptionTrains) }
    var threshold by remember { mutableFloatStateOf(settings.delayThreshold.toFloat()) }
    var reminder by remember { mutableFloatStateOf(settings.reminderMinutes.toFloat()) }
    var denied by remember { mutableStateOf(false) }
    // azione da completare dopo la concessione del permesso di notifica
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) pending?.invoke() else denied = true
        pending = null
    }

    fun toggle(value: Boolean, apply: (Boolean) -> Unit) {
        val action = { apply(value); Notifier.createChannels(ctx); Notifier.schedule(ctx) }
        if (value && !Notifier.canNotify(ctx) && Build.VERSION.SDK_INT >= 33) {
            pending = action
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else action()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notifiche") },
                navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            if (denied) {
                Text(
                    "Permesso di notifica negato: puoi concederlo dalle impostazioni di Android.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }
            SettingSwitch("Biglietti", "Promemoria prima della partenza (binario, ritardo, carrozza e posto) e variazioni del treno il giorno del viaggio", tickets) {
                toggle(it) { v -> settings.tickets = v; tickets = v }
            }
            SettingSwitch("Treni preferiti", "Ritardi oltre la soglia, cambi di binario e soppressioni, da un'ora prima della partenza all'arrivo", favorites) {
                toggle(it) { v -> settings.favorites = v; favorites = v }
            }
            SettingSwitch("Scioperi", "Nuovi scioperi annunciati in Infomobilità", strikes) {
                toggle(it) { v -> settings.strikes = v; strikes = v }
            }
            SettingSwitch("Scadenza abbonamenti", "7, 3 e 1 giorno prima e il giorno della scadenza", subs) {
                toggle(it) { v -> settings.subscriptions = v; subs = v }
            }
            SettingSwitch("Treni dell'abbonamento", "Ritardi e soppressioni dei treni compatibili intorno agli orari abituali indicati nell'abbonamento", subTrains) {
                toggle(it) { v -> settings.subscriptionTrains = v; subTrains = v }
            }
            SettingSwitch("Disagi sulle tue linee", "Guasti e rallentamenti che riguardano le località di abbonamenti, stazioni e treni preferiti", disruptions) {
                toggle(it) { v -> settings.disruptions = v; disruptions = v }
            }
            SettingSwitch("Riepilogo partenze", "Ogni giorno all'orario scelto, i prossimi treni dalla tua stazione", digest) {
                toggle(it) { v -> settings.digest = v; digest = v }
            }
            if (digest) {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    fun ref(s: String) = s.split("|", limit = 2).takeIf { it.size == 2 }?.let { io.github.andreadp.trenolibero.data.StationRef(it[0], it[1]) }
                    StationField("Stazione", ref(digestStation)) { s ->
                        digestStation = s?.let { "${it.id}|${it.name}" }.orEmpty(); settings.digestStation = digestStation
                    }
                    StationField("Destinazione (facoltativa)", ref(digestDest)) { s ->
                        digestDest = s?.let { "${it.id}|${it.name}" }.orEmpty(); settings.digestDestination = digestDest
                    }
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    ) {
                        androidx.compose.material3.AssistChip(
                            onClick = {
                                val (h, m) = digestTime.split(":").map { it.toIntOrNull() ?: 0 }
                                android.app.TimePickerDialog(ctx, { _, hh, mm ->
                                    digestTime = "%02d:%02d".format(hh, mm); settings.digestTime = digestTime
                                }, h, m, true).show()
                            },
                            label = { Text("Alle $digestTime") },
                        )
                        Text("Solo giorni feriali")
                        Switch(checked = digestWeekdays, onCheckedChange = { digestWeekdays = it; settings.digestWeekdays = it })
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("Avvisa per ritardi da ${threshold.toInt()} minuti")
            Slider(
                value = threshold, onValueChange = { threshold = it }, valueRange = 1f..30f, steps = 28,
                onValueChangeFinished = { settings.delayThreshold = threshold.toInt() },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            SectionTitle("Promemoria biglietto ${reminder.toInt()} minuti prima")
            Slider(
                value = reminder, onValueChange = { reminder = it }, valueRange = 15f..120f, steps = 6,
                onValueChangeFinished = { settings.reminderMinutes = reminder.toInt() },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            BackgroundHint()
            Text(
                "I controlli avvengono sul telefono circa ogni 15 minuti, solo con connessione e solo se hai attivato " +
                    "almeno una notifica (nessun server esterno). Android può ritardarli in risparmio energetico.\n\n" +
                    "La sveglia prima della fermata si imposta dalla schermata del treno (icona della campanella).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

/** Widget per la schermata home: aggiunta rapida e consigli per l'aggiornamento in background. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetSettingsScreen(nav: Nav) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Widget") },
                navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                "Aggiungi un widget alla schermata home: treno seguito, partenze di una stazione, prossimi treni " +
                    "dell'abbonamento, prossimo biglietto con codice QR e avvisi di Infomobilità.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
            WidgetButtons()
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            BackgroundHint()
            Text(
                "I widget si aggiornano circa ogni 15 minuti, uscendo dall'app e toccando il pulsante di aggiornamento.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

/**
 * Su molti telefoni (ColorOS, MIUI, One UI…) il risparmio energetico blocca l'app in background:
 * widget e notifiche smettono di aggiornarsi finché non si consente l'attività in background.
 */
@Composable
private fun BackgroundHint() {
    val ctx = LocalContext.current
    val pm = ctx.getSystemService(android.os.PowerManager::class.java)
    val restricted = !pm.isIgnoringBatteryOptimizations(ctx.packageName)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            if (restricted) "Il risparmio energetico può fermare l'aggiornamento di widget e notifiche. " +
                "Apri le impostazioni dell'app → Batteria e consenti l'attività in background (nessuna restrizione)."
            else "Attività in background consentita: widget e notifiche si aggiornano regolarmente.",
            style = MaterialTheme.typography.bodySmall,
            color = if (restricted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (restricted) {
            androidx.compose.material3.TextButton(onClick = {
                ctx.startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", ctx.packageName, null),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }) { Text("Apri impostazioni batteria dell'app") }
        }
    }
}

/** Propone al launcher di aggiungere un widget (Android 8+, se il launcher lo supporta). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun WidgetButtons() {
    val ctx = LocalContext.current
    val manager = android.appwidget.AppWidgetManager.getInstance(ctx)
    val canPin = Build.VERSION.SDK_INT >= 26 && manager.isRequestPinAppWidgetSupported
    val widgets = listOf(
        "Treno" to io.github.andreadp.trenolibero.widget.TrainWidgetReceiver::class.java,
        "Partenze" to io.github.andreadp.trenolibero.widget.DeparturesWidgetReceiver::class.java,
        "Abbonamento" to io.github.andreadp.trenolibero.widget.SubscriptionWidgetReceiver::class.java,
        "Biglietto" to io.github.andreadp.trenolibero.widget.TicketWidgetReceiver::class.java,
        "Infomobilità" to io.github.andreadp.trenolibero.widget.InfoWidgetReceiver::class.java,
    )
    if (!canPin) {
        Text(
            "Tieni premuto su uno spazio vuoto della schermata home, scegli Widget e cerca Capotreno.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        return
    }
    androidx.compose.foundation.layout.FlowRow(
        Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        widgets.forEach { (label, cls) ->
            androidx.compose.material3.AssistChip(
                onClick = { manager.requestPinAppWidget(android.content.ComponentName(ctx, cls), null, null) },
                label = { Text("+ $label") },
            )
        }
    }
}

@Composable
private fun SettingSwitch(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}
