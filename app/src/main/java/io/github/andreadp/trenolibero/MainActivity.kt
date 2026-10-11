package io.github.andreadp.trenolibero

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import io.github.andreadp.trenolibero.data.Graph
import io.github.andreadp.trenolibero.ui.App
import io.github.andreadp.trenolibero.ui.TrenoTheme
import org.osmdroid.config.Configuration
import java.io.File

class MainActivity : ComponentActivity() {
    companion object {
        /** Route di navigazione da aprire (usata dai widget). */
        const val EXTRA_ROUTE = "route"
    }

    /** PDF arrivato con "Condividi" / "Apri con" da un'altra app. */
    private var pendingImport by mutableStateOf<Uri?>(null)

    /** Schermata da aprire toccando una notifica. */
    private var pendingRoute by mutableStateOf<String?>(null)

    private fun handle(intent: Intent?) {
        intent?.getStringExtra(io.github.andreadp.trenolibero.data.Notifier.EXTRA_TRAIN)?.split("|")?.takeIf { it.size == 3 }?.let { (n, o, d) ->
            pendingRoute = "train/$n?origin=$o&date=$d"
        }
        intent?.getStringExtra(io.github.andreadp.trenolibero.data.Notifier.EXTRA_SUBSCRIPTION)?.let { pendingRoute = "subscription/$it" }
        intent?.getStringExtra(io.github.andreadp.trenolibero.data.Notifier.EXTRA_INFO)?.let { pendingRoute = "info" }
        intent?.getStringExtra(EXTRA_ROUTE)?.let { pendingRoute = it }
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            else -> null
        }
        if (uri != null) pendingImport = uri
    }

    /** Uscendo dall'app i widget riflettono subito preferiti, biglietti e abbonamenti modificati. */
    override fun onStop() {
        super.onStop()
        if (io.github.andreadp.trenolibero.widget.Widgets.any(this)) {
            lifecycleScope.launch { io.github.andreadp.trenolibero.widget.Widgets.updateAll(applicationContext) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Graph.init(this)
        // osmdroid: User-Agent identificativo (richiesto dalla tile policy OSM) e cache nello spazio privato dell'app
        Configuration.getInstance().apply {
            userAgentValue = "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}"
            osmdroidBasePath = File(filesDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid-tiles")
        }
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        io.github.andreadp.trenolibero.data.Notifier.createChannels(this)
        io.github.andreadp.trenolibero.data.Notifier.schedule(this)
        io.github.andreadp.trenolibero.widget.Widgets.schedule(this)
        setContent {
            TrenoTheme {
                App(pendingImport, { pendingImport = null }, pendingRoute) { pendingRoute = null }
            }
        }
    }
}
