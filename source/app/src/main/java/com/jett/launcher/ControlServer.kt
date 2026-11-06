package com.jett.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.jett.launcher.ControlCore.Request
import com.jett.launcher.ControlCore.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.time.ZonedDateTime

/**
 * The phone app's back end: a small web server on the box (home Wi-Fi only, port [PORT]).
 * It serves the app itself (assets/control) and a JSON API to read and change settings and to
 * do things now (volume, channels, photos, sleep). A phone has to pair once with a 4-digit code
 * shown on the TV, so nobody else on the Wi-Fi can drive the TV.
 */
object ControlServer {
    const val PORT = 8765
    private const val TAG = "JettControl"
    private lateinit var app: Context
    // At most 6 requests at once, 24 waiting; anything past that is turned away at once.
    private val pool = java.util.concurrent.ThreadPoolExecutor(6, 6, 30, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue(24))
    private val main = Handler(Looper.getMainLooper())
    private val watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    @Volatile private var started = false
    private val rnd = SecureRandom()

    fun start(ctx: Context) {
        if (started) return
        started = true
        app = ctx.applicationContext
        Thread({
            while (true) {
                try {
                    ServerSocket().use { ss ->
                        ss.reuseAddress = true
                        ss.bind(InetSocketAddress(PORT))
                        Log.i(TAG, "Phone app at http://<box>:$PORT")
                        while (true) {
                            val s = ss.accept()
                            try { pool.execute { handle(s) } } catch (e: java.util.concurrent.RejectedExecutionException) { try { s.close() } catch (x: Exception) { } }
                        }
                    }
                } catch (e: Exception) { Log.w(TAG, "Server stopped, restarting", e); Thread.sleep(5000) }
            }
        }, "control-server").apply { isDaemon = true }.start()
        // Make the phone's photo grid quick: small previews of every photo, made once, slowly,
        // in the background (a minute after start, so it doesn't compete with start-up).
        Thread({
            try {
                Thread.sleep(60_000)
                for (p in PhotoIndex.all(app)) {
                    if (!File(File(app.cacheDir, "thumbs"), "${p.id}_240.jpg").exists()) { try { photo(p.id, 240) } catch (e: Throwable) { } ; Thread.sleep(150) }
                }
            } catch (e: Throwable) { }
        }, "thumbs").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    private fun handle(s: Socket) {
        s.use { sock ->
            try {
                sock.soTimeout = 5_000
                // The whole request must arrive within 10 s (a trickling client can't hold a worker).
                val cut = watchdog.schedule({ try { sock.close() } catch (e: Exception) { } }, 10, java.util.concurrent.TimeUnit.SECONDS)
                val req = try { ControlCore.readRequest(sock.getInputStream()) } finally { cut.cancel(false) } ?: return
                val res = when {
                    !ControlCore.hostOk(req.header("host")) -> Response.text("Forbidden", 403)
                    req.body.isNotEmpty() && ControlCore.tooDeep(req.bodyText()) -> Response.text("Bad request", 400)
                    // Throwable: one bad request (out of memory on a photo, a bug) must never take the server down.
                    else -> try { route(req) } catch (e: Throwable) {
                        Log.w(TAG, "Error on ${req.path}", e); Response.json(JSONObject().put("error", "error"), 500)
                    }
                }
                ControlCore.writeResponse(sock.getOutputStream(), res)
            } catch (e: Throwable) {
                try { ControlCore.writeResponse(sock.getOutputStream(), Response.text("Bad request", 400)) } catch (x: Exception) { }
            }
        }
    }

    // ------------------------------------------------------------------ routing

    private val STATIC = mapOf(
        "/" to ("control/index.html" to "text/html; charset=utf-8"),
        "/index.html" to ("control/index.html" to "text/html; charset=utf-8"),
        "/app.webmanifest" to ("control/app.webmanifest" to "application/manifest+json"),
        "/icon-180.png" to ("control/icon-180.png" to "image/png"),
        "/icon-512.png" to ("control/icon-512.png" to "image/png"),
        "/fonts/serif.ttf" to ("clock.ttf" to "font/ttf"),
        "/fonts/italic.ttf" to ("caption.ttf" to "font/ttf"),
    )

    /** A request that came through the anywhere relay (already proven by its seal). */
    fun relayRoute(method: String, target: String, body: String): Response {
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
            java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }
        // Pairing, phone tokens and the anywhere link itself are home-Wi-Fi only.
        if (path !in setOf("/api/state", "/api/catalog", "/api/settings", "/api/action", "/api/bar")) return Response.text("Not here", 404)
        if (body.isNotEmpty() && ControlCore.tooDeep(body)) return Response.text("Bad request", 400)
        val r = Request(method, path, query, emptyMap(), body.toByteArray(Charsets.UTF_8))
        return try { route(r, trusted = true) } catch (e: Throwable) {
            Log.w(TAG, "Relay error on $path", e); Response.json(JSONObject().put("error", "error"), 500)
        }
    }

    private fun route(r: Request, trusted: Boolean = false): Response {
        STATIC[r.path]?.let { (asset, type) ->
            val bytes = app.assets.open(asset).use { it.readBytes() }
            val cache = if (asset.endsWith(".html")) "no-cache" else "public, max-age=604800"
            return Response(200, type, bytes, mapOf("Cache-Control" to cache))
        }
        if (r.path == "/api/ping") return Response.json(JSONObject().put("ok", true).put("name", Config.NAME).put("paired", authed(r)))
        if (r.path == "/api/pair/start" && r.method == "POST") return pairStart()
        if (r.path == "/api/pair" && r.method == "POST") return pair(r)
        if (r.path.startsWith("/api/") && !trusted && !authed(r)) return Response.json(JSONObject().put("error", "pair"), 401)
        return when (r.path) {
            "/api/remote" -> when {
                trusted -> Response.text("Not here", 404)
                r.method == "POST" -> { Relay.reset(); Response.json(JSONObject().put("link", Relay.link())) }
                else -> Response.json(JSONObject().put("link", Relay.link()).put("connected", Relay.connected))
            }
            "/api/unpair" -> if (r.method == "POST") unpair(r) else Response.text("POST only", 405)
            "/api/bar" -> Response.json(MainActivity.barJson ?: JSONObject().put("label", "").put("items", JSONArray()))
            "/api/appicon" -> appIcon(r.query["key"].orEmpty())
            "/api/state" -> Response.json(state())
            "/api/catalog" -> Response.json(catalog(withPhotos = r.query["lite"] == null))
            "/api/settings" -> if (r.method == "POST") {
                val patch = JSONObject(r.bodyText())
                // The main sections must stay objects (a wrong type would silently switch things off).
                for (k in patch.keys()) if (k in AppSettings.SECTIONS && patch.get(k) !is JSONObject) return Response.text("bad $k", 400)
                val s = AppSettings.update(app, patch)
                main.post { MainActivity.current?.settingsChanged() }
                Response.json(JSONObject().put("settings", s))
            } else Response.json(JSONObject().put("settings", AppSettings.all(app)))
            "/api/action" -> if (r.method == "POST") action(JSONObject(r.bodyText())) else Response.text("POST only", 405)
            "/api/photo" -> photo(r.query["id"].orEmpty(), r.query["w"]?.toIntOrNull() ?: 480)
            "/api/now.jpg" -> MainActivity.current?.nowShowing()?.let { photo(it.first, 900) } ?: Response.text("none", 404)
            else -> Response.text("Not found", 404)
        }
    }

    // ------------------------------------------------------------------ pairing

    private fun prefs() = app.getSharedPreferences("control", Context.MODE_PRIVATE)
    @Volatile private var code: String? = null
    @Volatile private var codeUntil = 0L
    // Guessing protection: 10 wrong codes (across any number of new codes) locks pairing for
    // 10 minutes; a new code at most every 30 s; only while the TV is on (you must see the code).
    @Volatile private var badTries = 0
    @Volatile private var lockedUntil = 0L
    @Volatile private var lastCodeAt = 0L
    @Volatile private var lockouts = 0

    private fun authed(r: Request): Boolean {
        // Header for API calls; ?k= for images (an <img> can't send headers).
        val t = r.header("x-jett-token") ?: r.query["k"] ?: return false
        return t.length >= 20 && t in prefs().getStringSet("tokens", emptySet()).orEmpty()
    }

    @Synchronized private fun pairStart(): Response {
        val now = System.currentTimeMillis()
        val tvOn = app.getSystemService(PowerManager::class.java).isInteractive
        if (!tvOn) return Response.json(JSONObject().put("error", "tvoff").put("tvOn", false), 409)
        if (now < lockedUntil) return Response.json(JSONObject().put("error", "locked"), 429)
        val current = code
        if (current != null && now < codeUntil) {   // a code is already up: keep showing it
            main.post { PairBanner.show(app, current) }
            return Response.json(JSONObject().put("ok", true).put("tvOn", true))
        }
        if (now - lastCodeAt < 30_000) return Response.json(JSONObject().put("error", "wait"), 429)
        val c = String.format("%04d", rnd.nextInt(10000))
        code = c; codeUntil = now + 3 * 60_000L; lastCodeAt = now
        main.post { PairBanner.show(app, c) }
        return Response.json(JSONObject().put("ok", true).put("tvOn", app.getSystemService(PowerManager::class.java).isInteractive))
    }

    @Synchronized private fun pair(r: Request): Response {
        val now = System.currentTimeMillis()
        if (now < lockedUntil) return Response.json(JSONObject().put("error", "locked"), 429)
        val given = try { JSONObject(r.bodyText()).optString("code") } catch (e: Exception) { "" }
        val c = code
        if (c == null || now > codeUntil) return Response.json(JSONObject().put("error", "expired"), 403)
        if (given != c) {
            if (++badTries % 5 == 0) { code = null; main.post { PairBanner.hide(app) } }   // every 5 wrong: that code is burned
            if (badTries >= 10) {
                // Locked out, longer each time (10 min, 20, 40 … up to a day).
                lockedUntil = now + minOf(10 * 60_000L shl lockouts, 24 * 3600_000L); lockouts = minOf(lockouts + 1, 8)
                badTries = 0; code = null; main.post { PairBanner.hide(app) }
            }
            return Response.json(JSONObject().put("error", if (now < lockedUntil) "locked" else "wrong"), 403)
        }
        code = null; badTries = 0; lockouts = 0
        val token = ByteArray(24).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val set = prefs().getStringSet("tokens", emptySet()).orEmpty().toMutableSet().apply { add(token) }
        prefs().edit().putStringSet("tokens", set).apply()
        main.post { PairBanner.hide(app) }
        return Response.json(JSONObject().put("token", token))
    }

    @Synchronized private fun unpair(r: Request): Response {
        val t = r.header("x-jett-token").orEmpty()
        val set = prefs().getStringSet("tokens", emptySet()).orEmpty().toMutableSet().apply { remove(t) }
        prefs().edit().putStringSet("tokens", set).apply()
        return ok()
    }

    /** An app's TV banner (or icon) as a PNG, for the phone's app tiles. Cached per app. */
    private val iconCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private fun appIcon(key: String): Response {
        val a = Router.appByKey(key) ?: return Response.text("no app", 404)
        val bytes = iconCache[key] ?: run {
            val pkg = Router.installedPackage(app, a) ?: return Response.text("not installed", 404)
            val (d, banner) = Router.tileImage(app, pkg)
            d ?: return Response.text("no icon", 404)
            val w = if (banner) 320 else 160; val h = if (banner) 180 else 160
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            d.setBounds(0, 0, w, h); d.draw(c)
            val out = java.io.ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.PNG, 100, out); bmp.recycle()
            out.toByteArray().also { iconCache[key] = it }
        }
        return Response(200, "image/png", bytes, mapOf("Cache-Control" to "public, max-age=86400"))
    }

    // ------------------------------------------------------------------ state

    private fun ev(e: ControlCore.Event?) = e?.let {
        JSONObject().put("at", it.at.format(LOCAL)).put("date", it.date.toString())
            .put("content", it.m.content).put("volume", it.m.volume).put("switchTo", it.m.switchTo)
            .put("switchVolume", it.m.switchVolume)
    }

    /** Always "2026-11-09T08:00:00" (New York time): the phone reads these with fixed slices. */
    private val LOCAL = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    private fun state(): JSONObject {
        val tvOn = app.getSystemService(PowerManager::class.java).isInteractive
        val next = try { WakeUp.upcoming(app) } catch (e: Exception) { emptyMap() }
        val home = MainActivity.current
        val now = home?.nowShowing()
        val w = try { Weather.cachedOrNull(app) } catch (e: Exception) { null }
        return JSONObject()
            .put("settings", AppSettings.all(app))
            .put("status", JSONObject()
                .put("version", app.packageManager.getPackageInfo(app.packageName, 0).versionName)
                .put("time", ZonedDateTime.now(Config.ZONE).format(LOCAL))
                .put("tvOn", tvOn)
                .put("onHome", MainActivity.onHome)
                .put("photoMode", home?.isAmbient() ?: false)
                .put("sleepMinutes", Sleep.minutesLeft(app))
                .put("nowPhoto", now?.let { JSONObject().put("id", it.first).put("caption", it.second) })
                .put("weather", w?.let { JSONObject().put("tempF", it.tempF).put("icon", it.icon).put("heads", it.heads) })
                .put("next", JSONObject()
                    .put("on", ev(next[ControlCore.Step.ON]))
                    .put("switch", ev(next[ControlCore.Step.SWITCH]))
                    .put("off", ev(next[ControlCore.Step.OFF]))))
    }

    private fun catalog(withPhotos: Boolean = true): JSONObject {
        val apps = JSONArray()
        for (a in Config.APPS) apps.put(JSONObject().put("key", a.key).put("label", a.label)
            .put("installed", Router.installedPackage(app, a) != null))
        val channels = (Config.YTTV_CHANNEL_CODES.keys + Updater.channelNames(app)).distinct()
            .filter { it.length > 2 || it in setOf("CBS", "NBC", "FOX", "ABC", "CNN", "TNT", "TBS", "FS1", "FS2", "BTN", "USA", "CW") }
            .sorted()
        val sounds = JSONArray()
        for ((id, title, sub) in AppSettings.SOUNDS) sounds.put(JSONObject().put("id", id).put("title", title).put("sub", sub))
        val photos = JSONArray()
        if (withPhotos) for (p in PhotoIndex.all(app)) photos.put(JSONArray().put(p.id).put(p.phase).put(p.caption))
        return JSONObject().put("apps", apps).put("channels", JSONArray(channels)).put("sounds", sounds)
            .put("leagues", JSONArray(listOf("nfl", "cfb", "nhl", "nba", "mlb", "ufc"))).put("photos", photos)
    }

    // ------------------------------------------------------------------ actions

    private fun ok(extra: JSONObject.() -> Unit = {}) = Response.json(JSONObject().put("ok", true).apply(extra))

    private fun onMain(block: () -> Unit) = main.post { try { block() } catch (e: Exception) { Log.w(TAG, "action", e) } }

    private fun goHome() = Router.openTarget(app, "home")

    private fun action(a: JSONObject): Response {
        when (a.optString("do")) {
            "volume" -> {
                val level = a.optInt("level", -1).takeIf { it in 0..100 } ?: return Response.text("level 0–100", 400)
                val done = SamsungTv.setVolume(app, level, waitSeconds = 8)
                return ok { put("volume", if (done) level else JSONObject.NULL) }
            }
            "getVolume" -> {
                val ip = SamsungTv.find(app)
                val v = ip?.let { SamsungTv.getVolume(it) }
                val m = ip?.let { SamsungTv.getMute(it) }
                return ok { put("volume", v ?: JSONObject.NULL); put("muted", m ?: JSONObject.NULL) }
            }
            "key" -> {
                val k = a.optString("key")
                if (!k.matches(Regex("KEY_[A-Z0-9_]{2,20}"))) return Response.text("bad key", 400)
                return ok { put("sent", SamsungTv.pressKey(app, k)) }
            }
            "home" -> onMain { goHome(); MainActivity.current?.remoteAmbient(false) }
            "game" -> {
                val g = MainActivity.barGames[a.optString("id")] ?: return Response.text("That game's no longer on the bar", 404)
                onMain { Router.openGame(app, g) }
            }
            "fight" -> {
                val f = MainActivity.barFight ?: return Response.text("No fight on the bar", 404)
                onMain { MainActivity.openFight(app, f) }
            }
            "open" -> {
                val t = a.optString("target")
                if (t.startsWith("app:")) onMain { Router.openApp(app, t.removePrefix("app:")) }
                else onMain { WakeUp.openAndPlay(app, t) }
            }
            "photos" -> {
                val on = a.optBoolean("on", true)
                onMain {
                    if (!MainActivity.onHome) goHome()
                    main.postDelayed({ MainActivity.current?.remoteAmbient(on) }, if (MainActivity.onHome) 0 else 900)
                }
            }
            "showPhoto" -> {
                val id = a.optString("id")
                PhotoIndex.find(app, id) ?: return Response.text("no photo", 404)   // (also warms the list off the main thread)
                onMain {
                    if (!MainActivity.onHome) goHome()
                    main.postDelayed({ MainActivity.current?.let { it.remoteAmbient(true); it.remoteShowPhoto(id) } }, if (MainActivity.onHome) 0 else 900)
                }
            }
            "sleep" -> { val m = a.optInt("minutes", 0).coerceIn(0, 600); Sleep.set(app, m); onMain { MainActivity.current?.settingsChanged() } }
            "off" -> onMain { Sleep.sleepNow(app) }
            "on" -> onMain { WakeUp.wakeNow(app, "home", -1) }
            "tryMorning" -> {
                // Run a morning now (today's plan, or the one sent) so you can hear it.
                val m = ControlCore.morning(a.optJSONObject("plan")) ?: ControlCore.planFor(AppSettings.wake(), java.time.LocalDate.now(Config.ZONE))
                    ?: return Response.text("no plan", 400)
                onMain { WakeUp.wakeNow(app, m.content, m.volume) }
            }
            else -> return Response.text("unknown action", 400)
        }
        return ok()
    }

    // ------------------------------------------------------------------ photos

    private val SAFE_ID = Regex("[A-Za-z0-9_-]{4,80}")

    private fun photo(id: String, w: Int): Response {
        if (!SAFE_ID.matches(id)) return Response.text("bad id", 400)
        val width = listOf(240, 480, 900).firstOrNull { it >= w } ?: 900
        val dir = File(app.cacheDir, "thumbs").apply { mkdirs() }
        val f = File(dir, "${id}_$width.jpg")
        if (!f.exists()) {
            val p = PhotoIndex.find(app, id) ?: return Response.text("no photo", 404)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            PhotoIndex.open(app, p.path).use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= width) sample *= 2
            val bmp = PhotoIndex.open(app, p.path).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                ?: return Response.text("unreadable", 500)
            val h = (bmp.height * width.toFloat() / bmp.width).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bmp, width, h, true)
            val tmp = File(dir, "${id}_$width.${Thread.currentThread().id}.tmp")
            tmp.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            tmp.renameTo(f)
            if (scaled !== bmp) scaled.recycle(); bmp.recycle()
        }
        return Response(200, "image/jpeg", f.readBytes(), mapOf("Cache-Control" to "public, max-age=2592000"))
    }
}

/** "Pair your phone: 4831", shown top-right on the TV for three minutes (doesn't take the remote). */
object PairBanner {
    private var view: LinearLayout? = null

    fun show(ctx: Context, code: String) {
        hide(ctx)
        val wm = ctx.getSystemService(WindowManager::class.java)
        val dp = ctx.resources.displayMetrics.density * Config.UI_SCALE
        fun px(v: Int) = (v * dp).toInt()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(30), px(20), px(30), px(22))
            background = GradientDrawable().apply { cornerRadius = 20f * dp; setColor(0xEE111419.toInt()) }
        }
        card.addView(TextView(ctx).apply {
            text = "PAIR YOUR PHONE"; textSize = 11f; letterSpacing = 0.16f; setTextColor(0xFFE8C27A.toInt())
        })
        card.addView(TextView(ctx).apply {
            text = code.toCharArray().joinToString("  "); textSize = 44f; setTextColor(0xFFFFFFFF.toInt())
            typeface = try { Typeface.Builder(ctx.assets, "clock.ttf").setFontVariationSettings("'wght' 500").build() } catch (e: Exception) { Typeface.SERIF }
            fontFeatureSettings = "'lnum'"
        })
        card.addView(TextView(ctx).apply {
            text = "Type this code in the app on your phone"; textSize = 12f; setTextColor(0xCCFFFFFF.toInt())
        })
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = px(48); y = px(48) }
        try {
            wm.addView(card, lp); view = card
            card.alpha = 0f; card.animate().alpha(1f).setDuration(300).start()
            Handler(Looper.getMainLooper()).postDelayed({ if (view === card) hide(ctx) }, 3 * 60_000L)
        } catch (e: Exception) { view = null }
    }

    fun hide(ctx: Context) {
        val v = view ?: return
        view = null
        try { ctx.getSystemService(WindowManager::class.java).removeView(v) } catch (e: Exception) { }
    }
}
