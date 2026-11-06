package com.jett.launcher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ControlCoreTest {
    private val ny = ZoneId.of("America/New_York")

    private fun day(on: Boolean, time: String = "08:00") = JSONObject().put("on", on).put("time", time)
        .put("content", "youtube:FxAgAyZYXJ8").put("volume", 15)
        .put("switchAt", "09:00").put("switchTo", "CNBC").put("switchVolume", 10).put("off", "11:00")

    private fun wake(overrides: JSONObject = JSONObject()) = JSONObject().put("enabled", true)
        .put("week", JSONObject().apply { for (d in 1..7) put(d.toString(), day(d <= 5)) })
        .put("overrides", overrides)

    // 2026-11-05 is a Thursday.
    private val thu = LocalDate.of(2026, 11, 5)

    @Test fun mergeObjectsDeepAndReplacesLists() {
        val base = JSONObject().put("a", JSONObject().put("x", 1).put("y", 2)).put("l", JSONArray(listOf(1, 2)))
        val m = ControlCore.merge(base, JSONObject().put("a", JSONObject().put("y", 3)).put("l", JSONArray(listOf(9))))
        assertEquals(1, m.getJSONObject("a").getInt("x"))
        assertEquals(3, m.getJSONObject("a").getInt("y"))
        assertEquals(1, m.getJSONArray("l").length())
        assertEquals(2, base.getJSONObject("a").getInt("y"))   // base untouched
    }

    @Test fun mergeNullRemovesKey() {
        val m = ControlCore.merge(JSONObject().put("o", JSONObject().put("k", 1)),
            JSONObject().put("o", JSONObject().put("k", JSONObject.NULL)))
        assertFalse(m.getJSONObject("o").has("k"))
    }

    @Test fun weekdayPlanAndWeekendOff() {
        val w = wake()
        val m = ControlCore.planFor(w, thu)!!
        assertEquals(LocalTime.of(8, 0), m.time)
        assertEquals("youtube:FxAgAyZYXJ8", m.content)
        assertEquals(15, m.volume); assertEquals("CNBC", m.switchTo); assertEquals(10, m.switchVolume)
        assertNull(ControlCore.planFor(w, thu.plusDays(2)))   // Saturday off
    }

    @Test fun customDayAndSkipOverrideTheWeek() {
        val ov = JSONObject().put(thu.plusDays(1).toString(), day(true, "06:30"))
            .put(thu.plusDays(4).toString(), JSONObject().put("on", false))   // skip Monday
        val w = wake(ov)
        assertEquals(LocalTime.of(6, 30), ControlCore.planFor(w, thu.plusDays(1))!!.time)
        assertNull(ControlCore.planFor(w, thu.plusDays(4)))
        assertNotNull(ControlCore.planFor(w, thu.plusDays(5)))
    }

    @Test fun disabledMeansNothing() {
        assertNull(ControlCore.planFor(wake().put("enabled", false), thu))
    }

    @Test fun nextEventsFromThursdayNight() {
        val now = ZonedDateTime.of(thu.atTime(23, 0), ny)
        val e = ControlCore.nextEvents(wake(), now, ny)
        assertEquals(ZonedDateTime.of(thu.plusDays(1).atTime(8, 0), ny), e[ControlCore.Step.ON]!!.at)
        assertEquals(ZonedDateTime.of(thu.plusDays(1).atTime(9, 0), ny), e[ControlCore.Step.SWITCH]!!.at)
        assertEquals(ZonedDateTime.of(thu.plusDays(1).atTime(11, 0), ny), e[ControlCore.Step.OFF]!!.at)
    }

    @Test fun nextEventsMidMorningSkipsPastSteps() {
        val now = ZonedDateTime.of(thu.atTime(8, 30), ny)
        val e = ControlCore.nextEvents(wake(), now, ny)
        assertEquals(thu.plusDays(1), e[ControlCore.Step.ON]!!.date)     // today's 8:00 has passed
        assertEquals(thu, e[ControlCore.Step.SWITCH]!!.date)             // 9:00 today still ahead
        assertEquals(thu, e[ControlCore.Step.OFF]!!.date)
    }

    @Test fun skippingFridayJumpsToMonday() {
        val w = wake(JSONObject().put(thu.plusDays(1).toString(), JSONObject().put("on", false)))
        val e = ControlCore.nextEvents(w, ZonedDateTime.of(thu.atTime(23, 0), ny), ny)
        assertEquals(thu.plusDays(4), e[ControlCore.Step.ON]!!.date)
    }

    @Test fun noSwitchWhenNoChannel() {
        val d = day(true).put("switchTo", "")
        val m = ControlCore.morning(d)!!
        assertNull(m.switchAt)
    }

    @Test fun pruneDropsPastDays() {
        val w = wake(JSONObject().put("2026-11-01", JSONObject().put("on", false)).put("2026-11-09", JSONObject().put("on", false)))
        val p = ControlCore.pruneOverrides(w, thu)
        assertFalse(p.getJSONObject("overrides").has("2026-11-01"))
        assertTrue(p.getJSONObject("overrides").has("2026-11-09"))
    }

    @Test fun barWindowsIncludingOvernight() {
        val bar = JSONObject().put("windows", JSONArray()
            .put(JSONObject().put("name", "Morning news").put("days", "1-5").put("start", "06:00").put("end", "09:00")
                .put("channels", JSONArray(listOf("CNBC", "CNN"))))
            .put(JSONObject().put("name", "Late").put("days", "1-7").put("start", "23:00").put("end", "02:00")
                .put("channels", JSONArray(listOf("ESPN")))))
        assertEquals("Morning news", ControlCore.barWindow(bar, ZonedDateTime.of(thu.atTime(7, 0), ny))!!.getString("name"))
        assertNull(ControlCore.barWindow(bar, ZonedDateTime.of(thu.atTime(9, 0), ny)))
        assertNull(ControlCore.barWindow(bar, ZonedDateTime.of(thu.plusDays(2).atTime(7, 0), ny)))   // Saturday
        assertEquals("Late", ControlCore.barWindow(bar, ZonedDateTime.of(thu.atTime(1, 30), ny))!!.getString("name"))
        assertEquals("Late", ControlCore.barWindow(bar, ZonedDateTime.of(thu.atTime(23, 30), ny))!!.getString("name"))
    }

    @Test fun overnightWindowBelongsToTheEvening() {
        val bar = JSONObject().put("windows", JSONArray().put(JSONObject().put("name", "Fri late").put("days", "5")
            .put("start", "22:00").put("end", "02:00").put("channels", JSONArray(listOf("ESPN")))))
        val fri = thu.plusDays(1)
        assertNotNull(ControlCore.barWindow(bar, ZonedDateTime.of(fri.atTime(23, 0), ny)))
        assertNotNull(ControlCore.barWindow(bar, ZonedDateTime.of(fri.plusDays(1).atTime(1, 0), ny)))   // Sat 1am
        assertNull(ControlCore.barWindow(bar, ZonedDateTime.of(fri.atTime(1, 0), ny)))                  // Fri 1am = Thursday night
    }

    @Test fun switchOrOffBeforeTurnOnIgnored() {
        val m = ControlCore.morning(day(true, "10:00"))!!   // switch 9:00, off 11:00
        assertNull(m.switchAt); assertEquals(LocalTime.of(11, 0), m.off)
    }

    @Test fun refreshRightBeforeWakeKeepsIt() {
        val now = ZonedDateTime.of(thu.atTime(7, 59, 50), ny)
        assertEquals(thu, ControlCore.nextEvents(wake(), now, ny)[ControlCore.Step.ON]!!.date)
        assertEquals(thu.plusDays(1), ControlCore.nextEvents(wake(), ZonedDateTime.of(thu.atTime(8, 0, 1), ny), ny, 60)[ControlCore.Step.ON]!!.date)
    }

    @Test fun bedtimeAfterMidnightIsNextMorning() {
        val night = JSONObject().put("on", true).put("time", "00:30").put("days", "5")   // Friday night
        val at = ControlCore.nextNightly(night, ZonedDateTime.of(thu.atTime(12, 0), ny), ny)!!
        assertEquals(ZonedDateTime.of(thu.plusDays(2).atTime(0, 30), ny), at)               // Sat 00:30
        val late = JSONObject().put("on", true).put("time", "23:00").put("days", "1-7")
        assertEquals(ZonedDateTime.of(thu.atTime(23, 0), ny), ControlCore.nextNightly(late, ZonedDateTime.of(thu.atTime(12, 0), ny), ny))
        assertNull(ControlCore.nextNightly(JSONObject().put("on", false), ZonedDateTime.of(thu.atTime(12, 0), ny), ny))
    }

    @Test fun jsonDepth() {
        assertTrue(ControlCore.tooDeep("{\"a\":".repeat(40) + "1" + "}".repeat(40)))
        assertFalse(ControlCore.tooDeep("""{"a":{"b":[1,2,{"c":"{{{{"}]}}"""))
    }

    @Test fun hosts() {
        assertTrue(ControlCore.hostOk("192.168.1.250:8765")); assertTrue(ControlCore.hostOk("jett.local"))
        assertFalse(ControlCore.hostOk("evil.example.com:8765"))
    }

    @Test fun daysSpec() {
        assertEquals(5, ControlCore.days("1-5").size)
        assertEquals(2, ControlCore.days("6,7").size)
        assertEquals(0, ControlCore.days("x").size)
    }

    @Test fun httpRequestParsing() {
        val body = """{"do":"volume","level":12}"""
        val raw = "POST /api/action?x=a%20b&y=1 HTTP/1.1\r\nHost: box\r\nX-Jett-Token: abc\r\nContent-Length: ${body.length}\r\n\r\n$body"
        val r = ControlCore.readRequest(ByteArrayInputStream(raw.toByteArray()))!!
        assertEquals("POST", r.method); assertEquals("/api/action", r.path)
        assertEquals("a b", r.query["x"]); assertEquals("abc", r.header("X-Jett-Token"))
        assertEquals(12, JSONObject(r.bodyText()).getInt("level"))
    }

    @Test(expected = IllegalArgumentException::class) fun hugeBodyRejected() {
        ControlCore.readRequest(ByteArrayInputStream("POST / HTTP/1.1\r\nContent-Length: 99999999\r\n\r\n".toByteArray()))
    }

    @Test fun emptyConnection() {
        assertNull(ControlCore.readRequest(ByteArrayInputStream(ByteArray(0))))
    }

    @Test fun httpResponseWriting() {
        val out = ByteArrayOutputStream()
        ControlCore.writeResponse(out, ControlCore.Response.json(JSONObject().put("ok", true)))
        val s = out.toString("UTF-8")
        assertTrue(s.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(s.contains("Content-Length: 11\r\n"))
        assertTrue(s.endsWith("{\"ok\":true}"))
    }
}
