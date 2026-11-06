package com.jett.launcher

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The parts of the phone control app that don't touch Android (so they're unit-tested on a
 * computer): settings merging, the wake-up plan, the sports-bar time windows, and a small
 * HTTP/1.1 reader/writer for the box's local web server.
 */
object ControlCore {

    // ------------------------------------------------------------------ settings

    /** [patch] merged into a copy of [base]: objects merge key by key, anything else replaces. */
    fun merge(base: JSONObject, patch: JSONObject): JSONObject {
        val out = JSONObject(base.toString())
        for (k in patch.keys()) {
            val pv = patch.get(k)
            val bv = out.opt(k)
            if (pv == JSONObject.NULL) out.remove(k)
            else if (pv is JSONObject && bv is JSONObject) out.put(k, merge(bv, pv))
            else out.put(k, pv)
        }
        return out
    }

    // ------------------------------------------------------------------ wake-ups

    /** One morning: turn on with [content] at [volume]; maybe switch later; maybe turn off. */
    data class Morning(
        val on: Boolean,
        val time: LocalTime,
        val content: String,          // "CNBC", or "youtube:<video id>", or "home"
        val volume: Int,              // -1 = leave as is
        val switchAt: LocalTime?, val switchTo: String, val switchVolume: Int,
        val off: LocalTime?,
    )

    private fun time(s: String?): LocalTime? = try { s?.takeIf { it.isNotBlank() }?.let { LocalTime.parse(it) } } catch (e: Exception) { null }

    fun morning(o: JSONObject?): Morning? {
        o ?: return null
        val on = o.optBoolean("on", false)
        val t = time(o.optString("time")) ?: return if (on) null else Morning(false, LocalTime.of(8, 0), "", -1, null, "", -1, null)
        // A switch or turn-off set earlier than the turn-on is ignored (it would fire before it).
        val sw = time(o.optString("switchAt")).takeIf { o.optString("switchTo").isNotBlank() && it?.isAfter(t) == true }
        return Morning(
            on = on, time = t, content = o.optString("content").ifBlank { "home" },
            volume = o.optInt("volume", -1),
            switchAt = sw, switchTo = if (sw != null) o.optString("switchTo") else "",
            switchVolume = if (sw != null) o.optInt("switchVolume", -1) else -1,
            off = time(o.optString("off"))?.takeIf { it.isAfter(t) },
        )
    }

    /**
     * What a given date looks like: a one-off for that date ("custom day" / "skip") wins over
     * that weekday's usual plan. Null = nothing that day.
     */
    fun planFor(wake: JSONObject, date: LocalDate): Morning? {
        if (!wake.optBoolean("enabled", true)) return null
        val one = wake.optJSONObject("overrides")?.optJSONObject(date.toString())
        val m = if (one != null) morning(one) else morning(wake.optJSONObject("week")?.optJSONObject(date.dayOfWeek.value.toString()))
        return m?.takeIf { it.on }
    }

    enum class Step { ON, SWITCH, OFF }
    data class Event(val step: Step, val at: ZonedDateTime, val date: LocalDate, val m: Morning)

    /**
     * The next ON, SWITCH and OFF moments after [now] (each may be null). [after] is how far
     * past [now] to look from: 0 normally; a minute when re-planning from an alarm that just
     * fired, so it isn't armed again.
     */
    fun nextEvents(wake: JSONObject, now: ZonedDateTime, zone: ZoneId, afterSeconds: Long = 0): Map<Step, Event> {
        val out = HashMap<Step, Event>()
        for (i in 0..8) {
            val d = now.toLocalDate().plusDays(i.toLong())
            val m = planFor(wake, d) ?: continue
            fun at(t: LocalTime) = d.atTime(t).atZone(zone)
            val cand = listOfNotNull(
                Event(Step.ON, at(m.time), d, m),
                m.switchAt?.let { Event(Step.SWITCH, at(it), d, m) },
                m.off?.let { Event(Step.OFF, at(it), d, m) },
            )
            for (e in cand) if (e.at.isAfter(now.plusSeconds(afterSeconds)) && out[e.step] == null) out[e.step] = e
            if (out.size == 3) break
        }
        return out
    }

    /**
     * Bedtime: the next moment the TV should turn itself off. Days are evenings ("5" = Friday
     * night): a time before noon falls the next morning (Friday 00:30 means Saturday 00:30).
     */
    fun nextNightly(night: JSONObject?, now: ZonedDateTime, zone: ZoneId, afterSeconds: Long = 0): ZonedDateTime? {
        if (night == null || !night.optBoolean("on", false)) return null
        val t = time(night.optString("time")) ?: return null
        val d = days(night.optString("days", "1-7"))
        for (i in -1..8) {
            val evening = now.toLocalDate().plusDays(i.toLong())
            if (evening.dayOfWeek !in d) continue
            val at = (if (t.hour < 12) evening.plusDays(1) else evening).atTime(t).atZone(zone)
            if (at.isAfter(now.plusSeconds(afterSeconds))) return at
        }
        return null
    }

    /** Drops one-off days that are already over (so the list doesn't grow forever). */
    fun pruneOverrides(wake: JSONObject, today: LocalDate): JSONObject {
        val ov = wake.optJSONObject("overrides") ?: return wake
        val keep = JSONObject()
        for (k in ov.keys()) {
            val d = try { LocalDate.parse(k) } catch (e: Exception) { null } ?: continue
            if (!d.isBefore(today)) keep.put(k, ov.get(k))
        }
        return JSONObject(wake.toString()).put("overrides", keep)
    }

    // ------------------------------------------------------------------ sports bar windows

    /** Days spec "1-5", "6,7", "1-7" (1 = Monday). */
    fun days(spec: String): Set<DayOfWeek> = spec.split(',').flatMap { part ->
        val p = part.trim()
        try {
            if ('-' in p) { val (a, b) = p.split('-').map { it.trim().toInt() }; (a..b).toList() } else listOf(p.toInt())
        } catch (e: Exception) { emptyList() }
    }.filter { it in 1..7 }.map { DayOfWeek.of(it) }.toSet()

    /** Channels for the first custom bar window covering [now], or null if none does. */
    fun barWindow(bar: JSONObject, now: ZonedDateTime): JSONObject? {
        val arr = bar.optJSONArray("windows") ?: return null
        val t = now.toLocalTime()
        for (i in 0 until arr.length()) {
            val w = arr.optJSONObject(i) ?: continue
            if (!w.optBoolean("on", true)) continue
            val s = time(w.optString("start")) ?: continue
            val e = time(w.optString("end")) ?: continue
            val d = days(w.optString("days", "1-7"))
            val inside = if (s < e) now.dayOfWeek in d && !t.isBefore(s) && t.isBefore(e)
                // Overnight (22:00–02:00): the evening part counts for that day, the after-midnight
                // part for the day before (Friday's slot still runs at 1am Saturday).
                else (now.dayOfWeek in d && !t.isBefore(s)) || (now.minusDays(1).dayOfWeek in d && t.isBefore(e))
            if (inside) return w
        }
        return null
    }

    fun strings(a: JSONArray?): List<String> = a?.let { (0 until it.length()).mapNotNull { i -> it.optString(i).takeIf { s -> s.isNotBlank() } } }.orEmpty()

    // ------------------------------------------------------------------ HTTP

    /** True if JSON text nests deeper than [max] (refused before parsing: deep nesting can crash a parser). */
    fun tooDeep(json: String, max: Int = 32): Boolean {
        var depth = 0; var inStr = false; var esc = false
        for (c in json) {
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            when (c) { '"' -> inStr = true; '{', '[' -> if (++depth > max) return true; '}', ']' -> depth-- }
        }
        return false
    }

    /** Is this Host header one the box should answer (its own IP, localhost, a .local name)? Blocks DNS-rebinding pages. */
    fun hostOk(host: String?): Boolean {
        host ?: return true   // old clients send none
        if (host.startsWith("[")) return true   // an IPv6 address literal
        val h = host.substringBefore(':').lowercase()
        return h == "localhost" || h.endsWith(".local") || h.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))
    }

    class Request(val method: String, val path: String, val query: Map<String, String>,
                  val headers: Map<String, String>, val body: ByteArray) {
        fun bodyText() = String(body, Charsets.UTF_8)
        fun header(name: String) = headers[name.lowercase()]
    }

    class Response(val status: Int, val type: String, val body: ByteArray, val extra: Map<String, String> = emptyMap()) {
        companion object {
            fun json(o: Any, status: Int = 200) = Response(status, "application/json; charset=utf-8", o.toString().toByteArray())
            fun text(s: String, status: Int = 200) = Response(status, "text/plain; charset=utf-8", s.toByteArray())
        }
    }

    private const val MAX_BODY = 256 * 1024

    private fun readLine(inp: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = inp.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("ISO-8859-1")
            if (c == '\n'.code) return b.toString("ISO-8859-1").trimEnd('\r')
            b.write(c)
            if (b.size() > 8192) throw IllegalArgumentException("line too long")
        }
    }

    private fun decode(s: String) = java.net.URLDecoder.decode(s, "UTF-8")

    /** Reads one request, or null if the connection closed first. Throws on malformed input. */
    fun readRequest(inp: InputStream): Request? {
        val first = readLine(inp) ?: return null
        val parts = first.split(' ')
        require(parts.size >= 2) { "bad request line" }
        val method = parts[0].uppercase()
        val target = parts[1]
        val headers = HashMap<String, String>()
        var lines = 0
        while (true) {
            val l = readLine(inp) ?: break
            if (l.isEmpty()) break
            require(++lines <= 100) { "too many headers" }
            val i = l.indexOf(':'); if (i <= 0) continue
            headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        require(len in 0..MAX_BODY) { "body too large" }
        val body = ByteArray(len); var off = 0
        while (off < len) { val n = inp.read(body, off, len - off); if (n < 0) break; off += n }
        val path = decode(target.substringBefore('?'))
        val query = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
            decode(it.substringBefore('=')) to decode(it.substringAfter('=', ""))
        }
        return Request(method, path, query, headers, body)
    }

    private val REASON = mapOf(200 to "OK", 204 to "No Content", 304 to "Not Modified", 400 to "Bad Request",
        401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found", 405 to "Method Not Allowed", 409 to "Conflict",
        429 to "Too Many Requests", 500 to "Internal Server Error")

    fun writeResponse(out: OutputStream, r: Response) {
        val head = StringBuilder("HTTP/1.1 ${r.status} ${REASON[r.status] ?: "OK"}\r\n")
            .append("Content-Type: ${r.type}\r\n")
            .append("Content-Length: ${r.body.size}\r\n")
            .append("Connection: close\r\n")
            .append("X-Content-Type-Options: nosniff\r\n")
        for ((k, v) in r.extra) head.append("$k: $v\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1)); out.write(r.body); out.flush()
    }
}
