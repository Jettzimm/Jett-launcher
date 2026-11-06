package com.jett.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONObject

/**
 * Games streamed on ESPN+ / Disney+ / Hulu (most NHL nights, lots of college). ESPN's public
 * watch API gives the exact stream for a game, so the ESPN app can open straight into it.
 * Every link is only tried if the app on the box says it handles it; otherwise the next one.
 */
object Espn {
    private const val TAG = "JettEspn"
    private const val API = "https://watch.product.api.espn.com/api/product/v3/watchespn/web/event" +
        "?lang=en&countryCode=US&entitlements=ESPN_PLUS&tz=America/New_York&eventId="

    /** The stream ("airing") id for an ESPN game id, or null. Network: call off the main thread. */
    fun airingId(gameId: String): String? = try {
        val c = JSONObject(Sports.httpGet(API + gameId)).getJSONObject("page").getJSONObject("contents")
        val streams = c.optJSONArray("streams")
        // Prefer the live/primary stream; else the listing's own id.
        (0 until (streams?.length() ?: 0)).map { streams!!.getJSONObject(it) }
            .sortedBy { if (it.optString("status") == "live") 0 else 1 }
            .firstOrNull()?.optString("id")?.takeIf { it.isNotEmpty() }
            ?: c.optString("id").takeIf { it.contains('-') }
    } catch (e: Exception) { Log.w(TAG, "No ESPN stream for $gameId", e); null }

    /** Deep links into the ESPN app, best first. */
    fun links(gameId: String, airing: String?): List<String> = listOfNotNull(
        airing?.let { "sportscenter://x-callback-url/showWatchStream?playID=$it" },
        airing?.let { "https://www.espn.com/watch/player?id=$it" },
        "sportscenter://x-callback-url/showWatchStream?playGameID=$gameId",
        "https://www.espn.com/watch/player/_/eventId/$gameId",
    )

    /** First link the installed app actually handles; true if one opened. */
    fun openFirst(ctx: Context, pkg: String, links: List<String>): Boolean {
        for (l in links) {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(l)).setPackage(pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (ctx.packageManager.queryIntentActivities(i, 0).isEmpty()) continue
            try { ctx.startActivity(i); Log.i(TAG, "Opened $l"); return true }
            catch (e: Exception) { Log.w(TAG, "Link refused: $l", e) }
        }
        return false
    }
}
