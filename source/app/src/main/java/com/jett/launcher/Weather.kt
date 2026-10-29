package com.jett.launcher

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Current NYC temperature + the next rain/snow in the coming hours, from the National Weather Service (free, no key). */
object Weather {
    data class Now(val tempF: Int, val icon: String, val heads: String?)

    @Volatile private var cached: Now? = null
    @Volatile private var fetchedAt = 0L

    /** Network: call off the main thread. Cached for 20 minutes. */
    fun get(ctx: Context): Now? {
        if (System.currentTimeMillis() - fetchedAt < 20 * 60_000L) return cached
        return try {
            val prefs = ctx.getSharedPreferences("weather", Context.MODE_PRIVATE)
            val hourlyUrl = prefs.getString("url", null) ?: run {
                val p = JSONObject(get("https://api.weather.gov/points/${Config.LAT},${Config.LON}"))
                p.getJSONObject("properties").getString("forecastHourly").also { prefs.edit().putString("url", it).apply() }
            }
            val periods = JSONObject(get(hourlyUrl)).getJSONObject("properties").getJSONArray("periods")
            val first = periods.getJSONObject(0)
            val heads = (1 until minOf(periods.length(), 13)).asSequence().map { periods.getJSONObject(it) }.firstOrNull {
                (it.optJSONObject("probabilityOfPrecipitation")?.optInt("value") ?: 0) >= 50
            }?.let { p ->
                val f = p.optString("shortForecast").lowercase(Locale.US)
                val what = when { "snow" in f -> "Snow"; "thunder" in f -> "Storms"; else -> "Rain" }
                val t = OffsetDateTime.parse(p.getString("startTime")).atZoneSameInstant(Config.ZONE)
                "$what around ${DateTimeFormatter.ofPattern("h a", Locale.US).format(t).lowercase(Locale.US).replace(" ", "")}"
            }
            Now(first.getInt("temperature"), icon(first.optString("shortForecast"), first.optBoolean("isDaytime", true)), heads)
                .also { cached = it; fetchedAt = System.currentTimeMillis() }
        } catch (e: Exception) {
            // Gridpoint URLs occasionally change: re-resolve next time.
            ctx.getSharedPreferences("weather", Context.MODE_PRIVATE).edit().remove("url").apply()
            cached
        }
    }

    private fun icon(forecast: String, day: Boolean): String {
        val f = forecast.lowercase(Locale.US)
        return when {
            "thunder" in f -> "⛈"
            "snow" in f || "flurr" in f || "sleet" in f -> "❄"
            "rain" in f || "shower" in f || "drizzle" in f -> "☂"
            "fog" in f || "haze" in f -> "≋"
            "partly" in f || "mostly sunny" in f || "mostly clear" in f -> if (day) "⛅" else "☾"
            "cloud" in f || "overcast" in f -> "☁"
            else -> if (day) "☀" else "☾"
        }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "JettLauncher/1.0 (home TV)")
        c.setRequestProperty("Accept", "application/geo+json")
        return try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }
}
