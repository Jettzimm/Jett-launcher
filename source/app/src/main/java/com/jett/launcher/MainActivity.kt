package com.jett.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.net.URL
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(3)
    private val zone: ZoneId = Config.ZONE
    private val logoCache = ConcurrentHashMap<String, Bitmap>()

    private lateinit var backdrop: Backdrop
    private lateinit var caption: TextView
    private lateinit var content: View
    private lateinit var page: ScrollView
    private lateinit var moreApps: LinearLayout
    /** Every app-grid tile in order (first row + the rows below). */
    private val gridTiles = mutableListOf<View>()
    private lateinit var sportsLabel: TextView
    private var ambient = false
    private lateinit var clock: TextView
    private lateinit var greeting: TextView
    private lateinit var dateLine: TextView
    private lateinit var sportsSection: LinearLayout
    private lateinit var sportsRow: LinearLayout
    private lateinit var appRow: LinearLayout

    private var dp = 1f
    companion object {
        /** Home screen showing, and when a remote button was last pressed on it (for 4am updates). */
        @Volatile var onHome = false
        @Volatile var lastKeyAt = 0L
        /** The home screen is the top screen (still true while the TV is off with it showing). */
        @Volatile var homeOnTop = false
        /** A home screen exists in this process (created and not destroyed). */
        @Volatile var alive = false
    }
    private var resumed = false
    private var lastAppsSignature = ""

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // Power-on rules run app-wide in JettApp; here we just leave photo mode.
            if (i.action == Intent.ACTION_SCREEN_ON && ambient) setAmbient(false)
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** Zoom the whole home screen out (Config.UI_SCALE) by drawing it at a lower density. */
    override fun attachBaseContext(base: Context) {
        val c = android.content.res.Configuration(base.resources.configuration)
        c.densityDpi = (c.densityDpi * Config.UI_SCALE).toInt()
        super.attachBaseContext(base.createConfigurationContext(c))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        alive = true
        dp = resources.displayMetrics.density
        setContentView(buildUi())
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        Rules.scheduleNflCheck(this)
        WindowAlarm.scheduleNext(this)
        Updater.scheduleDaily(this)
        // A real boot counts as a power-on. So does the TV waking into a freshly restarted app
        // (Android had closed it during standby), since that TV-on signal went out before we existed.
        val freshProcess = android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime() < 30_000L
        val screenOn = getSystemService(android.os.PowerManager::class.java).isInteractive
        if (android.os.SystemClock.elapsedRealtime() < 5 * 60_000L || (freshProcess && screenOn)) PowerOn.onWake(this)
    }

    override fun onResume() {
        super.onResume()
        resumed = true; onHome = true; homeOnTop = true
        // Resumed by a background restore while the TV is off? Not you using the home screen.
        val screenOn = getSystemService(android.os.PowerManager::class.java).isInteractive
        if (screenOn) lastKeyAt = System.currentTimeMillis()
        backdrop.tick()
        updateClock()
        io.execute { Updater.refreshChannelsIfStale(this); if (screenOn) Updater.confirmIfNeeded(this) }
        buildAppRow()
        refreshSports()
        main.post(clockTick); main.postDelayed(sportsTick, 60_000)
        if (ambient) setAmbient(false)
        resetIdle()
        appRow.getChildAt(0)?.requestFocus()
    }

    override fun onPause() {
        super.onPause()
        resumed = false; onHome = false
        // Paused because another app opened (not just because the TV went off)?
        if (getSystemService(android.os.PowerManager::class.java).isInteractive) homeOnTop = false
        main.removeCallbacks(clockTick); main.removeCallbacks(sportsTick); main.removeCallbacks(idleTimeout)
    }

    override fun onDestroy() {
        alive = false
        unregisterReceiver(screenReceiver); io.shutdownNow(); super.onDestroy()
    }

    // Home screen: Back does nothing. Pressing Home while already home jumps to the first app.
    override fun onBackPressed() {}
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); if (ambient) setAmbient(false)
        page.scrollTo(0, 0); appRow.getChildAt(0)?.requestFocus()   // Home: back to the top
    }

    private val clockTick = object : Runnable {
        override fun run() { updateClock(); backdrop.tick(); main.postDelayed(this, 20_000) }
    }
    private val sportsTick = object : Runnable {
        // Skipped in full-screen photo mode (bar isn't visible); refreshed on the way back.
        override fun run() { if (!ambient) refreshSports(); main.postDelayed(this, 60_000) }
    }

    // ------------------------------------------------------------------ UI

    private fun px(v: Int) = (v * dp).toInt()

    private fun buildUi(): View {
        val root = FrameLayout(this)
        backdrop = Backdrop(this)
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))

        // The page scrolls like Apple TV: the first screen is the clock, the bar and the first row
        // of apps; pressing Down from there scrolls up into the rest of the app grid.
        page = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; isFillViewport = true; clipChildren = false
            overScrollMode = View.OVER_SCROLL_NEVER; isSmoothScrollingEnabled = true
        }
        val pageInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
        }
        page.addView(pageInner, FrameLayout.LayoutParams(-1, -2))
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        content = page

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(64), px(44), px(64), px(28))
            clipChildren = false; clipToPadding = false
        }
        pageInner.addView(col, LinearLayout.LayoutParams(-1, resources.displayMetrics.heightPixels))
        moreApps = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
            setPadding(px(64), 0, px(64), px(64))
        }
        pageInner.addView(moreApps, LinearLayout.LayoutParams(-1, -2))

        // Photo location, bottom-right, shown in full-screen photo mode (like Apple TV).
        caption = TextView(this).apply {
            textSize = 15f; setTextColor(0xD9FFFFFF.toInt()); alpha = 0f
            setShadowLayer(10f, 0f, 2f, 0x99000000.toInt()); letterSpacing = 0.02f
        }
        root.addView(caption, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, px(48), px(36))
        })
        backdrop.onCaption = { text -> caption.text = text }

        clock = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 64f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setShadowLayer(12f, 0f, 2f, 0x66000000)
        }
        greeting = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 30f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setShadowLayer(12f, 0f, 2f, 0x66000000)
        }
        dateLine = TextView(this).apply {
            setTextColor(0xD9FFFFFF.toInt()); textSize = 17f
            setPadding(0, px(4), 0, 0); setShadowLayer(10f, 0f, 2f, 0x66000000)
        }
        col.addView(clock); col.addView(greeting); col.addView(dateLine)
        col.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))

        sportsSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            clipChildren = false; clipToPadding = false
        }
        sportsLabel = sectionLabel("Live & upcoming")
        sportsSection.addView(sportsLabel)
        sportsRow = row()
        sportsSection.addView(scroller(sportsRow))
        col.addView(sportsSection)

        appRow = row()
        col.addView(appRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8) })
        return root
    }

    private fun sectionLabel(t: String) = TextView(this).apply {
        text = t.uppercase(Locale.US); textSize = 13f; letterSpacing = 0.12f
        setTextColor(0xB3FFFFFF.toInt()); setPadding(px(4), 0, 0, px(8))
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false; clipToPadding = false
        setPadding(px(8), px(14), px(8), px(14))
    }

    private fun scroller(child: View) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false
        isFillViewport = false
        addView(child)
    }

    private var lastBedroom = false
    private var moonTile: TextView? = null
    private fun refreshMoon() {
        val m = Sleep.minutesLeft(this)
        moonTile?.text = if (m > 0) "☾\n$m min" else "☾\nSleep"
    }
    private fun isBedroom(now: ZonedDateTime) = now.hour >= Config.BEDROOM_START_HOUR || now.hour < 5

    private fun updateClock() {
        val now = ZonedDateTime.now(zone)
        clock.text = DateTimeFormatter.ofPattern("h:mm", Locale.US).format(now)
        val hello = when (now.hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            else -> "Good evening"
        }
        greeting.text = "$hello, ${Config.NAME}"
        val sleepLeft = Sleep.minutesLeft(this)
        refreshMoon()
        dateLine.text = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US).format(now) +
            (if (sleepLeft > 0) "   ·   ☾ Sleep in $sleepLeft min" else "")
        if (isBedroom(now) != lastBedroom) { lastBedroom = isBedroom(now); lastAppsSignature = ""; buildAppRow() }
    }

    /** Apple-TV-style focus: lift, scale and outline the selected tile. */
    private fun focusable(v: View, radius: Float, onClick: () -> Unit) {
        v.isFocusable = true; v.isClickable = true
        v.setOnClickListener { onClick() }
        v.setOnFocusChangeListener { view, has ->
            // Back on the first screen (bar or first app row): show the whole thing again.
            if (has && (view.parent === appRow || view.parent === sportsRow)) page.smoothScrollTo(0, 0)
            else if (has && view.parent?.parent === moreApps) {
                // Scroll so the focused row sits comfortably in view.
                val rowTop = (view.parent as View).top + moreApps.top
                page.smoothScrollTo(0, (rowTop - px(120)).coerceAtLeast(0))
            }
            view.animate().scaleX(if (has) 1.1f else 1f).scaleY(if (has) 1.1f else 1f)
                .translationZ(if (has) 24f * dp else 0f).setDuration(140).start()
            view.foreground = if (has) GradientDrawable().apply {
                cornerRadius = radius; setStroke(px(3), Color.WHITE)
            } else null
        }
    }

    // ------------------------------------------------------------------ apps

    private fun buildAppRow() {
        val installed = Config.APPS.mapNotNull { app -> Router.installedPackage(this, app)?.let { app to it } }
        val signature = installed.joinToString { it.second } + lastBedroom
        if (signature == lastAppsSignature && appRow.childCount > 0) return
        lastAppsSignature = signature
        // Keep your place: rebuilding (moon tile at 10pm / 5am) shouldn't drop focus.
        val focusedIdx = gridTiles.indexOfFirst { it.hasFocus() }.takeIf { it >= 0 }
        appRow.removeAllViews(); moreApps.removeAllViews(); gridTiles.clear()
        moonTile = null

        // Tile size from the screen width, so exactly APPS_PER_ROW fit across.
        val cols = Config.APPS_PER_ROW
        val gap = px(26)
        val usable = resources.displayMetrics.widthPixels - px(64) * 2 - px(8) * 2
        val w = (usable - gap * (cols - 1)) / cols; val h = w * 9 / 16; val r = 12f * dp
        val tiles = mutableListOf<View>()
        for ((app, pkg) in installed) {
            val tile = FrameLayout(this).apply {
                clipToOutline = true
                background = GradientDrawable().apply { cornerRadius = r; setColor(0xFF22252B.toInt()) }
            }
            val (img, isBanner) = Router.tileImage(this, pkg)
            if (img != null) {
                tile.addView(ImageView(this).apply {
                    setImageDrawable(img)
                    scaleType = if (isBanner) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
                    if (!isBanner) setPadding(px(28), px(20), px(28), px(20))
                }, FrameLayout.LayoutParams(-1, -1))
            }
            if (img == null || !isBanner) {
                tile.addView(TextView(this).apply {
                    text = app.label; setTextColor(Color.WHITE); textSize = 14f
                    gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM; setPadding(0, 0, 0, px(8))
                }, FrameLayout.LayoutParams(-1, -1))
            }
            focusable(tile, r) { Router.openApp(this, app.key) }
            tiles.add(tile)
        }

        // Settings tile, since the stock Google TV home screen is switched off.
        val settings = TextView(this).apply {
            text = "⚙"; textSize = 34f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = r; setColor(0x55FFFFFF) }
        }
        focusable(settings, r) { try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (e: Exception) { } }
        tiles.add(settings)

        // Bedroom mode: sleep timer tile after 10pm.
        if (lastBedroom) {
            val moon = TextView(this).apply {
                textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                background = GradientDrawable().apply { cornerRadius = r; setColor(0x55FFFFFF) }
            }
            moonTile = moon
            fun label() = refreshMoon()
            label()
            focusable(moon, r) { Sleep.cycle(this); label(); updateClock() }
            tiles.add(moon)
        }

        // Lay out: first row on the main screen, the rest in rows below (Apple TV style).
        tiles.chunked(cols).forEachIndexed { i, chunk ->
            val rowView = if (i == 0) appRow else row().also {
                moreApps.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
            }
            chunk.forEachIndexed { j, t ->
                rowView.addView(t, LinearLayout.LayoutParams(w, h).apply { if (j < cols - 1) marginEnd = gap })
            }
        }
        gridTiles.addAll(tiles)
        focusedIdx?.let { idx -> main.post { gridTiles.getOrNull(idx.coerceAtMost(gridTiles.size - 1))?.requestFocus() } }
    }

    // ------------------------------------------------------------------ sports

    @Volatile private var sportsGen = 0

    private fun refreshSports() {
        val gen = ++sportsGen
        io.execute {
            val games = try { Sports.barGames() } catch (e: Exception) { emptyList() }
            // Sunday early games (London etc.) lead the bar and take one pregame card's spot.
            val now = ZonedDateTime.now(zone)
            val early = games.filter {
                it.league.key == "nfl" && now.dayOfWeek == java.time.DayOfWeek.SUNDAY && it.state != "post" &&
                    it.start.atZone(zone).toLocalDate() == now.toLocalDate() &&
                    it.start.atZone(zone).hour < Config.EARLY_NFL_BEFORE_HOUR
            }
            val busy = early.flatMap { it.allNetworks }.map(Router::normalize).toSet()
            val shows = try { Morning.picks(now, Config.MORNING_CARDS - early.size, busy) } catch (e: Exception) { emptyList() }
            val ordered = early + games.filterNot { it in early }
            val ufc = try { Ufc.current() } catch (e: Exception) { null }
            // Nothing live and no morning shows: CNBC, ESPN, CNN.
            // No live game and no morning shows: lead with live channels (CNBC, ESPN, CNN) so
            // there's always something on to click.
            val cards = if (shows.isEmpty() && ordered.none { it.isLive }) Config.ALWAYS_ON else shows
            main.post { if (!isDestroyed && gen == sportsGen) showGames(ordered, cards, early.size, ufc) }   // newest only
        }
    }

    private fun showGames(games: List<Game>, shows: List<Config.Show> = emptyList(), leadGames: Int = 0, ufc: FightCard? = null) {
        val focusedIndex = (0 until sportsRow.childCount).firstOrNull { sportsRow.getChildAt(it).hasFocus() }
        sportsRow.removeAllViews()
        sportsSection.visibility = if (games.isEmpty() && shows.isEmpty() && ufc == null) View.GONE else View.VISIBLE
        sportsLabel.text = (when {
            shows === Config.ALWAYS_ON -> "On now"
            shows.isNotEmpty() && games.isEmpty() && ufc == null -> "On this morning"
            else -> "Live & upcoming"
        }).uppercase(Locale.US)
        val r = 16f * dp
        // Order: early games (London), morning shows, then everything else.
        val lead = games.take(leadGames)
        val rest = games.drop(leadGames)
        for (g in lead) addGameCard(g, r)
        for (show in shows) addShowCard(show, r)
        // Pinned teams, then the UFC card, then everything else.
        rest.filter { it.pinned }.forEach { addGameCard(it, r) }
        ufc?.let { addFightCard(it, r) }
        rest.filterNot { it.pinned }.forEach { addGameCard(it, r) }
        focusedIndex?.let { sportsRow.getChildAt(it.coerceAtMost(sportsRow.childCount - 1))?.requestFocus() }
    }

    private fun addGameCard(g: Game, r: Float) {
        run {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(16), px(12), px(16), px(12))
                background = GradientDrawable().apply {
                    cornerRadius = r; setColor(0xB3141820.toInt())
                    if (g.pinned) setStroke(px(2), 0xFFF2C14E.toInt())
                }
            }
            // Header: league · network          LIVE / time
            val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            header.addView(TextView(this).apply {
                text = listOf(g.league.label, g.networkLabel).filter { it.isNotEmpty() }.joinToString(" · ")
                textSize = 12f; setTextColor(0xB3FFFFFF.toInt()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(TextView(this).apply {
                text = if (g.isLive) "● LIVE" else g.statusText(zone).substringAfter(' ')
                textSize = 12f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (g.isLive) 0xFFFF5A5A.toInt() else 0xCCFFFFFF.toInt())
            })
            card.addView(header)
            card.addView(teamLine(g, away = true))
            card.addView(teamLine(g, away = false))
            if (g.isLive) card.addView(TextView(this).apply {
                text = g.detail; textSize = 12f; setTextColor(0xCCFFFFFF.toInt()); setPadding(0, px(4), 0, 0)
            })
            focusable(card, r) { Router.openGame(this, g) }
            sportsRow.addView(card, LinearLayout.LayoutParams(px(250), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = px(18) })
        }
    }

    /** Numbered UFC card. Opens Paramount+ (or YouTube TV if it's on CBS). */
    private fun addFightCard(f: FightCard, r: Float) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(12), px(16), px(12))
            background = GradientDrawable().apply { cornerRadius = r; setColor(0xB3141820.toInt()) }
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "UFC · ${f.network}"; textSize = 12f; setTextColor(0xB3FFFFFF.toInt())
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(this).apply {
            text = if (f.isLive) "● LIVE" else DateTimeFormatter.ofPattern("h:mm a", Locale.US).format(f.start.atZone(zone))
            textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (f.isLive) 0xFFFF5A5A.toInt() else 0xCCFFFFFF.toInt())
        })
        card.addView(header)
        card.addView(TextView(this).apply {
            text = f.title; textSize = 30f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            setPadding(0, px(6), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = f.headline; textSize = 17f; setTextColor(Color.WHITE); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        f.nowFighting?.let { now ->
            card.addView(TextView(this).apply {
                text = "Now: $now"; textSize = 12f; setTextColor(0xCCFFFFFF.toInt()); setPadding(0, px(4), 0, 0)
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
        }
        focusable(card, r) {
            when (val app = Router.appForNetwork(f.network, null)) {
                "yttv" -> Router.openChannel(this, f.network)
                null -> Router.openApp(this, "paramount")
                else -> Router.openApp(this, app)
            }
        }
        sportsRow.addView(card, LinearLayout.LayoutParams(px(250), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = px(18) })
    }

    /** Morning TV card: channel, show, LIVE until when. Opens the channel in YouTube TV. */
    private fun addShowCard(show: Config.Show, r: Float) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(12), px(16), px(12))
            background = GradientDrawable().apply { cornerRadius = r; setColor(0xB3141820.toInt()) }
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "YOUTUBE TV"; textSize = 12f; setTextColor(0xB3FFFFFF.toInt()); letterSpacing = 0.08f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(this).apply {
            text = if (show.upcoming) DateTimeFormatter.ofPattern("h:mm a", Locale.US).format(java.time.LocalTime.parse(show.start)) else "● LIVE"
            textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (show.upcoming) 0xCCFFFFFF.toInt() else 0xFFFF5A5A.toInt())
        })
        card.addView(header)
        card.addView(TextView(this).apply {
            text = show.channel; textSize = 30f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            setPadding(0, px(6), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = show.title; textSize = 17f; setTextColor(Color.WHITE); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        val until = java.time.LocalTime.parse(show.end)
        if (show.end != "23:59") card.addView(TextView(this).apply {
            text = if (show.upcoming) "starts " + DateTimeFormatter.ofPattern("h:mm a", Locale.US).format(java.time.LocalTime.parse(show.start))
                   else "until " + DateTimeFormatter.ofPattern("h:mm a", Locale.US).format(until)
            textSize = 12f; setTextColor(0xCCFFFFFF.toInt()); setPadding(0, px(4), 0, 0)
        })
        focusable(card, r) { Router.openChannel(this, show.channel) }
        sportsRow.addView(card, LinearLayout.LayoutParams(px(250), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = px(18) })
    }

    private fun teamLine(g: Game, away: Boolean): View {
        val abbr = if (away) g.awayAbbr else g.homeAbbr
        val score = if (g.state == "pre") "" else if (away) g.awayScore else g.homeScore
        val rank = if (away) g.awayRank else g.homeRank
        val logoUrl = if (away) g.awayLogo else g.homeLogo
        val line = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(6), 0, 0) }
        val logo = ImageView(this)
        line.addView(logo, LinearLayout.LayoutParams(px(26), px(26)).apply { marginEnd = px(10) })
        logoUrl?.let { loadLogo(it, logo) }
        line.addView(TextView(this).apply {
            text = (if (rank != null) "$rank " else "") + abbr
            textSize = 19f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(TextView(this).apply {
            text = score; textSize = 21f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        })
        return line
    }

    private fun loadLogo(url: String, into: ImageView) {
        logoCache[url]?.let { into.setImageBitmap(it); return }
        io.execute {
            val small = try {
                val c = URL(url).openConnection().apply { connectTimeout = 6000; readTimeout = 8000 }
                val bmp = c.getInputStream().use { BitmapFactory.decodeStream(it) }
                bmp?.let { Bitmap.createScaledBitmap(it, 96, (96 * it.height / it.width.coerceAtLeast(1)).coerceAtLeast(1), true) }
            } catch (e: Exception) { null }
            if (small != null) {
                logoCache[url] = small
                main.post { into.setImageBitmap(small) }
            }
        }
    }

    // ------------------------------------------------------------------ rules


    /** Full-screen photo mode, like Apple TV: everything fades away except the photo. */
    private val idleTimeout = Runnable { if (resumed && !ambient) setAmbient(true) }

    /** Restart the 2-minute countdown to full-screen photos. */
    private fun resetIdle() {
        main.removeCallbacks(idleTimeout)
        main.postDelayed(idleTimeout, Config.IDLE_TO_PHOTOS_SECONDS * 1000)
    }

    // Photo mode keeps the screen on (so Google TV's own screensaver doesn't cover the photos),
    // but only for 4 hours without a button press, so the box can still go to standby.
    private val releaseKeepOn = Runnable { window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }

    private fun setAmbient(on: Boolean) {
        ambient = on
        main.removeCallbacks(releaseKeepOn)
        if (on) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            main.postDelayed(releaseKeepOn, 4 * 3600_000L)
        } else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        content.animate().alpha(if (on) 0f else 1f).setDuration(600).start()
        backdrop.setAmbient(on)
        caption.animate().alpha(if (on) 1f else 0f).setDuration(600).start()
        if (!on) refreshSports()
    }

    private fun topRow(): LinearLayout =
        if (sportsSection.visibility == View.VISIBLE && sportsRow.childCount > 0) sportsRow else appRow

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        lastKeyAt = System.currentTimeMillis()
        if (event.action == KeyEvent.ACTION_DOWN) resetIdle()
        if (ambient) {
            // Any button brings the home screen back (and isn't passed on).
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode != KeyEvent.KEYCODE_HOME) setAmbient(false)
            return event.keyCode != KeyEvent.KEYCODE_HOME || super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            val f = currentFocus
            if (f != null && f.parent === topRow()) { setAmbient(true); return true }
        }
        // Down from the clock area always lands on the app row.
        if (event.action == KeyEvent.ACTION_DOWN && currentFocus == null &&
            event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP)
        ) { appRow.getChildAt(0)?.requestFocus(); return true }
        return super.dispatchKeyEvent(event)
    }
}
