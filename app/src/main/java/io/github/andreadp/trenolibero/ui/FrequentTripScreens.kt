package io.github.andreadp.trenolibero.ui

import android.Manifest
import android.app.TimePickerDialog
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.andreadp.trenolibero.data.FrequentTrip
import io.github.andreadp.trenolibero.data.FrequentTrips
import io.github.andreadp.trenolibero.data.Notifier

/** Creazione o modifica di un viaggio frequente: giorni, fascia oraria e anticipo dell'avviso. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FrequentTripDialog(initial: FrequentTrip, isNew: Boolean, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var days by remember { mutableStateOf(initial.days) }
    var start by remember { mutableStateOf(initial.start) }
    var end by remember { mutableStateOf(initial.end) }
    var lead by remember { mutableStateOf(initial.leadMinutes) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun pick(value: String, set: (String) -> Unit) {
        val (h, m) = value.split(":").map { it.toIntOrNull() ?: 0 }
        TimePickerDialog(ctx, { _, hh, mm -> set("%02d:%02d".format(hh, mm)) }, h, m, true).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Viaggio frequente" else initial.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (isNew) Text(initial.title, style = MaterialTheme.typography.titleSmall)
                Text("Ti avviso prima della partenza di ogni treno diretto nella fascia scelta, con binario e ritardo aggiornati.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Giorni", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FrequentTrip.WEEK.forEach { (d, label) ->
                        val on = d in days
                        Box(
                            Modifier.size(34.dp).clip(CircleShape)
                                .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { days = if (on) days - d else days + d },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(label, style = MaterialTheme.typography.labelLarge,
                                color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text("Partenze", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { pick(start) { start = it } }, label = { Text("Dalle $start") })
                    AssistChip(onClick = { pick(end) { end = it } }, label = { Text("Alle $end") })
                }
                Text("Avvisami", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(5, 10, 15, 30).forEach { v ->
                        FilterChip(selected = lead == v, onClick = { lead = v }, label = { Text("$v min") })
                    }
                }
                Text("prima della partenza", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                enabled = days.isNotEmpty(),
                onClick = {
                    FrequentTrips.save(ctx, initial.copy(days = days, start = start, end = end, leadMinutes = lead, enabled = true))
                    Notifier.createChannels(ctx)
                    if (!Notifier.canNotify(ctx) && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    onDismiss()
                },
            ) { Text("Salva") }
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = { FrequentTrips.remove(ctx, initial.id); onDismiss() }) { Text("Elimina") }
                TextButton(onClick = onDismiss) { Text("Annulla") }
            }
        },
    )
}

/** Elenco dei viaggi frequenti nelle impostazioni delle notifiche: si accendono, spengono e modificano da qui. */
@Composable
fun FrequentTripsSection() {
    val ctx = LocalContext.current
    remember { FrequentTrips.all(ctx) }
    val trips by FrequentTrips.items.collectAsState()
    var editing by remember { mutableStateOf<FrequentTrip?>(null) }
    SectionTitle("Viaggi frequenti")
    if (trips.isNullOrEmpty()) {
        Text(
            "Nessun viaggio frequente. In Cerca viaggio scegli partenza e arrivo, poi tocca l'icona del calendario per farti avvisare prima dei treni negli orari che usi.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    trips.orEmpty().forEach { t ->
        ListItem(
            headlineContent = { Text(t.title) },
            supportingContent = { Text(t.summary()) },
            trailingContent = {
                Switch(checked = t.enabled, onCheckedChange = { FrequentTrips.save(ctx, t.copy(enabled = it)) })
            },
            modifier = Modifier.clickable { editing = t },
        )
    }
    editing?.let { FrequentTripDialog(it, isNew = false) { editing = null } }
}
