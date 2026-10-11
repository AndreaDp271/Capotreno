package io.github.andreadp.trenolibero.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.runtime.collectAsState
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
import io.github.andreadp.trenolibero.data.platformLabel
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
    val dark = isDarkTheme()
    val palette by io.github.andreadp.trenolibero.data.AppSettings.palette.collectAsState()
    val ctx = LocalContext.current
    val scheme = when {
        palette == io.github.andreadp.trenolibero.data.AppSettings.Palette.PAPER -> if (dark) PaperDark else PaperLight
        Build.VERSION.SDK_INT >= 31 && palette == io.github.andreadp.trenolibero.data.AppSettings.Palette.DYNAMIC ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF7FD8A6), secondary = Color(0xFFFFC107))
        else -> lightColorScheme(primary = Green, secondary = Color(0xFF8A6D00))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

// Carta: fondo avorio, inchiostro quasi nero, un solo accento rosso; superfici piatte senza riflessi colorati.
private val PaperLight = lightColorScheme(
    primary = Color(0xFF1F1D1A), onPrimary = Color(0xFFF7F4EC),
    primaryContainer = Color(0xFFE6E0D3), onPrimaryContainer = Color(0xFF1F1D1A),
    secondary = Color(0xFF5E584E), onSecondary = Color(0xFFF7F4EC),
    secondaryContainer = Color(0xFFE9E3D6), onSecondaryContainer = Color(0xFF1F1D1A),
    tertiary = Color(0xFFB0372A), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF3D9D3), onTertiaryContainer = Color(0xFF4A120B),
    background = Color(0xFFF4F0E6), onBackground = Color(0xFF1F1D1A),
    surface = Color(0xFFF4F0E6), onSurface = Color(0xFF1F1D1A),
    surfaceVariant = Color(0xFFE8E2D5), onSurfaceVariant = Color(0xFF5E584E),
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color(0xFFFBF9F3), surfaceContainerLow = Color(0xFFF8F5EE),
    surfaceContainer = Color(0xFFEFEADF), surfaceContainerHigh = Color(0xFFEAE4D8), surfaceContainerHighest = Color(0xFFE4DDCF),
    outline = Color(0xFF8C8577), outlineVariant = Color(0xFFD6CFC0),
    error = Color(0xFFB0372A), onError = Color(0xFFFFFFFF),
)

private val PaperDark = darkColorScheme(
    primary = Color(0xFFEDE7DA), onPrimary = Color(0xFF1C1B18),
    primaryContainer = Color(0xFF3A3731), onPrimaryContainer = Color(0xFFEDE7DA),
    secondary = Color(0xFFB9B1A2), onSecondary = Color(0xFF1C1B18),
    secondaryContainer = Color(0xFF34312C), onSecondaryContainer = Color(0xFFEDE7DA),
    tertiary = Color(0xFFE8857A), onTertiary = Color(0xFF3B0D07),
    tertiaryContainer = Color(0xFF5A1F18), onTertiaryContainer = Color(0xFFF7D9D4),
    background = Color(0xFF1C1B18), onBackground = Color(0xFFEDE7DA),
    surface = Color(0xFF1C1B18), onSurface = Color(0xFFEDE7DA),
    surfaceVariant = Color(0xFF2E2C27), onSurfaceVariant = Color(0xFFB9B1A2),
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color(0xFF161512), surfaceContainerLow = Color(0xFF22211D),
    surfaceContainer = Color(0xFF262420), surfaceContainerHigh = Color(0xFF2E2C27), surfaceContainerHighest = Color(0xFF38352F),
    outline = Color(0xFF8A8376), outlineVariant = Color(0xFF45413A),
    error = Color(0xFFE8857A), onError = Color(0xFF3B0D07),
)

/** Tiene lo schermo acceso finché la schermata è visibile, se attivato nelle impostazioni. */
@Composable
fun KeepScreenOnIfEnabled() {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(view) {
        val on = io.github.andreadp.trenolibero.data.AppSettings.keepScreenOn
        if (on) view.keepScreenOn = true
        onDispose { if (on) view.keepScreenOn = false }
    }
}

/** Tema scuro secondo la scelta nelle impostazioni (o quella di sistema). */
@Composable
fun isDarkTheme(): Boolean {
    val theme by io.github.andreadp.trenolibero.data.AppSettings.theme.collectAsState()
    return when (theme) {
        io.github.andreadp.trenolibero.data.AppSettings.Theme.LIGHT -> false
        io.github.andreadp.trenolibero.data.AppSettings.Theme.DARK -> true
        io.github.andreadp.trenolibero.data.AppSettings.Theme.SYSTEM -> isSystemInDarkTheme()
    }
}

object Palette {
    val onTime = Color(0xFF2E7D32)
    val late = Color(0xFFE65100)
    val veryLate = Color(0xFFC62828)
    val early = Color(0xFF1565C0)
    val platformSign = Color(0xFF263238)
    val platformText = Color(0xFFFFD54F)
    val platformChanged = Color(0xFFFFC400)
}

// ---------- Caricamento dati ----------

class Loaded<T>(
    val data: T?,
    val error: String?,
    val loading: Boolean,
    /** Ora dell'ultimo aggiornamento riuscito (ms). */
    val updatedAt: Long? = null,
    val refreshMs: Long? = null,
    val reload: () -> Unit,
)

/**
 * Esegue [block] al cambio di [keys] e, se [refreshMs] non è null, lo ripete periodicamente.
 * Al ritorno nell'app (ON_RESUME) aggiorna subito se i dati sono più vecchi di [refreshMs].
 * I dati precedenti restano visibili durante l'aggiornamento.
 */
@Composable
fun <T> rememberLoader(vararg keys: Any?, refreshMs: Long? = null, block: suspend () -> T): Loaded<T> {
    // intervallo scalato secondo l'impostazione "Aggiornamento automatico"
    val every = remember(refreshMs) { refreshMs?.let { io.github.andreadp.trenolibero.data.AppSettings.refreshMs(it) } }
    var data by remember(*keys) { mutableStateOf<T?>(null) }
    var error by remember(*keys) { mutableStateOf<String?>(null) }
    var loading by remember(*keys) { mutableStateOf(true) }
    var updatedAt by remember(*keys) { mutableStateOf<Long?>(null) }
    var tick by remember(*keys) { mutableIntStateOf(0) }
    LaunchedEffect(*keys, tick) {
        while (true) {
            loading = true
            try {
                data = block()
                error = null
                updatedAt = System.currentTimeMillis()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = errorMessage(e)
            }
            loading = false
            if (every == null) break
            delay(every)
        }
    }
    if (every != null) {
        val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        androidx.compose.runtime.DisposableEffect(owner, *keys) {
            val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                val age = System.currentTimeMillis() - (updatedAt ?: 0L)
                if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && !loading && age > every) tick++
            }
            owner.lifecycle.addObserver(obs)
            onDispose { owner.lifecycle.removeObserver(obs) }
        }
    }
    return Loaded(data, error, loading, updatedAt, every) { tick++ }
}

/** Sottotitolo della barra: "Aggiornato alle 16:21 · ogni 30 s", con pallino che pulsa durante l'aggiornamento. */
@Composable
fun RefreshStatus(state: Loaded<*>) {
    val at = state.updatedAt ?: return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "refresh")
        val a by t.animateFloat(
            0.25f, 1f,
            androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(500), androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "a",
        )
        Box(
            Modifier.size(7.dp).background(
                if (state.error != null) Palette.veryLate else Palette.onTime.copy(alpha = if (state.loading) a else 1f),
                androidx.compose.foundation.shape.CircleShape,
            ),
        )
        Text(
            when {
                state.loading -> "Aggiornamento…"
                state.error != null -> "Non aggiornato · ultimo alle ${fmtTime(at)}"
                else -> "Aggiornato alle " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ITALY)
                    .apply { timeZone = ViaggiaTrenoApi.ROME }.format(java.util.Date(at)) +
                    (state.refreshMs?.let { " · ogni ${it / 1000} s" } ?: "")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
    val p = platformLabel(programmed).orEmpty()
    val a = platformLabel(actual).orEmpty()
    if (p.isEmpty() && a.isEmpty()) return
    val changed = a.isNotEmpty() && p.isNotEmpty() && a != p
    val confirmed = a.isNotEmpty()
    // Colori fissi, indipendenti dal tema: cartello scuro con numero giallo come nei tabelloni di stazione;
    // binario cambiato in giallo pieno per farlo notare subito
    val bg = when {
        changed -> Palette.platformChanged
        confirmed -> Palette.platformSign
        else -> Palette.platformSign.copy(alpha = 0.55f)
    }
    val fg = when {
        changed -> Palette.platformSign
        confirmed -> Palette.platformText
        else -> Palette.platformText.copy(alpha = 0.75f)
    }
    Surface(
        color = bg, shape = RoundedCornerShape(6.dp), modifier = modifier,
        // bordo giallo tenue: il cartello resta visibile anche sul tema scuro
        border = if (changed) null else androidx.compose.foundation.BorderStroke(1.dp, Palette.platformText.copy(alpha = 0.4f)),
    ) {
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
