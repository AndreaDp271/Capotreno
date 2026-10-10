package io.github.andreadp.trenolibero.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.andreadp.trenolibero.data.TrenoTratta
import io.github.andreadp.trenolibero.data.ViaggiaTrenoApi

/** Treni attualmente in transito su una linea, nelle due direzioni (come "dettaglio tratta" del sito). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LineScreen(nav: Nav, region: Int, ab: Int, ba: Int) {
    val state = rememberLoader(region, ab, ba, refreshMs = 60_000) { ViaggiaTrenoApi.lineTrains(region, ab, ba) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.data?.firstOrNull()?.tratta ?: "Linea", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
                },
                actions = { IconButton(onClick = state.reload) { Icon(Icons.Default.Refresh, "Aggiorna") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (state.loading && state.data != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            LoadedContent(state) { dirs ->
                LazyColumn(Modifier.fillMaxSize()) {
                    dirs.forEach { d ->
                        item {
                            SectionTitle("${d.tratta} · ${d.treni.size} ${if (d.treni.size == 1) "treno" else "treni"}")
                        }
                        if (d.treni.isEmpty()) item { EmptyText("Nessun treno in transito") }
                        items(d.treni) { t ->
                            LineTrainRow(t) {
                                nav.train(t.numeroTreno.toString(), t.codOrigine, t.dataPartenzaTreno.takeIf { t.codOrigine != null })
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LineTrainRow(t: TrenoTratta, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(96.dp)) {
            Text(t.title, fontWeight = FontWeight.Bold)
            if (t.provvedimento != 1 && t.nonPartito != true) DelayBadge(t.ritardo)
        }
        Column(Modifier.weight(1f)) {
            Text(
                "${t.origine.orEmpty()} → ${t.destinazione.orEmpty()}",
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${t.compOrarioPartenza.orEmpty()} – ${t.compOrarioArrivo.orEmpty()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
