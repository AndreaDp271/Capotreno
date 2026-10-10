package io.github.andreadp.trenolibero.ui

import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.andreadp.trenolibero.data.InfoNews
import io.github.andreadp.trenolibero.data.Statistiche
import io.github.andreadp.trenolibero.data.ViaggiaTrenoApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private class InfoData(val stats: Statistiche?, val ticker: List<String>, val news: List<InfoNews>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoScreen(nav: Nav) {
    val state = rememberLoader(Unit, refreshMs = 120_000) {
        coroutineScope {
            val s = async { runCatching { ViaggiaTrenoApi.statistics() }.getOrNull() }
            val t = async { runCatching { ViaggiaTrenoApi.ticker() }.getOrDefault(emptyList()) }
            val n = async { ViaggiaTrenoApi.infomobility() }
            InfoData(s.await(), t.await(), n.await())
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Infomobilità") },
                actions = { IconButton(onClick = state.reload) { Icon(Icons.Default.Refresh, "Aggiorna") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (state.loading && state.data != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            LoadedContent(state) { d ->
                LazyColumn(Modifier.fillMaxSize()) {
                    d.stats?.let { s -> item { StatsCard(s) } }
                    if (d.ticker.isNotEmpty()) {
                        item { SectionTitle("In evidenza") }
                        items(d.ticker) { t ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.Default.Warning, null, tint = Palette.late)
                                Text(t, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    item { SectionTitle("Avvisi sulla circolazione") }
                    if (d.news.isEmpty()) item { EmptyText("Nessun avviso") }
                    items(d.news) { n -> NewsCard(n) }
                    item {
                        Text(
                            "Dati: ViaggiaTreno (RFI/Trenitalia). App non ufficiale.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsCard(s: Statistiche) {
    Card(
        Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceAround) {
            Stat("${s.treniCircolanti ?: "-"}", "treni in circolazione")
            Stat("${s.treniGiorno ?: "-"}", "treni oggi")
        }
        Text(
            "Aggiornato alle ${fmtTime(s.ultimoAggiornamento)}",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 16.dp, bottom = 12.dp),
        )
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun NewsCard(n: InfoNews) {
    var open by rememberSaveable(n.title) { mutableStateOf(false) }
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).animateContentSize()
            .clickable { open = !open },
    ) {
        Column(Modifier.padding(16.dp)) {
            if (n.date.isNotEmpty()) Text(n.date, style = MaterialTheme.typography.labelSmall)
            Text(
                n.title,
                fontWeight = if (n.highlighted) FontWeight.Bold else FontWeight.Medium,
                style = MaterialTheme.typography.titleSmall,
            )
            if (open) {
                AndroidView(
                    factory = { ctx ->
                        TextView(ctx).apply {
                            movementMethod = LinkMovementMethod.getInstance()
                            setTextColor(textColor)
                            setLinkTextColor(linkColor)
                            textSize = 14f
                        }
                    },
                    update = { it.text = android.text.Html.fromHtml(n.html, android.text.Html.FROM_HTML_MODE_COMPACT) },
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                Text("Tocca per i dettagli", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
