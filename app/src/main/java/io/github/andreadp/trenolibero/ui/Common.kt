package io.github.andreadp.trenolibero.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.andreadp.trenolibero.data.ViaggiaTrenoApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------- Tema ----------

private val Green = Color(0xFF0B5D3B)

@Composable
fun TrenoTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF7FD8A6), secondary = Color(0xFFFFC107))
        else -> lightColorScheme(primary = Green, secondary = Color(0xFF8A6D00))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

object Palette {
    val onTime = Color(0xFF2E7D32)
    val late = Color(0xFFE65100)
    val veryLate = Color(0xFFC62828)
    val early = Color(0xFF1565C0)
}

// ---------- Caricamento dati ----------

class Loaded<T>(val data: T?, val error: String?, val loading: Boolean, val reload: () -> Unit)

/**
 * Esegue [block] al cambio di [keys] e, se [refreshMs] non è null, lo ripete periodicamente.
 * I dati precedenti restano visibili durante l'aggiornamento.
 */
@Composable
fun <T> rememberLoader(vararg keys: Any?, refreshMs: Long? = null, block: suspend () -> T): Loaded<T> {
    var data by remember(*keys) { mutableStateOf<T?>(null) }
    var error by remember(*keys) { mutableStateOf<String?>(null) }
    var loading by remember(*keys) { mutableStateOf(true) }
    var tick by remember(*keys) { mutableIntStateOf(0) }
    LaunchedEffect(*keys, tick) {
        while (true) {
            loading = true
            try {
                data = block()
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = errorMessage(e)
            }
            loading = false
            if (refreshMs == null) break
            delay(refreshMs)
        }
    }
    return Loaded(data, error, loading) { tick++ }
}

fun errorMessage(e: Throwable): String = when (e) {
    is java.net.UnknownHostException -> "Nessuna connessione a Internet"
    is java.net.SocketTimeoutException -> "Il server non risponde, riprova"
    is IOException -> e.message ?: "Errore di rete"
    is kotlinx.serialization.SerializationException -> "Risposta del server non riconosciuta"
    else -> e.message ?: e.toString()
}

/** Mostra caricamento / errore / contenuto. */
@Composable
fun <T> LoadedContent(state: Loaded<T>, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    val data = state.data
    when {
        data != null -> content(data)
        state.error != null -> ErrorBox(state.error, modifier, state.reload)
        else -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}

@Composable
fun ErrorBox(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        if (onRetry != null) Button(onClick = onRetry) { Text("Riprova") }
    }
}

@Composable
fun EmptyText(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(32.dp),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ---------- Formattazione ----------

private val hm = ThreadLocal.withInitial {
    SimpleDateFormat("HH:mm", Locale.ITALY).apply { timeZone = ViaggiaTrenoApi.ROME }
}

fun fmtTime(ms: Long?): String = if (ms == null || ms <= 0) "--:--" else hm.get()!!.format(Date(ms))

fun delayText(min: Int?): String = when {
    min == null -> ""
    min > 0 -> "+$min min"
    min < 0 -> "$min min"
    else -> "in orario"
}

/** Ritardo in rosso, anticipo in blu, in orario in verde. */
fun delayColor(min: Int?): Color = when {
    min == null -> Color.Gray
    min < 0 -> Palette.early
    min == 0 -> Palette.onTime
    else -> Palette.veryLate
}

/** Etichetta colorata del ritardo. */
@Composable
fun DelayBadge(min: Int?, modifier: Modifier = Modifier) {
    if (min == null) return
    Surface(color = delayColor(min), shape = RoundedCornerShape(6.dp), modifier = modifier) {
        Text(
            delayText(min),
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Binario: evidenziato se l'effettivo differisce dal programmato. */
@Composable
fun PlatformBadge(programmed: String?, actual: String?, modifier: Modifier = Modifier) {
    val p = programmed?.trim().orEmpty()
    val a = actual?.trim().orEmpty()
    if (p.isEmpty() && a.isEmpty()) return
    val changed = a.isNotEmpty() && p.isNotEmpty() && a != p
    val confirmed = a.isNotEmpty()
    val bg = when {
        changed -> Palette.veryLate
        confirmed -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = if (changed || confirmed) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = bg, shape = RoundedCornerShape(6.dp), modifier = modifier) {
        Text(
            buildString {
                append("Bin. ")
                append(a.ifEmpty { p })
                if (changed) append(" (era $p)")
            },
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            color = fg,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}
