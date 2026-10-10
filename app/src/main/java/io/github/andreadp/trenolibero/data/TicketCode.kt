package io.github.andreadp.trenolibero.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Codice del biglietto trovato: immagine pronta da mostrare + contenuto letto. */
class FoundCode(val image: Bitmap, val format: String, val text: String)

/**
 * Individua il codice a barre del biglietto (QR, Aztec, PDF417, DataMatrix) con ZXing.
 *
 * Strategie, in ordine:
 * 1. immagini incorporate nel PDF (i biglietti Trenitalia contengono l'Aztec come immagine a sé: resta identico all'originale);
 * 2. pagina o immagine intera (va bene per i QR, che si trovano in qualsiasi posizione);
 * 3. finestre scorrevoli su una copia ridotta: il rilevatore Aztec di ZXing cerca il "bersaglio"
 *    partendo dal centro dell'immagine, quindi un codice in un angolo va prima inquadrato.
 */
object TicketCode {
    private val ALL = listOf(BarcodeFormat.QR_CODE, BarcodeFormat.AZTEC, BarcodeFormat.PDF_417, BarcodeFormat.DATA_MATRIX)
    private val CENTERED = listOf(BarcodeFormat.AZTEC, BarcodeFormat.DATA_MATRIX, BarcodeFormat.QR_CODE)

    private fun hints(formats: List<BarcodeFormat>) = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to formats,
    )

    /** Rende le pagine del PDF (max [maxPages]) alla larghezza [width] px, su fondo bianco. */
    fun renderPdf(file: File, width: Int, maxPages: Int = 6): List<Bitmap> =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                (0 until min(r.pageCount, maxPages)).map { i ->
                    r.openPage(i).use { p ->
                        val height = (width.toFloat() * p.height / p.width).toInt()
                        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { b ->
                            b.eraseColor(Color.WHITE)
                            p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        }
                    }
                }
            }
        }

    /** Strategia 1: immagini incorporate nelle prime pagine del PDF (richiede PDFBoxResourceLoader.init). */
    fun fromPdfImages(doc: PDDocument, maxPages: Int = 3): FoundCode? {
        val images = mutableListOf<Bitmap>()
        fun collect(res: PDResources?, depth: Int) {
            res ?: return
            for (name in res.xObjectNames) {
                when (val x = runCatching { res.getXObject(name) }.getOrNull()) {
                    is PDImageXObject -> {
                        val ratio = x.width.toFloat() / max(1, x.height)
                        if (min(x.width, x.height) >= 40 && ratio in 0.2f..6f) runCatching { x.image }.getOrNull()?.let(images::add)
                    }
                    is PDFormXObject -> if (depth < 3) collect(x.resources, depth + 1)
                }
            }
        }
        doc.pages.take(maxPages).forEach { collect(it.resources, 0) }
        // prima le immagini più "quadrate" (QR/Aztec), poi le altre
        images.sortBy { kotlin.math.abs(1f - it.width.toFloat() / it.height) }
        for (img in images) {
            val padded = pad(img)
            decode(padded, ALL)?.let { r -> return FoundCode(padded, r.barcodeFormat.name, r.text.orEmpty()) }
        }
        return null
    }

    fun find(pages: List<Bitmap>): FoundCode? {
        for (page in pages) find(page)?.let { return it }
        return null
    }

    /** Strategie 2 e 3 su una pagina renderizzata, uno screenshot o una foto. */
    fun find(bitmap: Bitmap): FoundCode? {
        decode(bitmap, ALL)?.let { return FoundCode(crop(bitmap, it, 0f, 0f, 1f), it.barcodeFormat.name, it.text.orEmpty()) }

        // Copia ridotta (lato lungo ~1200 px) per scorrere le finestre in fretta
        val scale = min(1f, 1200f / max(bitmap.width, bitmap.height))
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else bitmap
        val side = min(small.width, small.height)
        for (f in listOf(0.22f, 0.32f, 0.45f, 0.65f)) {
            val win = (side * f).toInt().coerceAtLeast(120)
            val step = max(30, win / 3)
            var y = 0
            while (y + win <= small.height) {
                var x = 0
                while (x + win <= small.width) {
                    val sub = Bitmap.createBitmap(small, x, y, win, win)
                    decode(sub, CENTERED)?.let { r ->
                        return FoundCode(crop(bitmap, r, x.toFloat(), y.toFloat(), scale), r.barcodeFormat.name, r.text.orEmpty())
                    }
                    x += step
                }
                y += step
            }
        }
        return null
    }

    private fun decode(src: Bitmap, formats: List<BarcodeFormat>): Result? {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val source = RGBLuminanceSource(w, h, pixels)
        val reader = MultiFormatReader().apply { setHints(hints(formats)) }
        for (bin in listOf(HybridBinarizer(source), GlobalHistogramBinarizer(source))) {
            try {
                return reader.decodeWithState(BinaryBitmap(bin))
            } catch (e: Exception) {
                // non trovato con questo binarizzatore
            } finally {
                reader.reset()
            }
        }
        return null
    }

    /** Ridisegna un codice dal suo contenuto (per i biglietti scansionati con la fotocamera). */
    fun render(format: String, text: String): Bitmap? = runCatching {
        val fmt = BarcodeFormat.valueOf(format)
        // ISO-8859-1 conserva i byte del contenuto letto quando non è testo puro
        val charset = if (text.all { it.code < 256 }) "ISO-8859-1" else "UTF-8"
        val hints = mapOf(com.google.zxing.EncodeHintType.CHARACTER_SET to charset, com.google.zxing.EncodeHintType.MARGIN to 2)
        val w = if (fmt == BarcodeFormat.PDF_417) 1200 else 800
        val h = if (fmt == BarcodeFormat.PDF_417) 400 else 800
        val m = com.google.zxing.MultiFormatWriter().encode(text, fmt, w, h, hints)
        val px = IntArray(m.width * m.height) { i -> if (m[i % m.width, i / m.width]) Color.BLACK else Color.WHITE }
        Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
    }.getOrNull()

    /** Aggiunge un bordo bianco (zona di rispetto) attorno al codice. */
    private fun pad(img: Bitmap): Bitmap {
        val p = max(img.width, img.height) / 8
        val out = Bitmap.createBitmap(img.width + 2 * p, img.height + 2 * p, Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.WHITE)
        Canvas(out).drawBitmap(img, p.toFloat(), p.toFloat(), null)
        return out
    }

    /**
     * Ritaglio dall'immagine originale attorno ai punti del codice trovati in una finestra
     * (spostata di [dx],[dy] e ridotta di [scale]); i punti del QR sono i centri dei mirini, da cui il margine.
     */
    private fun crop(src: Bitmap, r: Result, dx: Float, dy: Float, scale: Float): Bitmap {
        val pts = r.resultPoints.filterNotNull()
        if (pts.isEmpty()) return src
        val minX = (pts.minOf { it.x } + dx) / scale
        val maxX = (pts.maxOf { it.x } + dx) / scale
        val minY = (pts.minOf { it.y } + dy) / scale
        val maxY = (pts.maxOf { it.y } + dy) / scale
        val size = max(maxX - minX, maxY - minY)
        val margin = size * if (r.barcodeFormat == BarcodeFormat.QR_CODE) 0.45f else 0.18f
        val left = max(0, (minX - margin).toInt())
        val top = max(0, (minY - margin).toInt())
        val right = min(src.width, (maxX + margin).toInt())
        val bottom = min(src.height, (maxY + margin).toInt())
        if (right - left < 20 || bottom - top < 20) return src
        return Bitmap.createBitmap(src, left, top, right - left, bottom - top)
    }
}
