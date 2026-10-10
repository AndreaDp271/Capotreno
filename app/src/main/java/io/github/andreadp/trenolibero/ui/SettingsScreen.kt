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
fun SettingsScreen(nav: Nav) {
    val ctx = LocalContext.current
    val settings = remember { NotifySettings(ctx) }
    var favorites by remember { mutableStateOf(settings.favorites) }
    var tickets by remember { mutableStateOf(settings.tickets) }
    var strikes by remember { mutableStateOf(settings.strikes) }
    var subs by remember { mutableStateOf(settings.subscriptions) }
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
            Text(
                "I controlli avvengono sul telefono circa ogni 15 minuti, solo con connessione e solo se hai attivato " +
                    "almeno una notifica (nessun server esterno). Android può ritardarli in risparmio energetico.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
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
