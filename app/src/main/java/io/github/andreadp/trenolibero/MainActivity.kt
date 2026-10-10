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
import io.github.andreadp.trenolibero.data.Graph
import io.github.andreadp.trenolibero.ui.App
import io.github.andreadp.trenolibero.ui.TrenoTheme
import org.osmdroid.config.Configuration
import java.io.File

class MainActivity : ComponentActivity() {
    /** PDF arrivato con "Condividi" / "Apri con" da un'altra app. */
    private var pendingImport by mutableStateOf<Uri?>(null)

    private fun handle(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            else -> null
        }
        if (uri != null) pendingImport = uri
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
        setContent { TrenoTheme { App(pendingImport) { pendingImport = null } } }
    }
}
