package com.jett.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log
import android.widget.Toast

/** Decides which app (and ideally which channel) to open, then opens it. */
object Router {

    private const val TAG = "JettRouter"

    fun installedPackage(ctx: Context, app: Config.App): String? =
        app.packages.firstOrNull { pkg ->
            try { ctx.packageManager.getApplicationInfo(pkg, 0); true }
            catch (e: PackageManager.NameNotFoundException) { false }
        }

    fun appByKey(key: String) = Config.APPS.firstOrNull { it.key == key }

    fun tileImage(ctx: Context, pkg: String): Pair<Drawable?, Boolean> {
        val pm = ctx.packageManager
        return try {
            val banner = pm.getApplicationBanner(pkg)
                ?: pm.getLeanbackLaunchIntentForPackage(pkg)?.let { pm.getActivityBanner(it) }
            if (banner != null) banner to true else pm.getApplicationIcon(pkg) to false
        } catch (e: Exception) { null to false }
    }

    fun openApp(ctx: Context, key: String): Boolean {
        val app = appByKey(key) ?: return false
        val pkg = installedPackage(ctx, app) ?: run {
            toast(ctx, "${app.label} isn't installed"); return false
        }
        val pm = ctx.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { ctx.startActivity(intent); true } catch (e: Exception) { Log.w(TAG, "Couldn't open $pkg", e); false }
    }

    /** Opens a live channel: deep link into YouTube TV if we have one, else just the app. */
    /** A channel name ("CNBC") or a YouTube video ("youtube:<id>"). */
    fun openTarget(ctx: Context, target: String): Boolean {
        if (target.isBlank() || target == "home") {
            ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(ctx.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        }
        if (target.startsWith("youtube:")) {
            val id = target.removePrefix("youtube:")
            return tryDeepLink(ctx, "youtube", "https://www.youtube.com/watch?v=$id") || openApp(ctx, "youtube")
        }
        return openChannel(ctx, target)
    }

    fun openChannel(ctx: Context, rawChannel: String): Boolean {
        val channel = normalize(rawChannel)
        val app = appForNetwork(channel, null) ?: "yttv"
        if (app == "yttv") {
            // Direct link with the channel's code (from the update feed). No code → YouTube TV home.
            val code = Updater.channelCode(ctx, channel)
            if (code.isNotEmpty() && openYttvChannel(ctx, code)) return true
        }
        return openApp(ctx, app)
    }

    /**
     * Tunes YouTube TV to a live channel. First the form Android TV users have confirmed tunes
     * the channel (`watch/<code>` to YouTube TV's main activity), then the web link.
     * The update feed's "linkStyle" (or Config.YTTV_LINK_STYLE) flips the order without a rebuild.
     */
    private fun openYttvChannel(ctx: Context, code: String): Boolean {
        val pkg = appByKey("yttv")?.let { installedPackage(ctx, it) } ?: return false
        val inApp = {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("watch/$code"))
                    .setClassName(pkg, "com.google.android.apps.youtube.tvunplugged.activity.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)); true
            } catch (e: Exception) { Log.w(TAG, "YTTV in-app link failed", e); false }
        }
        val web = { tryDeepLink(ctx, "yttv", "https://tv.youtube.com/watch/$code") }
        return if (Updater.linkStyle(ctx) == "web") web() || inApp() else inApp() || web()
    }

    fun openGame(ctx: Context, game: Game) {
        // 0) A link for this exact game from the update feed (Netflix, Peacock, Apple TV …).
        Updater.gameLink(ctx, game.id)?.let { link -> if (openLink(ctx, link)) return }
        // Prime-only game (Thursday night etc.): straight to the game's page in Prime.
        if (Prime.isPrime(game) && game.nationalNetworks.none { appForNetwork(it, null) == "yttv" }) {
            val app = ctx.applicationContext
            Thread {
                val link = Prime.gtiFor(app, game)?.let { Prime.link(it) }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (link == null || !Prime.open(app, link)) openApp(app, "prime")
                }
            }.start()
            return
        }
        // 1) A channel YouTube TV carries (national first, then local) → YouTube TV on it.
        for (list in listOf(game.nationalNetworks, game.localNetworks)) {
            list.firstOrNull { appForNetwork(it, null) == "yttv" }?.let { return void(openChannel(ctx, normalize(it))) }
        }
        // 2) Streaming: only apps actually on the box, in the order ESPN lists them.
        val wanted = game.allNetworks.mapNotNull { appForNetwork(it, null) }.distinct()
        val espnFamily = wanted.filter { it in ESPN_FAMILY }
        if (espnFamily.isNotEmpty()) return openEspnGame(ctx, game, espnFamily)
        wanted.firstOrNull { isInstalled(ctx, it) }?.let { return void(openApp(ctx, it)) }
        // 3) League fallback (out-of-market NHL → ESPN), if that app is here.
        val fb = Config.FALLBACK_APP[game.league.key] ?: Config.FALLBACK_APP.getValue("default")
        if (fb in ESPN_FAMILY) return openEspnGame(ctx, game, listOf(fb))
        if (isInstalled(ctx, fb)) return void(openApp(ctx, fb))
        toast(ctx, "This game is on ${game.allNetworks.joinToString(", ")}, and none of those apps is on this TV")
    }

    /**
     * The network name to show on a game card: where pressing it actually takes you, using the
     * same order as [openGame] (so a card never says Disney+ and then opens ESPN).
     */
    fun cardLabel(ctx: Context, game: Game): String {
        if (Prime.isPrime(game) && game.nationalNetworks.none { appForNetwork(it, null) == "yttv" })
            return game.nationalNetworks.first { it.contains("Prime Video", true) }.replace(" (Local)", "")
        for (list in listOf(game.nationalNetworks, game.localNetworks))
            list.firstOrNull { appForNetwork(it, null) == "yttv" }?.let { return normalize(it) }
        val nets = game.allNetworks
        val wanted = nets.mapNotNull { appForNetwork(it, null) }.distinct()
        val fb = Config.FALLBACK_APP[game.league.key] ?: Config.FALLBACK_APP.getValue("default")
        if (wanted.any { it in ESPN_FAMILY } || (wanted.none { isInstalled(ctx, it) } && fb in ESPN_FAMILY)) {
            val family = wanted.filter { it in ESPN_FAMILY }
            // Same order as openEspnGame: the ESPN app first, then the listed apps, then the rest.
            val app = listOf("espn", "disney", "hulu").sortedBy { if (it == "espn") 0 else if (it in family) 1 else 2 }
                .firstOrNull { isInstalled(ctx, it) }
            return when (app) { "espn" -> "ESPN+"; "disney" -> "Disney+"; "hulu" -> "Hulu"; else -> game.networkLabel }
        }
        wanted.firstOrNull { isInstalled(ctx, it) }?.let { key -> return nets.first { appForNetwork(it, null) == key } }
        return game.networkLabel
    }

    /** Which app a web link belongs to (by its site). */
    private val LINK_HOSTS = listOf(
        "netflix.com" to "netflix", "peacocktv.com" to "peacock", "max.com" to "max", "hbomax.com" to "max",
        "tv.apple.com" to "appletv", "paramountplus.com" to "paramount", "youtube.com" to "youtube",
        "primevideo.com" to "prime", "espn.com" to "espn", "disneyplus.com" to "disney", "hulu.com" to "hulu",
    )

    /** Opens a web link in the app it belongs to; false if that app isn't here or won't take it. */
    fun openLink(ctx: Context, link: String): Boolean {
        val host = Uri.parse(link).host.orEmpty().lowercase()
        val key = LINK_HOSTS.firstOrNull { host == it.first || host.endsWith("." + it.first) }?.second ?: return false
        if (key == "prime") return Prime.open(ctx, link)
        return tryDeepLink(ctx, key, link)
    }

    /** ESPN+, Disney+ and Hulu all carry the same ESPN streams. */
    private val ESPN_FAMILY = setOf("espn", "disney", "hulu")

    fun isInstalled(ctx: Context, key: String) = appByKey(key)?.let { installedPackage(ctx, it) } != null

    /**
     * ESPN app straight into the game's stream (looked up from ESPN's watch API); if the app
     * won't take a link, the ESPN app itself; then Disney+, then Hulu (both have the ESPN tab).
     */
    private fun openEspnGame(ctx: Context, game: Game, family: List<String>) {
        val app = ctx.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            val airing = Espn.airingId(game.id)
            main.post {
                val espnPkg = appByKey("espn")?.let { installedPackage(app, it) }
                if (espnPkg != null && Espn.openFirst(app, espnPkg, Espn.links(game.id, airing))) return@post
                val order = listOf("espn", "disney", "hulu").sortedBy { if (it == "espn") 0 else if (it in family) 1 else 2 }
                order.firstOrNull { isInstalled(app, it) }?.let { openApp(app, it); return@post }
                toast(app, "This game is on ${game.allNetworks.joinToString(", ")}, and none of those apps is on this TV")
            }
        }.start()
    }

    /** Returns app key for a network name, or [fallback] if unknown. */
    fun appForNetwork(network: String, fallback: String?): String? {
        Config.NETWORK_TO_APP_EXACT.entries.firstOrNull { it.key.equals(network.trim(), true) }?.let { return it.value }
        Config.NETWORK_TO_APP.firstOrNull { network.contains(it.first, ignoreCase = true) }?.let {
            // "Prime Video (Local)" etc. still means Prime.
            return it.second
        }
        if (Config.YTTV_NETWORKS.any { it.equals(network, ignoreCase = true) }) return "yttv"
        return fallback
    }

    /** ESPN's network names → the names YouTube TV's guide uses. */
    private val CANON = mapOf(
        "FOX" to "FOX", "NBC" to "NBC", "CBS" to "CBS", "ABC" to "ABC",
        "NFL NET" to "NFL Network", "NFL NETWORK" to "NFL Network",
        "USA NET" to "USA Network", "NHL NET" to "NHL Network", "CBSSN" to "CBS Sports Network",
        "CW" to "The CW", "SECN" to "SEC Network", "ACCN" to "ACC Network", "BIG TEN NETWORK" to "BTN",
    )
    fun normalize(network: String) = CANON[network.trim().uppercase()] ?: network.trim()

    private fun tryDeepLink(ctx: Context, appKey: String, link: String): Boolean {
        val pkg = appByKey(appKey)?.let { installedPackage(ctx, it) } ?: return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
                .setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent); true
        } catch (e: Exception) {
            Log.w(TAG, "Deep link failed: $link", e); false
        }
    }

    private fun void(@Suppress("UNUSED_PARAMETER") b: Boolean) = Unit

    fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
}
