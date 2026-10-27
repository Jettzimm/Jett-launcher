package com.jett.launcher

import android.content.Context
import android.graphics.*
import android.view.View
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.*
import kotlin.random.Random

enum class Phase(val folder: String) { NIGHT("night"), DAWN("dawn"), MORNING("morning"), MIDDAY("midday"), GOLDEN("golden"), DUSK("dusk") }

object SunClock {
    /** Sunrise/sunset in local minutes-after-midnight (NOAA approximation, ±2 min). */
    fun sunTimes(date: LocalDate, zone: ZoneId, lat: Double, lon: Double): Pair<Int, Int> {
        val n = date.dayOfYear
        val gamma = 2 * PI / 365 * (n - 1)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
            0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma))
        val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) -
            0.006758 * cos(2 * gamma) + 0.000907 * sin(2 * gamma) -
            0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)
        val latR = Math.toRadians(lat)
        val ha = Math.toDegrees(acos(cos(Math.toRadians(90.833)) / (cos(latR) * cos(decl)) - tan(latR) * tan(decl)))
        val offsetMin = zone.rules.getOffset(date.atStartOfDay(zone).toInstant()).totalSeconds / 60.0
        val rise = 720 - 4 * (lon + ha) - eqTime + offsetMin
        val set = 720 - 4 * (lon - ha) - eqTime + offsetMin
        return rise.roundToInt() to set.roundToInt()
    }

    fun phase(now: ZonedDateTime): Phase {
        val (rise, set) = sunTimes(now.toLocalDate(), now.zone, Config.LAT, Config.LON)
        val m = now.hour * 60 + now.minute
        return when {
            m < rise - 50 -> Phase.NIGHT
            m < rise + 50 -> Phase.DAWN
            m < 11 * 60 -> Phase.MORNING
            m < set - 100 -> Phase.MIDDAY
            m < set + 5 -> Phase.GOLDEN
            m < set + 55 -> Phase.DUSK
            else -> Phase.NIGHT
        }
    }

    /** 0..1 how far the sun is across the sky (for placing the glow). */
    fun sunProgress(now: ZonedDateTime): Float {
        val (rise, set) = sunTimes(now.toLocalDate(), now.zone, Config.LAT, Config.LON)
        val m = now.hour * 60 + now.minute
        return ((m - rise).toFloat() / (set - rise)).coerceIn(-0.1f, 1.1f)
    }
}

/** Fallback drawn sky + skyline, used only when a time of day has no photos. */
class SkyView(context: Context) : View(context) {

    private var now: ZonedDateTime = ZonedDateTime.now(Config.ZONE)
    private var phase = SunClock.phase(now)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Generated once so the skyline/stars don't jump around.
    private val rng = Random(1977)
    private val buildings = List(70) { Triple(rng.nextFloat(), 0.10f + rng.nextFloat() * 0.32f, 0.015f + rng.nextFloat() * 0.03f) }
        .sortedBy { it.second }
    private val stars = List(160) { Triple(rng.nextFloat(), rng.nextFloat() * 0.6f, rng.nextFloat()) }
    private val windowSeed = rng.nextInt()

    fun refresh(time: ZonedDateTime = ZonedDateTime.now(Config.ZONE)) {
        now = time; phase = SunClock.phase(time)
        invalidate()
    }

    private fun palette(): IntArray = when (phase) {
        Phase.NIGHT -> intArrayOf(0xFF050913.toInt(), 0xFF0C1630.toInt(), 0xFF1A2448.toInt())
        Phase.DAWN -> intArrayOf(0xFF2B2F5E.toInt(), 0xFFB5698A.toInt(), 0xFFF4B183.toInt())
        Phase.MORNING -> intArrayOf(0xFF4A84C9.toInt(), 0xFF8CBDE6.toInt(), 0xFFE4EEF2.toInt())
        Phase.MIDDAY -> intArrayOf(0xFF2E6DB8.toInt(), 0xFF5B9BDB.toInt(), 0xFFA9CFEE.toInt())
        Phase.GOLDEN -> intArrayOf(0xFF3B5B91.toInt(), 0xFFE39A62.toInt(), 0xFFF7C873.toInt())
        Phase.DUSK -> intArrayOf(0xFF151A3D.toInt(), 0xFF5A3566.toInt(), 0xFFD9735A.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        drawSky(canvas, width.toFloat(), height.toFloat())
    }

    private fun drawSky(canvas: Canvas, w: Float, h: Float) {
        val c = palette()
        paint.shader = LinearGradient(0f, 0f, 0f, h * 0.75f, c, floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        val night = phase == Phase.NIGHT || phase == Phase.DUSK
        if (night) {
            for ((x, y, b) in stars) {
                paint.color = Color.argb(((if (phase == Phase.DUSK) 0.35f else 0.85f) * (80 + b * 175)).toInt(), 255, 255, 255)
                canvas.drawCircle(x * w, y * h, 0.8f + b * 1.6f, paint)
            }
            // Moon
            paint.shader = RadialGradient(w * 0.78f, h * 0.18f, h * 0.16f, 0x55DDE6FF, 0x00DDE6FF, Shader.TileMode.CLAMP)
            canvas.drawCircle(w * 0.78f, h * 0.18f, h * 0.16f, paint); paint.shader = null
            paint.color = 0xFFF1F3FA.toInt(); canvas.drawCircle(w * 0.78f, h * 0.18f, h * 0.035f, paint)
        } else {
            val t = SunClock.sunProgress(now)
            val sx = w * (0.1f + 0.8f * t)
            val sy = h * (0.62f - 0.5f * sin(PI * t.coerceIn(0f, 1f)).toFloat())
            val glow = when (phase) { Phase.GOLDEN, Phase.DAWN -> 0xAAFFD08A.toInt(); else -> 0x66FFFFFF }
            paint.shader = RadialGradient(sx, sy, h * 0.45f, glow, glow and 0x00FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawCircle(sx, sy, h * 0.45f, paint); paint.shader = null
            paint.color = if (phase == Phase.GOLDEN || phase == Phase.DAWN) 0xFFFFE2A8.toInt() else 0xFFFFFBEF.toInt()
            canvas.drawCircle(sx, sy, h * 0.03f, paint)
        }

        // Skyline: two layers for depth.
        val litChance = when (phase) { Phase.NIGHT -> 0.45f; Phase.DUSK -> 0.3f; Phase.DAWN -> 0.12f; else -> 0f }
        val far = blend(c[2], 0xFF000000.toInt(), if (night) 0.55f else 0.35f)
        val near = blend(c[2], 0xFF000000.toInt(), if (night) 0.85f else 0.7f)
        val wr = Random(windowSeed)
        buildings.forEachIndexed { i, (bx, bh, bw) ->
            val isNear = i % 2 == 0
            val top = h - h * bh * (if (isNear) 1f else 1.25f)
            val left = bx * w; val right = left + bw * w
            paint.color = if (isNear) near else far
            canvas.drawRect(left, top, right, h, paint)
            if (litChance > 0 && isNear) {
                val ws = w * 0.004f
                var y = top + ws * 2
                while (y < h - ws) {
                    var x = left + ws
                    while (x < right - ws * 1.5f) {
                        if (wr.nextFloat() < litChance) {
                            paint.color = 0xCCFFD58A.toInt()
                            canvas.drawRect(x, y, x + ws, y + ws * 1.4f, paint)
                        }
                        x += ws * 2.2f
                    }
                    y += ws * 3f
                }
            }
        }
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
        (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
        (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt(),
    )
}

/** Darkens the bottom and top-left so text stays readable over any photo. */
class Scrim(context: Context) : View(context) {
    private val paint = Paint()
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        paint.shader = LinearGradient(0f, h * 0.35f, 0f, h, 0x00000000, 0xD9000000.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = LinearGradient(0f, 0f, w * 0.6f, 0f, 0x99000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h * 0.5f, paint)
    }
}

/**
 * Apple-TV-style background: real photos that match the time of day, cross-fading
 * every few minutes with a slow drift. Falls back to the drawn sky if no photos exist.
 */
class Backdrop(context: Context) : android.widget.FrameLayout(context) {

    private val sky = SkyView(context)
    private val a = android.widget.ImageView(context)
    private val b = android.widget.ImageView(context)
    val scrim = Scrim(context)
    private var front = a
    private var phase: Phase? = null
    private var queue = ArrayDeque<String>()
    private var lastSwap = 0L
    private val builtInCaptions: Map<String, String> by lazy {
        try {
            val j = org.json.JSONObject(context.assets.open("captions.json").bufferedReader().use { it.readText() })
            j.keys().asSequence().associateWith { j.getString(it) }
        } catch (e: Exception) { emptyMap() }
    }
    // Built-in captions plus the online library's (re-read each reshuffle, as the library grows).
    @Volatile private var captions: Map<String, String> = emptyMap()
    /** Called with the location of each new photo ("Positano, Italy"), or "" if unknown. */
    var onCaption: ((String) -> Unit)? = null

    init {
        for (v in listOf(sky, a, b, scrim)) addView(v, LayoutParams(-1, -1))
        for (iv in listOf(a, b)) { iv.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; iv.alpha = 0f }
    }

    /** Built-in photos ("asset:" paths) plus downloaded ones (file paths), one entry per photo. */
    private fun photos(p: Phase): List<String> {
        val builtIn = try {
            context.assets.list("bg/${p.folder}").orEmpty()
                .filter { it.matches(Regex(".*\\.(jpe?g|png|webp)", RegexOption.IGNORE_CASE)) }
                .map { "asset:bg/${p.folder}/$it" }
        } catch (e: Exception) { emptyList() }
        // The downloaded (full-quality) copy wins over the built-in starter copy of the same photo.
        val downloaded = PhotoLibrary.files(context, p.folder).map { it.path }
        val have = downloaded.map { idOf(it) }.toSet()
        return downloaded + builtIn.filter { idOf(it) !in have }
    }

    private fun idOf(path: String) = path.substringAfterLast('/').substringBeforeLast('.')
    /** Call every ~20 seconds. Swaps photo on phase change or when the rotation is due. */
    fun tick(now: ZonedDateTime = ZonedDateTime.now(Config.ZONE)) {
        sky.refresh(now)
        val p = SunClock.phase(now)
        val due = System.currentTimeMillis() - lastSwap >= Config.PHOTO_ROTATE_MINUTES * 60_000
        if (p != phase) { phase = p; queue.clear(); next(fast = lastSwap == 0L) }
        else if (due) next(fast = false)
    }

    private val decoder = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var decoding = false
    private var pendingSwap = false   // time of day changed mid-decode: show the new set next

    /** Decodes the next photo off the main thread (no stutter), then cross-fades to it. */
    fun next(fast: Boolean = false) {
        val p = phase ?: return
        if (decoding) { pendingSwap = true; return }
        if (queue.isEmpty()) {
            queue.addAll(photos(p).shuffled())
            captions = builtInCaptions + PhotoLibrary.captions(context)
        }
        val path = queue.removeFirstOrNull() ?: run { a.alpha = 0f; b.alpha = 0f; return }
        decoding = true
        lastSwap = System.currentTimeMillis()
        decoder.execute {
            val bmp = decode(path)
            post {
                decoding = false
                when {
                    pendingSwap -> { pendingSwap = false; if (bmp != null) show(path, bmp, fast); next(false) }
                    bmp != null -> show(path, bmp, fast)
                    queue.isNotEmpty() -> next(fast)   // a photo was removed meanwhile: try the next
                }
            }
        }
    }

    private fun show(path: String, bmp: Bitmap, fast: Boolean) {
        onCaption?.invoke(captions[idOf(path)].orEmpty())
        val incoming = if (front === a) b else a
        val outgoing = front
        incoming.animate().cancel()
        incoming.setImageBitmap(bmp)
        incoming.scaleX = 1f; incoming.scaleY = 1f
        incoming.bringToFront(); scrim.bringToFront()
        val fade = if (fast) 0L else Config.PHOTO_FADE_MS
        incoming.alpha = if (fast) 1f else 0f
        incoming.animate().alpha(1f).setDuration(fade).withEndAction { outgoing.alpha = 0f; outgoing.setImageDrawable(null) }.start()
        // Slow drift, like Apple TV's aerials. Hardware-accelerated, so cheap on the box.
        incoming.animate().scaleX(1.08f).scaleY(1.08f)
            .setDuration(Config.PHOTO_ROTATE_MINUTES * 60_000 + fade)
            .setInterpolator(android.view.animation.LinearInterpolator()).start()
        front = incoming
    }

    /**
     * Decodes at the screen's real resolution: a 4K photo on a 1080p screen is shrunk 2x while
     * decoding (sharp, and a quarter of the memory); on a 4K screen it stays full size.
     * Full colour (no banding in skies).
     */
    private fun decode(path: String): Bitmap? = try {
        fun open() = if (path.startsWith("asset:")) context.assets.open(path.removePrefix("asset:"))
                     else java.io.File(path).inputStream()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        val dm = resources.displayMetrics
        val targetW = maxOf(width, dm.widthPixels, 1280); val targetH = maxOf(height, dm.heightPixels, 720)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetW && bounds.outHeight / (sample * 2) >= targetH) sample *= 2
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inSampleSize = sample }
        open().use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (e: Throwable) { null }   // incl. OutOfMemoryError: skip that photo, keep going

    /** Full-screen photo mode: hide the darkening so the photo shows clean. */
    fun setAmbient(on: Boolean) { scrim.animate().alpha(if (on) 0f else 1f).setDuration(600).start() }
}
