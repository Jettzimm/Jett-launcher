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
        // 1) A national network YouTube TV carries → YouTube TV on that channel.
        // 2) A streaming-only national broadcast (Prime, Peacock, ESPN+) → that app.
        // 3) Local/regional networks the same way. 4) League fallback (NHL → ESPN+).
        for (list in listOf(game.nationalNetworks, game.localNetworks)) {
            list.firstOrNull { appForNetwork(it, null) == "yttv" }?.let { return void(openChannel(ctx, normalize(it))) }
            list.firstNotNullOfOrNull { appForNetwork(it, null) }?.let { return void(openApp(ctx, it)) }
        }
        openApp(ctx, Config.FALLBACK_APP[game.league.key] ?: Config.FALLBACK_APP.getValue("default"))
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

    private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}
