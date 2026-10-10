package io.github.andreadp.trenolibero.data

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File

/** Prova manuale su un codice reale: TICKET_IMG=file .raw (int w, int h, byte grigi), mai incluso nel repository. */
class LocalTicketImageTest {
    @Test
    fun decodificaImmagineLocale() {
        val path = System.getenv("TICKET_IMG")
        assumeTrue(!path.isNullOrEmpty())
        val d = DataInputStream(File(path).inputStream())
        val iw = d.readInt(); val ih = d.readInt()
        val gray = ByteArray(iw * ih).also { d.readFully(it) }
        val pad = iw / 8
        val w = iw + 2 * pad; val h = ih + 2 * pad
        val px = IntArray(w * h) { -1 }
        for (y in 0 until ih) for (x in 0 until iw) {
            val g = gray[y * iw + x].toInt() and 0xFF
            px[(y + pad) * w + x + pad] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
        val hints = mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.AZTEC, BarcodeFormat.QR_CODE),
        )
        val r = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, h, px))), hints)
        println("Formato ${r.barcodeFormat}, ${r.text.length} caratteri")
    }
}
