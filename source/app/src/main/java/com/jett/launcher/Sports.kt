package com.jett.launcher

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class Game(
    val league: Config.League,
    val id: String,
    val awayAbbr: String, val homeAbbr: String,
    val awayScore: String, val homeScore: String,
    val awayLogo: String?, val homeLogo: String?,
    val awayRank: Int?, val homeRank: Int?,
    val state: String,          // "pre", "in", "post"
    val detail: String,         // "9:04 - 4th", "Final", ...
    val start: Instant,
    val nationalNetworks: List<String>,
    val localNetworks: List<String>,
    val pinned: Boolean,
    val seasonType: Int = 2,    // 2 regular season, 3 postseason (ESPN)
    val awayName: String = "", val homeName: String = "",   // "Panthers", "Packers"
) {
    val isLive get() = state == "in"
    val allNetworks get() = nationalNetworks + localNetworks
    val networkLabel get() = (nationalNetworks.firstOrNull() ?: localNetworks.firstOrNull()).orEmpty()

    fun statusText(zone: ZoneId): String = when (state) {
        "pre" -> TIME_FMT.format(start.atZone(zone))
        else -> detail
    }

    companion object {
        private val TIME_FMT = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.US)
    }
}

object Sports {

    private const val BASE = "https://site.api.espn.com/apis/site/v2/sports/"

    /** Raw fetch of one league's scoreboard (today by default, or yyyyMMdd). */
    fun fetchLeague(league: Config.League, date: String? = null): List<Game> {
        val q = buildList {
            if (league.extraQuery.isNotEmpty()) add(league.extraQuery)
            if (date != null) add("dates=$date")
        }.joinToString("&")
        val json = JSONObject(httpGet(BASE + league.path + "/scoreboard" + if (q.isEmpty()) "" else "?$q"))

        if (league.playoffsOnly) {
            val type = json.optJSONObject("season")?.optInt("type") ?: 0
            if (type != 3) return emptyList()   // 3 = postseason on ESPN
        }

        val pinnedSet = Config.PINNED[league.key].orEmpty()
        val events = json.optJSONArray("events") ?: return emptyList()
        val out = ArrayList<Game>()
        for (i in 0 until events.length()) try {
            val ev = events.getJSONObject(i)
            val comp = ev.getJSONArray("competitions").getJSONObject(0)
            val competitors = comp.getJSONArray("competitors")
            var home: JSONObject? = null
            var away: JSONObject? = null
            for (j in 0 until competitors.length()) {
                val c = competitors.getJSONObject(j)
                if (c.optString("homeAway") == "home") home = c else away = c
            }
            if (home == null || away == null) continue

            fun abbr(c: JSONObject) = c.getJSONObject("team").optString("abbreviation")
            fun logo(c: JSONObject) = c.getJSONObject("team").optString("logo").ifEmpty { null }
            fun rank(c: JSONObject): Int? =
                c.optJSONObject("curatedRank")?.optInt("current")?.takeIf { it in 1..25 }
            fun conf(c: JSONObject) = c.getJSONObject("team").optString("conferenceId")

            if (league.key == "cfb") {
                val keep = abbr(home) in pinnedSet || abbr(away) in pinnedSet ||
                    (rank(home) ?: 99) <= Config.CFB_MAX_RANK ||
                    (rank(away) ?: 99) <= Config.CFB_MAX_RANK ||
                    conf(home) in Config.CFB_CONFERENCES || conf(away) in Config.CFB_CONFERENCES
                if (!keep) continue
            }

            val national = ArrayList<String>()
            val local = ArrayList<String>()
            comp.optJSONArray("broadcasts")?.let { arr ->
                for (k in 0 until arr.length()) {
                    val b = arr.getJSONObject(k)
                    val names = b.optJSONArray("names") ?: continue
                    val target = if (b.optString("market") == "national") national else local
                    for (n in 0 until names.length()) target.add(names.getString(n))
                }
            }

            val status = ev.getJSONObject("status").getJSONObject("type")
            out += Game(
                league = league,
                id = ev.optString("id"),
                awayAbbr = abbr(away), homeAbbr = abbr(home),
                awayScore = away.optString("score"), homeScore = home.optString("score"),
                awayLogo = logo(away), homeLogo = logo(home),
                awayRank = rank(away), homeRank = rank(home),
                state = status.optString("state"),
                detail = status.optString("shortDetail"),
                start = Instant.parse(ev.getString("date").let { if (it.length == 17) it.dropLast(1) + ":00Z" else it }),
                nationalNetworks = national, localNetworks = local,
                pinned = abbr(home) in pinnedSet || abbr(away) in pinnedSet,
                seasonType = ev.optJSONObject("season")?.optInt("type") ?: 2,
                awayName = away.optJSONObject("team")?.optString("shortDisplayName").orEmpty(),
                homeName = home.optJSONObject("team")?.optString("shortDisplayName").orEmpty(),
            )
        } catch (e: Exception) {
            // One malformed event (TBD/postponed) shouldn't drop the whole league.
        }
        return out
    }

    /** Games worth showing in the bar right now, best first. Empty = hide the bar. */
    fun barGames(now: Instant = Instant.now()): List<Game> {
        val all = Config.LEAGUES.flatMap { lg ->
            try { fetchLeague(lg) } catch (e: Exception) { emptyList() }
        }
        val soon = now.plusSeconds(Config.SHOW_BAR_MINUTES_BEFORE * 60L)
        val relevant = all.filter { it.seasonType != 1 }   // no preseason
            .filter { it.isLive || (it.state == "pre" && it.start.isBefore(soon) && it.start.isAfter(now.minusSeconds(3600))) }
        if (relevant.isEmpty()) return emptyList()

        val leagueOrder = Config.LEAGUES.map { it.key }
        return relevant.sortedWith(
            compareByDescending<Game> { it.pinned }
                .thenByDescending { it.isLive }
                .thenBy { leagueOrder.indexOf(it.league.key) }
                .thenBy { it.start }
        ).take(Config.MAX_GAMES_IN_BAR)
    }

    fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "JettLauncher/1.0")
        return try { conn.inputStream.bufferedReader().use { it.readText() } } finally { conn.disconnect() }
    }
}
