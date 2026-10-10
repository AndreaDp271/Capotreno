package io.github.andreadp.trenolibero

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.andreadp.trenolibero.data.Graph
import io.github.andreadp.trenolibero.ui.App
import io.github.andreadp.trenolibero.ui.TrenoTheme
import org.osmdroid.config.Configuration
import java.io.File

class MainActivity : ComponentActivity() {
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
        setContent { TrenoTheme { App() } }
    }
}
