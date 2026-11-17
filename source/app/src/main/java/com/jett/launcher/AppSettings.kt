package com.jett.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the phone app can change, kept on the box. Starts from the defaults below (what
 * was set up by hand before the app existed); what you change in the app is stored on top.
 */
object AppSettings {
    private const val PREFS = "settings"
    /** Set once at start-up (JettApp), so code without a Context at hand can read settings. */
    lateinit var app: Context

    /** Nature videos offered for mornings (all checked: long, no music, no loop). */
    val SOUNDS = listOf(
        Triple("FxAgAyZYXJ8", "Forest birdsong", "Robins and blackbirds in a deep forest · 8 h"),
        Triple("sX6J4t2lRu0", "Tropical sunrise", "Calm waves on a tropical beach at dawn · 8 h"),
        Triple("F-Xs9s-q358", "Stream & birds", "A woodland brook with birds chirping · 8 h"),
        Triple("rV_ERKtNyNA", "Northwest birds", "Songbirds in Washington State woods · 8 h"),
        Triple("KIjSUAvm8EI", "Sunrise surf", "Sunrise over Kailua Beach, Oʻahu · 12 h"),
        Triple("sa2JCQ1Dils", "Forest waterfall", "Water over mossy rocks in a forest · 10 h"),
        Triple("Nmmsl2X_--U", "Morning chorus", "Birds singing in a sunlit forest · 8 h"),
        Triple("c17HoVALYno", "Caribbean dawn", "Gentle waves at sunrise, Mexico · 8 h"),
        Triple("GovZkJbxe7g", "Yosemite sunrise", "Golden light on the Merced River · 6 h"),
        Triple("qQgNWg96Rls", "Rainforest birds", "Colourful tropical birds, 4K · 11 h"),
        Triple("FrXPW3Ne2YM", "Ocean sunrise", "The sun coming up over calm waves · 8 h"),
        Triple("aVS7PHjSxdI", "Gentle birdsong", "Soft birdsong in a quiet wood · 8 h"),
        Triple("EHklxmBvzwc", "Singing forest", "Real-time birdsong, no loop, 4K HDR · 10 h"),
        Triple("9GpA9og6H-k", "Forest rain", "Steady rain under a forest canopy · 8 h"),
        Triple("AWKzr6n0ea0", "Fireplace", "Crackling logs, 4K · 8 h"),
    )

    /**
     * "Nature rotation" for mornings: a different one each day, in this order (birds, waves and
     * water take turns). Every one checked Nov 2026: real, plays on the TV, long, no music. 13 is
     * deliberate: a size that isn't a multiple of 7 means weekday-only mornings still reach all.
     */
    const val ROTATE = "youtube:rotate"
    val ROTATION = SOUNDS.map { it.first }.filter { it != "9GpA9og6H-k" && it != "AWKzr6n0ea0" }

    /** [content] for a given morning: the rotation becomes that day's video. */
    fun resolve(content: String, date: java.time.LocalDate = java.time.LocalDate.now(Config.ZONE)) =
        if (content == ROTATE) "youtube:" + ControlCore.rotationPick(ROTATION, date) else content

    /** One time: weekday mornings still on the original birdsong move to the rotation (Jett asked, Nov 16). */
    fun migrate(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getBoolean("rotation1", false)) return
        val week = wake().optJSONObject("week") ?: JSONObject()
        val patch = JSONObject()
        for (k in week.keys()) if (week.optJSONObject(k)?.optString("content") == "youtube:FxAgAyZYXJ8")
            patch.put(k, JSONObject().put("content", ROTATE))
        if (patch.length() > 0) update(ctx, JSONObject().put("wake", JSONObject().put("week", patch)))
        p.edit().putBoolean("rotation1", true).apply()
    }

    private fun day(on: Boolean) = JSONObject()
        .put("on", on).put("time", "08:00").put("content", ROTATE).put("volume", 15)
        .put("switchAt", "09:00").put("switchTo", "CNBC").put("switchVolume", 10).put("off", "11:00")

    fun defaults(): JSONObject = JSONObject()
        .put("wake", JSONObject().put("enabled", true)
            .put("week", JSONObject().apply { for (d in 1..7) put(d.toString(), day(d <= 5)) })
            .put("overrides", JSONObject()))
        .put("bar", JSONObject()
            .put("leagues", JSONObject().put("nfl", true).put("cfb", true).put("nhl", true).put("nba", true).put("mlb", true).put("ufc", true))
            .put("teams", JSONObject()
                .put("nfl", JSONArray(listOf("MIA"))).put("cfb", JSONArray(listOf("MICH", "TEX"))).put("nhl", JSONArray(listOf("FLA"))))
            .put("minutesBefore", Config.SHOW_BAR_MINUTES_BEFORE)
            .put("cfbTopN", Config.CFB_MAX_RANK).put("bigTen", true)
            .put("morningShows", true)
            .put("idleChannels", JSONArray(listOf("CNBC", "ESPN", "CNN")))
            .put("windows", JSONArray()))
        .put("alerts", JSONObject().put("on", true).put("topRank", Config.ALERT_TOP_RANK)
            .put("footballMargin", 8).put("hockeyMargin", 1))
        .put("auto", JSONObject().put("redzone", true).put("nightGames", true).put("playoffs", true).put("gameday", true))
        .put("backdrop", JSONObject()
            .put("rotateMin", Config.PHOTO_ROTATE_MINUTES.toInt()).put("idleSec", Config.IDLE_TO_PHOTOS_SECONDS.toInt())
            .put("shade", 40).put("captions", true).put("matchTime", true)
            .put("places", JSONArray()).put("hidden", JSONArray()).put("favorites", JSONArray()).put("favoritesOnly", false))
        .put("night", JSONObject().put("on", false).put("time", "00:30").put("days", "1-7"))
        .put("home", JSONObject().put("weather", true).put("appOrder", JSONArray()).put("hiddenApps", JSONArray())
            .put("sleepFrom", Config.BEDROOM_START_HOUR))

    val SECTIONS = setOf("wake", "bar", "alerts", "auto", "backdrop", "night", "home")

    @Volatile private var cache: JSONObject? = null
    /** Bumped on every change, so screens can tell when to re-read. */
    @Volatile var version = 0; private set

    @Synchronized fun all(ctx: Context): JSONObject {
        cache?.let { return it }
        val stored = try { JSONObject(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("json", "{}")!!) } catch (e: Exception) { JSONObject() }
        return ControlCore.merge(defaults(), stored).also { cache = it }
    }

    /** Applies a change from the app (objects merge; lists and values replace) and re-plans. */
    @Synchronized fun update(ctx: Context, patch: JSONObject): JSONObject {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = try { JSONObject(p.getString("json", "{}")!!) } catch (e: Exception) { JSONObject() }
        var next = ControlCore.merge(stored, patch)
        next.optJSONObject("wake")?.let { next.put("wake", ControlCore.pruneOverrides(it, java.time.LocalDate.now(Config.ZONE))) }
        p.edit().putString("json", next.toString()).apply()
        cache = null; version++
        val app = ctx.applicationContext
        try { WakeUp.schedule(app) } catch (e: Exception) { }
        return all(ctx)
    }

    // Shortcuts used around the app (defaults if called before start-up finished).
    fun section(name: String): JSONObject =
        (if (::app.isInitialized) all(app) else defaults()).optJSONObject(name) ?: JSONObject()
    fun bar() = section("bar")
    fun backdrop() = section("backdrop")
    fun home() = section("home")
    fun alerts() = section("alerts")
    fun auto() = section("auto")
    fun wake() = section("wake")

    fun teams(league: String): Set<String> =
        ControlCore.strings(bar().optJSONObject("teams")?.optJSONArray(league)).map { it.uppercase() }.toSet()
    fun leagueOn(league: String) = bar().optJSONObject("leagues")?.optBoolean(league, true) ?: true
    fun hiddenPhotos(): Set<String> = ControlCore.strings(backdrop().optJSONArray("hidden")).toSet()
    fun favoritePhotos(): Set<String> = ControlCore.strings(backdrop().optJSONArray("favorites")).toSet()
    fun autoOn(key: String) = auto().optBoolean(key, true)
}
