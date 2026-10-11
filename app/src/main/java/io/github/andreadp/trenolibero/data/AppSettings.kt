package io.github.andreadp.trenolibero.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Preferenze generali dell'app (aspetto, avvio, aggiornamento). */
object AppSettings {
    enum class Theme(val label: String) { SYSTEM("Sistema"), LIGHT("Chiaro"), DARK("Scuro") }

    /** Frequenza dell'aggiornamento automatico: moltiplica gli intervalli predefiniti (null = disattivato). */
    enum class Refresh(val label: String, val factor: Double?) {
        FAST("Veloce", 0.5), NORMAL("Normale", 1.0), SAVER("Risparmio", 2.0), OFF("Manuale", null)
    }

    private lateinit var prefs: android.content.SharedPreferences
    private val _theme = MutableStateFlow(Theme.SYSTEM)
    val theme: StateFlow<Theme> = _theme
    private val _dynamicColor = MutableStateFlow(true)
    val dynamicColor: StateFlow<Boolean> = _dynamicColor

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        _theme.value = enumOr(prefs.getString("theme", null), Theme.SYSTEM)
        _dynamicColor.value = prefs.getBoolean("dynamic_color", true)
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E) =
        runCatching { enumValueOf<E>(name!!) }.getOrDefault(default)

    fun setTheme(t: Theme) { _theme.value = t; prefs.edit().putString("theme", t.name).apply() }
    fun setDynamicColor(v: Boolean) { _dynamicColor.value = v; prefs.edit().putBoolean("dynamic_color", v).apply() }

    /** Scheda aperta all'avvio: home, stations, map, tickets, info. */
    var startTab: String
        get() = prefs.getString("start_tab", "home")!!
        set(v) = prefs.edit().putString("start_tab", v).apply()

    var refresh: Refresh
        get() = enumOr(prefs.getString("refresh", null), Refresh.NORMAL)
        set(v) = prefs.edit().putString("refresh", v.name).apply()

    /** Schermo sempre acceso sulla pagina e sulla mappa del treno. */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keep_screen_on", false)
        set(v) = prefs.edit().putBoolean("keep_screen_on", v).apply()

    /** Luminosità al massimo quando si mostra un codice QR a schermo intero. */
    var qrBrightness: Boolean
        get() = prefs.getBoolean("qr_brightness", true)
        set(v) = prefs.edit().putBoolean("qr_brightness", v).apply()

    /** Intervallo effettivo per una schermata che di base si aggiorna ogni [baseMs]. */
    fun refreshMs(baseMs: Long): Long? {
        if (!::prefs.isInitialized) return baseMs
        return refresh.factor?.let { (baseMs * it).toLong() }
    }
}

/**
 * Backup in un file .zip di preferiti, biglietti, abbonamenti, sveglie e impostazioni.
 * Il ripristino sovrascrive i dati attuali; l'app va poi riavviata.
 */
object Backup {
    private const val MARKER = "capotreno-backup.txt"
    private val prefsFiles = listOf("favorites", "notify", "alarms", "app_settings")

    private fun dataDir(ctx: Context) = File(ctx.applicationInfo.dataDir)

    /** File da salvare, come percorso relativo alla cartella dati dell'app. */
    private fun sources(ctx: Context): List<File> {
        val base = dataDir(ctx)
        val prefs = prefsFiles.map { File(base, "shared_prefs/$it.xml") }
        val files = listOf(File(ctx.filesDir, "subscriptions.json")) +
            (File(ctx.filesDir, "tickets").walkTopDown().filter { it.isFile }.toList())
        return (prefs + files).filter { it.isFile }
    }

    /** Scrive il backup e restituisce un riepilogo di cosa contiene. */
    suspend fun export(ctx: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val base = dataDir(ctx)
        val list = sources(ctx)
        ctx.contentResolver.openOutputStream(uri)!!.use { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry(MARKER))
                zip.write("Capotreno ${System.currentTimeMillis()}\n".toByteArray())
                zip.closeEntry()
                list.forEach { f ->
                    zip.putNextEntry(ZipEntry(f.relativeTo(base).invariantSeparatorsPath))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        val favs = Graph.favorites.trains.value.size + Graph.favorites.stations.value.size
        fun n(k: Int, one: String, many: String) = "$k ${if (k == 1) one else many}"
        listOf(
            n(Graph.tickets.tickets.value.size, "biglietto", "biglietti"),
            n(Graph.subscriptions.items.value.size, "abbonamento", "abbonamenti"),
            n(favs, "preferito", "preferiti"),
        ).joinToString(", ")
    }

    /** Ripristina un backup; lancia un'eccezione se il file non è un backup di Capotreno. */
    suspend fun import(ctx: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val base = dataDir(ctx).canonicalFile
        val tmp = File(ctx.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        var valid = false
        ctx.contentResolver.openInputStream(uri)!!.use { input ->
            ZipInputStream(input).use { zip ->
                generateSequence { zip.nextEntry }.forEach { e ->
                    if (e.isDirectory) return@forEach
                    if (e.name == MARKER) { valid = true; return@forEach }
                    val allowed = e.name.startsWith("shared_prefs/") || e.name == "files/subscriptions.json" || e.name.startsWith("files/tickets/")
                    val target = File(tmp, e.name).canonicalFile
                    if (!allowed || !target.path.startsWith(tmp.canonicalPath + File.separator)) return@forEach
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        if (!valid) { tmp.deleteRecursively(); error("Il file scelto non è un backup di Capotreno") }
        // sostituisce biglietti e abbonamenti solo se presenti nel backup
        File(ctx.filesDir, "tickets").deleteRecursively()
        File(ctx.filesDir, "subscriptions.json").delete()
        tmp.copyRecursively(base, overwrite = true)
        tmp.deleteRecursively()
    }
}

/** Dati scaricati che si possono rigenerare: tessere della mappa e anagrafica delle stazioni. */
object CacheInfo {
    private fun targets(ctx: Context) = listOf(ctx.cacheDir, File(ctx.filesDir, "stations.json"), File(ctx.filesDir, "osmdroid"))

    suspend fun size(ctx: Context): Long = withContext(Dispatchers.IO) {
        targets(ctx).sumOf { f -> f.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
    }

    suspend fun clear(ctx: Context) = withContext(Dispatchers.IO) {
        ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        File(ctx.filesDir, "stations.json").delete()
        File(ctx.filesDir, "osmdroid").deleteRecursively()
    }
}
