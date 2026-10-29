package com.jett.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Prime Video games (Thursday Night Football, Black Friday, Christmas, playoff games on Prime).
 * Prime's public search page lists each game as "<Away> vs. <Home>" with its Prime title id
 * (gti); the Prime app opens that game's page from https://app.primevideo.com/detail?gti=….
 * Looked up on the box itself, no login or weekly upkeep. If it can't be found, Prime just opens.
 */
object Prime {
    private const val TAG = "JettPrime"
    private const val PREFS = "prime"
    private val GTI = Regex("amzn1\\.dv\\.gti\\.[0-9a-f-]{36}")

    /** Prime title id for this game, cached per ESPN game id. Network: call off the main thread. */
    fun gtiFor(ctx: Context, g: Game): String? {
        if (g.awayName.isEmpty() || g.homeName.isEmpty()) return null
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString("gti_${g.id}", null)?.let { return it }
        val title = "${g.awayName} vs. ${g.homeName}"
        return try {
            val conn = URL("https://www.primevideo.com/search/?phrase=" + URLEncoder.encode(title, "UTF-8"))
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 6000; conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Safari/537.36")
            val html = try { conn.inputStream.bufferedReader().use { it.readText() } } finally { conn.disconnect() }
            // The result tile labelled exactly "<Away> vs. <Home>", then the first title id after it.
            val at = html.indexOf("aria-label=\"$title\"")
            if (at < 0) return null
            val gti = GTI.find(html.substring(at, minOf(html.length, at + 4000)))?.value ?: return null
            prefs.edit().putString("gti_${g.id}", gti).apply()
            gti
        } catch (e: Exception) { Log.w(TAG, "Lookup failed for $title", e); null }
    }

    fun link(gti: String) = "https://app.primevideo.com/detail?gti=$gti"

    /** Opens a Prime game page; false if the Prime app isn't there or refuses the link. */
    fun open(ctx: Context, link: String): Boolean {
        val pkg = Router.appByKey("prime")?.let { Router.installedPackage(ctx, it) } ?: return false
        return try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)); true
        } catch (e: Exception) { Log.w(TAG, "Prime link failed", e); false }
    }

    fun isPrime(g: Game) = g.nationalNetworks.any { it.contains("Prime Video", true) }
}
