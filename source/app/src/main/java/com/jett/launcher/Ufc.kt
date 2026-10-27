package com.jett.launcher

import org.json.JSONObject
import java.time.Instant

/** A numbered UFC card (UFC 333 etc). Fight Nights are skipped. */
data class FightCard(
    val title: String,        // "UFC 333"
    val headline: String,     // "Volkanovski vs. Evloev"
    val state: String,        // "pre", "in", "post"
    val start: Instant,
    val network: String,      // "Paramount+", "CBS"
    val nowFighting: String?, // "Dvalishvili vs. Yan" while live
) {
    val isLive get() = state == "in"
}

object Ufc {
    private val NUMBERED = Regex("^UFC \\d+", RegexOption.IGNORE_CASE)

    /** Numbered card that's live or starts within the bar window, else null. */
    fun current(now: Instant = Instant.now()): FightCard? {
        // Yesterday–today (ET) so a card running past midnight still shows, and an old one doesn't.
        val today = now.atZone(Config.ZONE).toLocalDate()
        val f = java.time.format.DateTimeFormatter.BASIC_ISO_DATE
        val json = JSONObject(Sports.httpGet("https://site.api.espn.com/apis/site/v2/sports/mma/ufc/scoreboard?dates=${f.format(today.minusDays(1))}-${f.format(today)}"))
        val events = json.optJSONArray("events") ?: return null
        for (i in 0 until events.length()) {
            val ev = events.getJSONObject(i)
            val short = ev.optString("shortName")
            val name = ev.optString("name")
            if (!NUMBERED.containsMatchIn(short) && !NUMBERED.containsMatchIn(name)) continue

            val state = ev.optJSONObject("status")?.optJSONObject("type")?.optString("state") ?: "pre"
            val start = Instant.parse(ev.getString("date").let { if (it.length == 17) it.dropLast(1) + ":00Z" else it })
            val soon = now.plusSeconds(Config.SHOW_BAR_MINUTES_BEFORE * 60L)
            if (state == "post" || (state == "pre" && start.isAfter(soon))) continue

            val comps = ev.optJSONArray("competitions")
            var network = ""
            var live: String? = null
            var mainStart = start   // last bout's scheduled time ≈ main card start
            if (comps != null) for (c in 0 until comps.length()) {
                val comp = comps.getJSONObject(c)
                if (network.isEmpty()) network = comp.optString("broadcast")
                try {
                    val d = comp.optString("startDate").ifEmpty { comp.optString("date") }
                    if (d.isNotEmpty()) Instant.parse(if (d.length == 17) d.dropLast(1) + ":00Z" else d).let { if (it.isAfter(mainStart)) mainStart = it }
                } catch (_: Exception) {}
                if (comp.optJSONObject("status")?.optJSONObject("type")?.optString("state") == "in") {
                    val fs = comp.optJSONArray("competitors") ?: continue
                    live = (0 until fs.length()).joinToString(" vs. ") {
                        fs.getJSONObject(it).optJSONObject("athlete")?.optString("shortName").orEmpty()
                            .substringAfter(". ")
                    }
                }
            }
            return FightCard(
                title = NUMBERED.find(short)?.value ?: NUMBERED.find(name)?.value ?: short,
                headline = name.substringAfter(": ", name),
                state = state, start = mainStart,
                network = network.ifEmpty { "Paramount+" },
                nowFighting = live,
            )
        }
        return null
    }
}
