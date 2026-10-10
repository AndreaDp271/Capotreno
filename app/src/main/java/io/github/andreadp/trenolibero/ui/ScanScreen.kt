package io.github.andreadp.trenolibero.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import io.github.andreadp.trenolibero.data.Graph
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Scansione del codice del biglietto (QR, Aztec, PDF417, DataMatrix) con la fotocamera. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(nav: Nav, attachTo: String? = null) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; asked = true }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scansiona il biglietto") },
                navigationIcon = {
                    IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
                },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
            if (granted) {
                CameraScanner { result ->
                    scope.launch {
                        val t = Graph.tickets.addScanned(result.barcodeFormat.name, result.text.orEmpty(), Graph.stations)
                        nav.back()
                        val sub = attachTo?.let { Graph.subscriptions.get(it) }
                        if (sub != null) Graph.subscriptions.save(sub.copy(ticketId = t.id)) else nav.ticket(t.id)
                    }
                }
                Box(Modifier.size(260.dp).border(3.dp, Color.White, RoundedCornerShape(16.dp)))
                Card(Modifier.align(Alignment.BottomCenter).padding(24.dp)) {
                    Text(
                        "Inquadra il QR o l'Aztec del biglietto (anche sullo schermo di un altro dispositivo o su carta)",
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else if (asked) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ErrorBox("Serve il permesso della fotocamera per scansionare il codice.")
                    Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Concedi permesso") }
                }
            }
        }
    }
}

@Composable
private fun CameraScanner(onResult: (Result) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val done = remember { AtomicBoolean(false) }
    val reader = remember {
        MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.TRY_HARDER to true,
                    DecodeHintType.POSSIBLE_FORMATS to listOf(
                        BarcodeFormat.QR_CODE, BarcodeFormat.AZTEC, BarcodeFormat.PDF_417, BarcodeFormat.DATA_MATRIX,
                    ),
                )
            )
        }
    }

    DisposableEffect(owner) {
        val providerFuture = ProcessCameraProvider.getInstance(ctx)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { image ->
                val r = if (done.get()) null else decode(reader, image)
                image.close()
                if (r != null && done.compareAndSet(false, true)) {
                    ContextCompat.getMainExecutor(ctx).execute { onResult(r) }
                }
            }
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            executor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

/** Decodifica dal piano Y (luminanza) del fotogramma (QR e Aztec si leggono in qualsiasi orientamento). */
private fun decode(reader: MultiFormatReader, image: ImageProxy): Result? {
    val plane = image.planes[0]
    val buf = plane.buffer
    val w = image.width
    val h = image.height
    val stride = plane.rowStride
    val data = ByteArray(w * h)
    for (y in 0 until h) {
        buf.position(y * stride)
        buf.get(data, y * w, w)
    }
    return try {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false))))
    } catch (e: Exception) {
        null // nessun codice in questo fotogramma
    } finally {
        reader.reset()
    }
}
