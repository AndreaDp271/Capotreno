package io.github.andreadp.trenolibero.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import io.github.andreadp.trenolibero.data.DetectionLocator
import io.github.andreadp.trenolibero.data.LatLon
import kotlin.math.atan2
import kotlin.math.cos

/** Icona del treno sulla mappa: pallino arancione con freccia nella direzione di marcia. */
object TrainMarker {
    private val cache = HashMap<Int, BitmapDrawable>()

    /** [bearing] in gradi da nord in senso orario; null = solo pallino. */
    fun icon(ctx: Context, bearing: Double?): BitmapDrawable {
        val key = bearing?.let { ((it / 10).toInt() * 10 + 360) % 360 } ?: -1
        return cache.getOrPut(key) {
            val d = ctx.resources.displayMetrics.density
            val size = (30 * d).toInt()
            val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val r = size / 2f
            c.drawCircle(r, r, r - 1.5f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE65100.toInt() })
            c.drawCircle(r, r, r - 1.5f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f * d
            })
            val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
            if (key >= 0) {
                c.save()
                c.rotate(key.toFloat(), r, r)
                val arrow = Path().apply {
                    moveTo(r, r - 8.5f * d)
                    lineTo(r + 6.5f * d, r + 6f * d)
                    lineTo(r, r + 2.5f * d)
                    lineTo(r - 6.5f * d, r + 6f * d)
                    close()
                }
                c.drawPath(arrow, white)
                c.restore()
            } else {
                c.drawCircle(r, r, 4f * d, white)
            }
            BitmapDrawable(ctx.resources, b)
        }
    }

    /** Direzione (gradi da nord) dal primo punto verso quello a ~[aheadM] metri lungo la polilinea. */
    fun bearingAlong(points: List<LatLon>, aheadM: Double = 300.0): Double? {
        if (points.size < 2) return null
        val p0 = points.first()
        var q = points.last()
        var acc = 0.0
        for (i in 1 until points.size) {
            acc += DetectionLocator.distanceKm(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon) * 1000
            q = points[i]
            if (acc >= aheadM) break
        }
        if (q == p0) return null
        val dx = (q.lon - p0.lon) * cos(Math.toRadians(p0.lat))
        val dy = q.lat - p0.lat
        return (Math.toDegrees(atan2(dx, dy)) + 360) % 360
    }
}
