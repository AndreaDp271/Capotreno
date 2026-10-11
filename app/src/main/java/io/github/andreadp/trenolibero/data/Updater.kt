package io.github.andreadp.trenolibero.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import io.github.andreadp.trenolibero.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Aggiornamenti dalle release di GitHub: controllo, download e installazione dell'APK.
 * Se l'app è stata installata da F-Droid (o da un suo client) gli aggiornamenti restano a quello store.
 */
object Updater {
    private const val API = "https://api.github.com/repos/AndreaDp271/Capotreno/releases/latest"
    const val FDROID_PAGE = "https://f-droid.org/packages/${BuildConfig.APPLICATION_ID}/"

    /** Esito dell'installazione (null = riuscita o annullata), ascoltato dall'interfaccia se aperta. */
    var onFinished: ((error: String?) -> Unit)? = null

    data class Release(val version: String, val apkUrl: String, val notes: String, val page: String)

    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    /** Client F-Droid noti: se uno di questi ha installato l'app, gli aggiornamenti passano da lì. */
    private val stores = setOf(
        "org.fdroid.fdroid", "org.fdroid.basic", "org.fdroid.fdroid.privileged",
        "com.looker.droidify", "com.machiav3lli.fdroid", "nya.kitsunyan.foxydroid", "eu.bubu1.fdroidclassic",
    )

    fun installer(ctx: Context): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 30) ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName
        else @Suppress("DEPRECATION") ctx.packageManager.getInstallerPackageName(ctx.packageName)
    }.getOrNull()

    fun fromFDroid(ctx: Context) = installer(ctx) in stores

    /** Ultima release pubblicata su GitHub. */
    suspend fun latest(): Release = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(API).header("Accept", "application/vnd.github+json").build()
        val body = client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("GitHub ha risposto ${r.code}")
            r.body!!.string()
        }
        val o = ViaggiaTrenoApi.json.parseToJsonElement(body).jsonObject
        val apk = o["assets"]?.jsonArray?.map { it.jsonObject }
            ?.firstOrNull { it["name"]?.jsonPrimitive?.content.orEmpty().endsWith(".apk") }
            ?: throw IOException("Nessun APK nell'ultima release")
        Release(
            version = o["tag_name"]!!.jsonPrimitive.content.removePrefix("v"),
            apkUrl = apk["browser_download_url"]!!.jsonPrimitive.content,
            notes = o["body"]?.jsonPrimitive?.content.orEmpty().trim(),
            page = o["html_url"]?.jsonPrimitive?.content.orEmpty(),
        )
    }

    /** true se [remote] (es. "2.4.1") è successiva a [local]. */
    fun isNewer(remote: String, local: String = BuildConfig.VERSION_NAME): Boolean {
        val a = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val b = local.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** Scarica l'APK nella cache, segnalando l'avanzamento (0..1). */
    suspend fun download(ctx: Context, rel: Release, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "Capotreno-${rel.version}.apk")
        client.newCall(Request.Builder().url(rel.apkUrl).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Download non riuscito (${r.code})")
            val body = r.body!!
            val total = body.contentLength().takeIf { it > 0 }
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        total?.let { progress(done.toFloat() / it) }
                    }
                }
            }
        }
        verify(ctx, file)
        file
    }

    /** L'APK deve essere della stessa app, più recente e firmato con la stessa chiave. */
    private fun verify(ctx: Context, file: File) {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val apk = pm.getPackageArchiveInfo(file.path, flags) ?: run { file.delete(); throw IOException("File scaricato non valido") }
        val me = pm.getPackageInfo(ctx.packageName, flags)
        val ok = apk.packageName == ctx.packageName && signers(apk) == signers(me) && signers(me).isNotEmpty()
        if (!ok) { file.delete(); throw IOException("L'APK scaricato non è firmato come questa app: installazione annullata") }
        @Suppress("DEPRECATION")
        val newer = if (Build.VERSION.SDK_INT >= 28) apk.longVersionCode > me.longVersionCode else apk.versionCode > me.versionCode
        if (!newer) { file.delete(); throw IOException("La versione su GitHub non è più recente di quella installata") }
    }

    private fun signers(p: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners else @Suppress("DEPRECATION") p.signatures
        return sigs.orEmpty().map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    fun canInstall(ctx: Context) = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** Avvia l'installazione: Android chiede conferma all'utente. */
    suspend fun install(ctx: Context, file: File) = withContext(Dispatchers.IO) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("base.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val intent = Intent(ctx, UpdateReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            s.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        }
    }
}

/** Esito dell'installazione: se serve la conferma dell'utente apre la schermata di sistema. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED -> Updater.onFinished?.invoke(null)
            else -> {
                val raw = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                val msg = when {
                    "DOWNGRADE" in raw -> "La versione scaricata è più vecchia di quella installata"
                    "INSUFFICIENT_STORAGE" in raw -> "Spazio di archiviazione insufficiente"
                    "SIGNATURE" in raw || "UPDATE_INCOMPATIBLE" in raw -> "Firma dell'APK non compatibile con l'app installata"
                    else -> "Installazione non riuscita" + if (raw.isNotBlank()) " ($raw)" else ""
                }
                Updater.onFinished?.invoke(msg)
                    ?: android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}
