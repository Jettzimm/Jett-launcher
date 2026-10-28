package com.jett.launcher

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Morning TV cards for the bar.
 *  Mon–Fri ............ CNBC, network morning show, ESPN
 *  Sat, college season  three college pregame shows
 *  Sun, NFL season .... three NFL pregame shows (early London-type game goes first)
 *  Weekends otherwise . regular morning TV
 * "Season" = there are games on today, checked live against ESPN, so bye weeks,
 * bowl season and the off-season all sort themselves out.
 */
object Morning {

    private val DATE = DateTimeFormatter.BASIC_ISO_DATE

    fun isOn(show: Config.Show, now: ZonedDateTime): Boolean {
        val t = now.toLocalTime()
        return !t.isBefore(LocalTime.parse(show.start)) && t.isBefore(LocalTime.parse(show.end))
    }

    /** Which mode applies right now. Network call on weekends — call off the main thread. */
    private var cachedDay: java.time.LocalDate? = null
    private var cachedMode: Config.MorningMode? = null

    /** Cached per day once the season check succeeds (one network call a day). */
    @Synchronized fun mode(now: ZonedDateTime): Config.MorningMode {
        val d = now.toLocalDate()
        cachedMode?.let { if (cachedDay == d) return it }
        val (m, sure) = computeMode(now)
        if (sure) { cachedDay = d; cachedMode = m }   // a guess (no connection) isn't cached
        return m
    }

    private fun computeMode(now: ZonedDateTime): Pair<Config.MorningMode, Boolean> {
        val guess = now.monthValue in 9..12 || now.monthValue == 1
        // College Saturdays (GameDay, Big Noon, SEC Nation): late Aug through conference
        // championship weekend (first Saturday of December). After that it's bowls, which those
        // shows don't cover.
        val collegeSeason = now.monthValue in 8..11 || (now.monthValue == 12 && now.dayOfMonth <= 7)
        return when (now.dayOfWeek) {
            DayOfWeek.SATURDAY -> if (!collegeSeason) Config.SATURDAY_MORNING to true
                else gamesToday("cfb", now).let { (if (it ?: guess) Config.COLLEGE_SATURDAY else Config.SATURDAY_MORNING) to (it != null) }
            DayOfWeek.SUNDAY -> gamesToday("nfl", now).let { (if (it ?: guess) Config.NFL_SUNDAY_MORNING else Config.SUNDAY_MORNING) to (it != null) }
            else -> Config.WEEKDAY_MORNING to true
        }
    }

    /** Up to [maxCards] shows on air right now, or empty outside the morning window. */
    fun picks(
        now: ZonedDateTime = ZonedDateTime.now(Config.ZONE), maxCards: Int = Config.MORNING_CARDS,
        skipChannels: Set<String> = emptySet(),   // channels already showing a live game (London on NFL Network)
    ): List<Config.Show> {
        if (maxCards <= 0) return emptyList()
        if (now.hour < 6 || now.hour >= 13) return emptyList()   // outside every mode: skip network
        val m = mode(now)
        if (now.hour < m.startHour || now.hour >= m.endHour) return emptyList()
        val out = ArrayList<Config.Show>()
        for (slot in m.slots) {
            val live = slot.firstOrNull { isOn(it, now) }
            // Nothing in this slot on yet (e.g. FOX's pregame at 10am): show the next one
            // with its start time, rather than an unrelated channel.
            val next = live ?: slot.filter { LocalTime.parse(it.start).isAfter(now.toLocalTime()) }
                .minByOrNull { LocalTime.parse(it.start) }?.copy(upcoming = true)
            next?.takeIf { s -> skipChannels.none { it.equals(s.channel, true) } }?.let { out += it }
        }
        for (f in Config.MORNING_FALLBACK) {
            if (out.size >= Config.MORNING_CARDS) break
            if (isOn(f, now) && out.none { it.channel == f.channel } && skipChannels.none { it.equals(f.channel, true) }) out += f
        }
        return out.take(maxCards)
    }

    /** true/false, or null if the check couldn't run (no connection). */
    private fun gamesToday(key: String, now: ZonedDateTime): Boolean? = try {
        val lg = Config.LEAGUES.first { it.key == key }
        Sports.fetchLeague(lg, DATE.format(now.toLocalDate()))
            .any { if (key == "nfl") Rules.counts(it) else it.seasonType == 2 || it.seasonType == 3 }   // no preseason / Pro Bowl
    } catch (e: Exception) { null }
}
