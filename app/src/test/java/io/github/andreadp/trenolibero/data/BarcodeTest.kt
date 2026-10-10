package io.github.andreadp.trenolibero.data

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Test

/** Un QR piccolo in un angolo di una pagina A4 renderizzata a 2000 px deve essere trovato (stessi parametri dell'app). */
class BarcodeTest {
    @Test
    fun qrInPaginaA4() {
        val w = 2000; val h = 2828
        val page = IntArray(w * h) { -1 }
        val qr = QRCodeWriter().encode("PNR X7K2QZ CP 2612345678 REG 2938", BarcodeFormat.QR_CODE, 260, 260)
        for (y in 0 until qr.height) for (x in 0 until qr.width) {
            if (qr[x, y]) page[(2300 + y) * w + 1500 + x] = 0xFF000000.toInt()
        }
        // testo "di disturbo" attorno
        for (y in 200 until 1800 step 40) for (x in 150 until 1850) if ((x / 7 + y) % 3 == 0) page[y * w + x] = 0xFF333333.toInt()
        val r = MultiFormatReader().decode(
            BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, h, page))),
            mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE, BarcodeFormat.AZTEC)),
        )
        assertEquals("PNR X7K2QZ CP 2612345678 REG 2938", r.text)
        val xs = r.resultPoints.map { it.x }
        println("Punti QR x: $xs")
    }
}
