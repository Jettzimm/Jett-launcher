package com.jett.launcher

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

/**
 * Thin white outline icons (weather, settings, sleep), drawn in code so they match the
 * typography instead of the system's colour emoji. Drawn on a 24×24 grid, scaled to bounds.
 *
 * Kinds: sun, moon, cloud, partly-day, partly-night, rain, snow, storm, fog, gear.
 */
class LineIcon(private val kind: String, color: Int = 0xF2FFFFFF.toInt(), stroke: Float = 1.5f) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; this.color = color
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(b.width() / 24f, b.height() / 24f)
        when (kind) {
            "sun" -> sun(canvas, 12f, 12f, 4.2f, 6.8f, 9.4f)
            "moon" -> canvas.drawPath(moon(12f, 12f, 8f), paint)
            "cloud" -> canvas.drawPath(cloud(0f), paint)
            "partly-day", "partly-night" -> {
                val c = Path(cloud(0f)).apply { transform(Matrix().apply { setScale(0.85f, 0.85f, 24f, 24f) }) }
                canvas.save(); canvas.clipOutPath(c)
                if (kind == "partly-day") sun(canvas, 8.5f, 8f, 3f, 5f, 7f)
                else canvas.drawPath(moon(8.5f, 8f, 5.5f), paint)
                canvas.restore()
                canvas.drawPath(c, paint)
            }
            "rain" -> { canvas.drawPath(cloud(-4f), paint)
                for (x in listOf(8f, 12f, 16f)) canvas.drawLine(x, 17.5f, x - 1.5f, 21.5f, paint) }
            "snow" -> { canvas.drawPath(cloud(-4f), paint)
                for ((x, y) in listOf(8f to 18.5f, 12f to 20.5f, 16f to 18.5f)) canvas.drawCircle(x, y, 0.9f, fill) }
            "storm" -> { canvas.drawPath(cloud(-4f), paint)
                canvas.drawPath(Path().apply { moveTo(13f, 16f); lineTo(10.5f, 19.5f); lineTo(13.5f, 19.5f); lineTo(11f, 23f) }, paint) }
            "fog" -> { canvas.drawLine(4f, 8f, 20f, 8f, paint); canvas.drawLine(3f, 12f, 19f, 12f, paint)
                canvas.drawLine(5f, 16f, 21f, 16f, paint) }
            "gear" -> gear(canvas)
        }
        canvas.restore()
    }

    private fun sun(c: Canvas, cx: Float, cy: Float, r: Float, r1: Float, r2: Float) {
        c.drawCircle(cx, cy, r, paint)
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            c.drawLine(cx + r1 * cos(a).toFloat(), cy + r1 * sin(a).toFloat(), cx + r2 * cos(a).toFloat(), cy + r2 * sin(a).toFloat(), paint)
        }
    }

    /** Crescent: a circle minus an offset circle. */
    private fun moon(cx: Float, cy: Float, r: Float): Path {
        val outer = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
        val bite = Path().apply { addCircle(cx + r * 0.55f, cy - r * 0.4f, r * 0.85f, Path.Direction.CW) }
        return Path().apply { op(outer, bite, Path.Op.DIFFERENCE) }
    }

    /** Cloud outline (flat bottom at y = 18.5 + dy). */
    private fun cloud(dy: Float): Path {
        val p = Path().apply {
            addCircle(8f, 14.5f + dy, 4f, Path.Direction.CW)
            val a = Path().apply { addCircle(13f, 11.5f + dy, 5.5f, Path.Direction.CW) }
            val b = Path().apply { addCircle(17.5f, 14.8f + dy, 3.7f, Path.Direction.CW) }
            val base = Path().apply { addRect(RectF(8f, 13f + dy, 17.5f, 18.5f + dy), Path.Direction.CW) }
            op(a, Path.Op.UNION); op(b, Path.Op.UNION); op(base, Path.Op.UNION)
        }
        return p
    }

    private fun gear(c: Canvas) {
        val body = Path().apply { addCircle(12f, 12f, 6.6f, Path.Direction.CW) }
        for (i in 0 until 8) {
            val tooth = Path().apply {
                addRoundRect(RectF(10.4f, 2.4f, 13.6f, 7f), 0.8f, 0.8f, Path.Direction.CW)
                transform(Matrix().apply { setRotate(i * 45f, 12f, 12f) })
            }
            body.op(tooth, Path.Op.UNION)
        }
        c.drawPath(body, paint)
        c.drawCircle(12f, 12f, 3f, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; fill.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf; fill.colorFilter = cf; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
