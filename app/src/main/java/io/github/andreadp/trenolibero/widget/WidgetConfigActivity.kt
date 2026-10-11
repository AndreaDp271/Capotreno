package io.github.andreadp.trenolibero.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import io.github.andreadp.trenolibero.data.Graph
import io.github.andreadp.trenolibero.ui.StationField
import io.github.andreadp.trenolibero.ui.TrenoTheme
import kotlinx.coroutines.launch

/** Scelta di cosa mostra un widget: treno, stazione o abbonamento, secondo il tipo di widget. */
class WidgetConfigActivity : ComponentActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Graph.init(this)
        widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // chiudendo senza scegliere, il widget non viene aggiunto
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider?.className.orEmpty()
        setContent {
            TrenoTheme {
                when {
                    provider.endsWith("TrainWidgetReceiver") -> TrainPicker(::save)
                    provider.endsWith("DeparturesWidgetReceiver") -> StationPicker(::save)
                    provider.endsWith("SubscriptionWidgetReceiver") -> SubscriptionPicker(::save)
                    else -> androidx.compose.runtime.LaunchedEffect(Unit) { save("") }
                }
            }
        }
    }

    private fun save(value: String) {
        WidgetPrefs.setSelection(this, widgetId, value)
        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(widgetId)
            val provider = AppWidgetManager.getInstance(this@WidgetConfigActivity).getAppWidgetInfo(widgetId)?.provider?.className.orEmpty()
            when {
                provider.endsWith("TrainWidgetReceiver") -> TrainWidget().update(this@WidgetConfigActivity, glanceId)
                provider.endsWith("DeparturesWidgetReceiver") -> DeparturesWidget().update(this@WidgetConfigActivity, glanceId)
                provider.endsWith("SubscriptionWidgetReceiver") -> SubscriptionWidget().update(this@WidgetConfigActivity, glanceId)
            }
            Widgets.schedule(this@WidgetConfigActivity)
            setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerScaffold(title: String, content: @Composable () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(title) }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) { content() }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
}

@Composable
private fun Section(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}

/** Treni preferiti e treni dei biglietti dei prossimi giorni. */
@Composable
private fun TrainPicker(onPick: (String) -> Unit) {
    val favs by Graph.favorites.trains.collectAsState()
    val tickets by Graph.tickets.tickets.collectAsState()
    val today = System.currentTimeMillis() - 24 * 3600_000L
    val ticketTrains = tickets.filter { !it.isSubscription && (it.dateMs ?: 0) >= today }
        .flatMap { t -> t.trains.map { tr -> Triple(tr, t, t.dateMs) } }
    PickerScaffold("Quale treno?") {
        LazyColumn {
            if (favs.isEmpty() && ticketTrains.isEmpty()) {
                item { Hint("Non hai treni preferiti né biglietti in programma. Nell'app apri un treno e tocca il cuore per aggiungerlo ai preferiti, poi aggiungi di nuovo il widget.") }
            }
            if (favs.isNotEmpty()) item { Section("Treni preferiti") }
            items(favs) { t ->
                ListItem(
                    headlineContent = { Text(t.label) },
                    supportingContent = { Text("Il treno di oggi, ogni giorno") },
                    modifier = Modifier.clickable { onPick("${t.number}|${t.originId}||${t.label}") },
                )
                HorizontalDivider()
            }
            if (ticketTrains.isNotEmpty()) item { Section("Dai tuoi biglietti") }
            items(ticketTrains) { (tr, t, date) ->
                ListItem(
                    headlineContent = { Text("${tr.category} ${tr.number}".trim()) },
                    supportingContent = { Text("${t.title} · ${t.date} ${t.departure}") },
                    modifier = Modifier.clickable { onPick("${tr.number}||${date ?: ""}|${"${tr.category} ${tr.number}".trim()} · ${t.title}") },
                )
                HorizontalDivider()
            }
        }
    }
}

/** Stazioni preferite oppure ricerca. */
@Composable
private fun StationPicker(onPick: (String) -> Unit) {
    val favs by Graph.favorites.stations.collectAsState()
    PickerScaffold("Quale stazione?") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StationField("Cerca una stazione", null) { s -> if (s != null) onPick("${s.id}|${s.name}") }
        }
        LazyColumn {
            if (favs.isNotEmpty()) item { Section("Stazioni preferite") }
            items(favs) { s ->
                ListItem(headlineContent = { Text(s.name) }, modifier = Modifier.clickable { onPick("${s.id}|${s.name}") })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SubscriptionPicker(onPick: (String) -> Unit) {
    val subs by Graph.subscriptions.items.collectAsState()
    PickerScaffold("Quale abbonamento?") {
        LazyColumn {
            if (subs.isEmpty()) item { Hint("Non hai abbonamenti: aggiungine uno nell'app (Biglietti → Abbonamenti), poi aggiungi di nuovo il widget.") }
            items(subs) { s ->
                ListItem(
                    headlineContent = { Text("${s.fromName} ⇄ ${s.toName}") },
                    supportingContent = { Text(s.name.ifEmpty { "Abbonamento" } + if (s.validTo.isNotBlank()) " · fino al ${s.validTo}" else "") },
                    modifier = Modifier.clickable { onPick(s.id) },
                )
                HorizontalDivider()
            }
        }
    }
}
