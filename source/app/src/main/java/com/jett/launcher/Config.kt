package com.jett.launcher

import java.time.DayOfWeek

/**
 * EVERYTHING YOU'D WANT TO TWEAK LIVES IN THIS FILE.
 * Change a value, rebuild, reinstall. Nothing else needs touching for normal changes.
 */
object Config {

    const val NAME = "Jett"

    // Every time in this file (shows, rules, sunrise) is Eastern, regardless of the box's setting.
    val ZONE: java.time.ZoneId = java.time.ZoneId.of("America/New_York")

    // New York City — used to work out sunrise/sunset for the background.
    const val LAT = 40.7128
    const val LON = -74.0060

    // ---------------------------------------------------------------- APP ROW
    // Shown left to right in this order. Each app lists the package names it can
    // ship under (apps sometimes change them); the first one installed is used.
    // Apps that aren't installed are skipped automatically.
    data class App(val key: String, val label: String, val packages: List<String>)

    val APPS = listOf(
        App("yttv", "YouTube TV", listOf("com.google.android.youtube.tvunplugged")),
        App("appletv", "Apple TV", listOf("com.apple.atve.androidtv.appletv")),
        App("netflix", "Netflix", listOf("com.netflix.ninja")),
        App("espn", "ESPN", listOf("com.espn.gtv", "com.espn.score_center")),
        App("hulu", "Hulu", listOf("com.hulu.livingroomplus")),
        App("max", "HBO Max", listOf("com.wbd.stream", "com.hbo.hbonow")),
        App("prime", "Prime Video", listOf("com.amazon.amazonvideo.livingroom")),
        App("disney", "Disney+", listOf("com.disney.disneyplus")),
        App("youtube", "YouTube", listOf("com.google.android.youtube.tv")),
        App("peacock", "Peacock", listOf("com.peacocktv.peacockandroid")),
        App("paramount", "Paramount+", listOf("com.cbs.ott")),
        App("spotify", "Spotify", listOf("com.spotify.tv.android")),
    )

    // ------------------------------------------------------------ SPORTS BAR
    // Bar only appears when a game below is live or starts within this many minutes.
    const val SHOW_BAR_MINUTES_BEFORE = 15
    const val MAX_GAMES_IN_BAR = 16

    // Pinned teams always sort to the front (ESPN abbreviations).
    val PINNED = mapOf(
        "nfl" to setOf("MIA"),            // Dolphins
        "cfb" to setOf("MICH", "TEX"),    // Michigan, Texas
        "nhl" to setOf("FLA"),            // Panthers
    )

    data class League(
        val key: String, val label: String, val path: String,
        val playoffsOnly: Boolean = false,
        val extraQuery: String = "",
    )

    val LEAGUES = listOf(
        League("nfl", "NFL", "football/nfl"),
        // groups=80 = all of FBS; we filter down to Top 25 / Big Ten / pinned below.
        League("cfb", "NCAAF", "football/college-football", extraQuery = "groups=80&limit=300"),
        League("nhl", "NHL", "hockey/nhl"),
        League("nba", "NBA", "basketball/nba", playoffsOnly = true),
        League("mlb", "MLB", "baseball/mlb", playoffsOnly = true),
    )

    // College games are shown if: a pinned team plays, either team is ranked
    // in the Top 25, or it's a game involving a Big Ten team (ESPN group 5).
    const val CFB_MAX_RANK = 25
    val CFB_CONFERENCES = setOf("5")

    // -------------------------------------------------------------- ROUTING
    // Which app a TV network opens in. YouTube TV wins whenever it carries the channel.
    val YTTV_NETWORKS = setOf(
        "ABC", "CBS", "NBC", "FOX", "ESPN", "ESPN2", "ESPNU", "ESPNEWS", "FS1", "FS2",
        "BTN", "Big Ten Network", "SECN", "SEC Network", "ACCN", "ACC Network",
        "NFL Network", "NFL NET", "NFL RedZone", "CBSSN", "CBS Sports Network", "TNT", "TBS", "truTV",
        "NHL Net", "NHL Network", "SEC Network", "MLB Network", "NBA TV", "USA Net", "USA Network",
        "CW", "The CW", "CNBC", "Fox News", "FNC", "MSNBC", "CNN",
    )

    // Anything containing these words goes to that app instead.
    val NETWORK_TO_APP = listOf(
        "Prime Video" to "prime",
        "ESPN+" to "espn",
        "ESPN Unlmtd" to "espn",
        "Peacock" to "peacock",
        "Netflix" to "netflix",
        "Apple TV" to "appletv",
        "Paramount+" to "paramount",
        "Max" to "max",
        "Disney+" to "disney",
        "SECN+" to "espn",
        "ACCNX" to "espn",
    )
    // Exact-name matches (so "YouTube" doesn't catch "YouTube TV").
    val NETWORK_TO_APP_EXACT = mapOf("YouTube" to "youtube")

    // When nothing above matches (e.g. an out-of-market Panthers game on a
    // regional channel), open this app — out-of-market NHL streams on ESPN+.
    val FALLBACK_APP = mapOf("nhl" to "espn", "default" to "yttv")

    // ------------------------------------------------------ YOUTUBE TV LINKS
    // YouTube TV opens a live channel from https://tv.youtube.com/watch/<code>.
    // YouTube rotates these codes every month or few, so the live list comes from the
    // update feed (channels.json) and is refreshed automatically. Codes here are only a
    // fallback. Find a code: tv.youtube.com on a computer → Live → click the channel →
    // copy the part after /watch/ in the address bar.
    // A missing or empty code just opens YouTube TV's home screen.
    // "web" = https://tv.youtube.com/watch/<code> (tested on this box: tunes the channel).
    // "app" = watch/<code> to YouTube TV's main screen (on this box it only opens the app home).
    const val YTTV_LINK_STYLE = "web"

    // Built-in copy of the codes read from Jett's YouTube TV guide (Oct 29, 2026, NYC lineup);
    // the feed's list overrides these when YouTube changes one.
    val YTTV_CHANNEL_CODES = mapOf(
        "CBS" to "QgitbnthKJI", "NBC" to "fIbKmfCLA9o", "FOX" to "qUyOvSNqmEQ",
        "ABC" to "ADhENjHS-8c", "The CW" to "ZGwdaZkS6EA", "CW" to "ZGwdaZkS6EA",
        "TBS" to "hna7JJwFnL0", "TNT" to "kAmuQECdZWY", "truTV" to "LH84hSCuuBQ",
        "ESPN" to "bj3v-DQPnNs", "ESPN2" to "nIaQPNyNo34", "ESPNU" to "p0TOraK6Img",
        "ESPNEWS" to "hbYvuCKXj0Y", "SEC Network" to "iuDz6UdSjMo", "SECN" to "iuDz6UdSjMo",
        "ACC Network" to "VasWEa--oME", "ACCN" to "VasWEa--oME", "FS1" to "PIJjfw43Rao",
        "FS2" to "lc06tgwYNZs", "NBA TV" to "F3wVB3UuZGo", "NFL Network" to "WPuH0gnV73o",
        "NFL NET" to "WPuH0gnV73o", "BTN" to "tUwIbNAQ6yU", "Big Ten Network" to "tUwIbNAQ6yU",
        "CBS Sports Network" to "fGRTFzKDNAc", "CBSSN" to "fGRTFzKDNAc",
        "Golf Channel" to "f4VYXRthuQI", "USA Network" to "Qi_C4zyXBIg", "USA" to "Qi_C4zyXBIg",
        "USA Net" to "Qi_C4zyXBIg", "CNBC" to "I-7YWn-wtFA", "CNN" to "AW9-z6zluec",
        "Fox News" to "q5TBgD8fyG8", "FNC" to "q5TBgD8fyG8", "FOX Business" to "8lamVOKuXtQ",
        "MSNBC" to "VlWoYVR-sj0", "MS NOW" to "VlWoYVR-sj0", "Telemundo" to "VZ_O4CWiKNY",
        "Univision" to "K0EUH8k7JZM", "ABC News Live" to "bm_DB8-y0sU",
        "NBC News NOW" to "ApPDaqv6EvE", "The Weather Channel" to "AERMXq8CBCQ",
        "NFL RedZone" to "JCZl8GHvkko", "RedZone" to "JCZl8GHvkko",
    )

    // ------------------------------------------------------------- UPDATES
    // The box checks this feed daily at 4am (and on boot) for a new version and fresh
    // channel codes, and installs updates by itself. Empty = updates off.
    var UPDATE_FEED_URL = "https://raw.githubusercontent.com/Jettzimm/jett-launcher/main/feed.json"
    const val UPDATE_CHECK_HOUR = 4

    // --------------------------------------------------------- BEDROOM MODE
    // From this hour until 5am a moon tile appears in the app row: press it to cycle a
    // sleep timer (30 → 60 → 90 min → off). When it runs out the box sleeps and the
    // Samsung turns off with it.
    const val BEDROOM_START_HOUR = 22
    val SLEEP_STEPS_MIN = listOf(30, 60, 90)

    // ------------------------------------------------------------ BACKGROUND
    // Photos live in app/src/main/assets/bg/<dawn|morning|midday|golden|dusk|night>/.
    // Add or delete .jpg files there to change what shows. The set follows the real sun.
    const val PHOTO_ROTATE_MINUTES = 2L
    // Home screen untouched this long → full-screen photos (like Apple TV's screensaver).
    const val IDLE_TO_PHOTOS_SECONDS = 120L
    const val PHOTO_FADE_MS = 2500L

    // ---------------------------------------------------------------- LAYOUT
    // Overall size of everything on the home screen (1.0 = Android TV default; smaller = more
    // zoomed out). 0.8 = 20% smaller text, tiles and cards.
    const val UI_SCALE = 0.8f
    // Close-game alerts also cover college games with a team ranked this high or better.
    const val ALERT_TOP_RANK = 10
    // Apps per row in the Apple-TV-style grid (first row on the main page, scroll down for more).
    const val APPS_PER_ROW = 5

    // --------------------------------------------------------- MORNING SHOWS
    // Mornings, the bar leads with three live TV cards. Each "slot" is one card: it shows
    // the first show in its list that's on air right now. Empty slots are filled from the
    // fallback list. Times are the usual ET broadcast schedule, not a live guide, so
    // edit them here if a show moves.
    data class Show(
        val title: String, val channel: String, val start: String, val end: String,
        val upcoming: Boolean = false,   // not on yet: card shows its start time instead of LIVE
    )

    data class MorningMode(val startHour: Int, val endHour: Int, val slots: List<List<Show>>)

    // Mon–Fri: CNBC, a network morning show, ESPN.
    val WEEKDAY_MORNING = MorningMode(6, 11, listOf(
        listOf(Show("Squawk Box", "CNBC", "06:00", "09:00"), Show("Squawk on the Street", "CNBC", "09:00", "11:00")),
        listOf(Show("Good Morning America", "ABC", "07:00", "09:00"), Show("TODAY", "NBC", "07:00", "11:00"),
               Show("CBS Mornings", "CBS", "07:00", "09:00")),
        listOf(Show("SportsCenter", "ESPN", "06:00", "08:00"), Show("Get Up", "ESPN", "08:00", "10:00"),
               Show("First Take", "ESPN", "10:00", "12:00")),
    ))

    // Saturday when college games are on today: three college pregame shows.
    val COLLEGE_SATURDAY = MorningMode(8, 12, listOf(
        listOf(Show("College GameDay", "ESPN", "09:00", "12:00")),
        listOf(Show("Big Noon Kickoff", "FOX", "10:00", "12:00")),
        listOf(Show("SEC Nation", "SEC Network", "10:00", "12:00")),
    ))

    // Sunday when NFL games are on today: three NFL pregame shows. An early game
    // (London, Germany, etc.) takes the first card and pushes one show off.
    val NFL_SUNDAY_MORNING = MorningMode(8, 13, listOf(
        listOf(Show("NFL GameDay Morning", "NFL Network", "09:00", "13:00")),
        listOf(Show("Sunday NFL Countdown", "ESPN", "10:00", "13:00")),
        listOf(Show("FOX NFL Kickoff", "FOX", "11:00", "12:00"), Show("FOX NFL Sunday", "FOX", "12:00", "13:00"),
               Show("The NFL Today", "CBS", "12:00", "13:00")),
    ))
    const val EARLY_NFL_BEFORE_HOUR = 12

    // Weekends outside football season: regular morning TV.
    val SATURDAY_MORNING = MorningMode(7, 11, listOf(
        listOf(Show("GMA Weekend", "ABC", "07:00", "09:00"), Show("Weekend TODAY", "NBC", "07:00", "09:00"),
               Show("CBS Saturday Morning", "CBS", "07:00", "09:00")),
        listOf(Show("SportsCenter", "ESPN", "07:00", "12:00")),
        listOf(Show("CNBC", "CNBC", "07:00", "11:00")),
    ))
    val SUNDAY_MORNING = MorningMode(7, 11, listOf(
        listOf(Show("GMA Weekend", "ABC", "08:00", "09:00"), Show("Sunday TODAY", "NBC", "08:00", "09:00"),
               Show("CBS Sunday Morning", "CBS", "09:00", "10:30")),
        listOf(Show("SportsCenter", "ESPN", "07:00", "12:00")),
        listOf(Show("CNBC", "CNBC", "07:00", "11:00")),
    ))

    // Used to fill any empty card in any mode.
    val MORNING_FALLBACK = listOf(
        Show("SportsCenter", "ESPN", "06:00", "13:00"),
        Show("CNBC", "CNBC", "06:00", "13:00"),
    )
    const val MORNING_CARDS = 3

    // When nothing is live and no morning shows are on, the bar shows these instead.
    val ALWAYS_ON = listOf(
        Show("Live now", "CNBC", "00:00", "23:59"),
        Show("Live now", "ESPN", "00:00", "23:59"),
        Show("Live now", "CNN", "00:00", "23:59"),
    )

    // ---------------------------------------------------------------- RULES
    const val COUNTDOWN_SECONDS = 10

    // Opens when the TV is turned on inside the window.
    data class PowerOnRule(
        val id: String, val day: DayOfWeek, val startHour: Int, val endHour: Int,
        val title: String, val channel: String,
    )

    val POWER_ON_RULES = listOf(
        PowerOnRule("gameday", DayOfWeek.SATURDAY, 9, 12, "College GameDay", "ESPN"),
    )

    // NFL auto-switch (switches even if you're inside another app, with the 10-second countdown):
    // - Sundays: RedZone from 1pm while the afternoon games are on, then the night game's channel.
    // - Any night game (Thursday, Sunday, Monday, and late-season Saturdays/holidays): its channel
    //   shortly before kickoff, or straight away if you turn the TV on during it.
    // - Every playoff game, any day, including the Super Bowl.
    val NFL_SUNDAY = DayOfWeek.SUNDAY
    const val REDZONE_START_HOUR = 13
    const val REDZONE_CHANNEL = "NFL RedZone"
    // A game kicking off at or after this hour (Eastern) counts as a night game.
    const val NIGHT_GAME_HOUR = 18
    // How early before kickoff to switch: night games / playoff games.
    const val NIGHT_SWITCH_MINUTES_BEFORE = 15L
    const val PLAYOFF_SWITCH_MINUTES_BEFORE = 30L
    // Daily check time (Eastern) for whether today has any of the above.
    const val NFL_DAILY_CHECK_HOUR = 12
}
