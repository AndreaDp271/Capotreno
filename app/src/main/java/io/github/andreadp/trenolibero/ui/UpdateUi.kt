package io.github.andreadp.trenolibero.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.andreadp.trenolibero.BuildConfig
import io.github.andreadp.trenolibero.data.AppSettings
import io.github.andreadp.trenolibero.data.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Stato condiviso del controllo aggiornamenti (scheda Opzioni e avviso all'avvio). */
object UpdateUi {
    sealed interface Phase {
        data object Idle : Phase
        data object Checking : Phase
        data object UpToDate : Phase
        data class Available(val rel: Updater.Release) : Phase
        data class Downloading(val rel: Updater.Release, val progress: Float?) : Phase
        data class Installing(val rel: Updater.Release) : Phase
        data class Failed(val message: String) : Phase
    }

    var phase by mutableStateOf<Phase>(Phase.Idle)

    /** true quando il dialogo va mostrato (nuova versione, download o errore dopo un controllo manuale). */
    var dialog by mutableStateOf(false)

    fun check(scope: CoroutineScope, manual: Boolean) {
        if (phase is Phase.Checking || phase is Phase.Downloading) return
        phase = Phase.Checking
        scope.launch {
            phase = runCatching { Updater.latest() }.fold(
                { if (Updater.isNewer(it.version)) Phase.Available(it) else Phase.UpToDate },
                { Phase.Failed(errorMessage(it)) },
            )
            AppSettings.lastUpdateCheck = System.currentTimeMillis()
            dialog = phase is Phase.Available || (manual && phase is Phase.Failed)
        }
    }

    /** Controllo automatico all'avvio: al massimo una volta al giorno e mai per le installazioni da F-Droid. */
    fun checkOnStart(ctx: Context, scope: CoroutineScope) {
        if (Updater.fromFDroid(ctx) || !AppSettings.autoUpdateCheck) return
        if (System.currentTimeMillis() - AppSettings.lastUpdateCheck < 24 * 3600_000L) return
        check(scope, manual = false)
    }

    fun install(ctx: Context, scope: CoroutineScope, rel: Updater.Release) {
        if (!Updater.canInstall(ctx) && Build.VERSION.SDK_INT >= 26) {
            // Android chiede di consentire una volta l'installazione di app da Capotreno
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        phase = Phase.Downloading(rel, null)
        Updater.onFinished = { err ->
            if (err == null) { phase = Phase.Available(rel); dialog = false }
            else { phase = Phase.Failed(err); dialog = true }
        }
        scope.launch {
            runCatching {
                val file = Updater.download(ctx, rel) { p -> phase = Phase.Downloading(rel, p) }
                phase = Phase.Installing(rel)
                Updater.install(ctx, file)
            }.onFailure { phase = Phase.Failed(errorMessage(it)) }
        }
    }

    /** Testo breve per la voce nelle Opzioni. */
    fun summary(ctx: Context): String = if (Updater.fromFDroid(ctx)) "Installata da F-Droid: gli aggiornamenti arrivano da lì" else when (val p = phase) {
        Phase.Idle -> "Versione ${BuildConfig.VERSION_NAME} · tocca per controllare"
        Phase.Checking -> "Controllo in corso…"
        Phase.UpToDate -> "Versione ${BuildConfig.VERSION_NAME}: è la più recente"
        is Phase.Available -> "Disponibile la versione ${p.rel.version}"
        is Phase.Downloading -> "Download della versione ${p.rel.version}…"
        is Phase.Installing -> "Installazione della versione ${p.rel.version}…"
        is Phase.Failed -> "Controllo non riuscito: ${p.message}"
    }
}

@Composable
fun UpdateDialog() {
    if (!UpdateUi.dialog) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val close = { UpdateUi.dialog = false }
    when (val p = UpdateUi.phase) {
        is UpdateUi.Phase.Available -> AlertDialog(
            onDismissRequest = close,
            title = { Text("Nuova versione ${p.rel.version}") },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text("Hai la versione ${BuildConfig.VERSION_NAME}.", style = MaterialTheme.typography.bodyMedium)
                    if (p.rel.notes.isNotBlank()) {
                        Text(p.rel.notes, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                    }
                    if (!Updater.canInstall(ctx)) {
                        Text(
                            "La prima volta Android ti chiederà di consentire l'installazione di app da Capotreno: " +
                                "attivalo, torna indietro e tocca di nuovo Aggiorna.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { UpdateUi.install(ctx, scope, p.rel) }) { Text("Aggiorna") } },
            dismissButton = { TextButton(onClick = close) { Text("Più tardi") } },
        )
        is UpdateUi.Phase.Downloading, is UpdateUi.Phase.Installing -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Aggiornamento") },
            text = {
                Column {
                    val progress = (p as? UpdateUi.Phase.Downloading)?.progress
                    Text(if (p is UpdateUi.Phase.Installing) "Conferma l'installazione nella finestra di Android." else "Download in corso…")
                    if (p is UpdateUi.Phase.Downloading) {
                        if (progress != null) LinearProgressIndicator({ progress }, Modifier.fillMaxWidth().padding(top = 16.dp))
                        else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp))
                    }
                }
            },
            confirmButton = { if (p is UpdateUi.Phase.Installing) TextButton(onClick = close) { Text("Chiudi") } },
        )
        is UpdateUi.Phase.Failed -> AlertDialog(
            onDismissRequest = close,
            title = { Text("Aggiornamento non riuscito") },
            text = { Text(p.message) },
            confirmButton = { TextButton(onClick = close) { Text("OK") } },
        )
        else -> androidx.compose.runtime.SideEffect { UpdateUi.dialog = false }
    }
}
