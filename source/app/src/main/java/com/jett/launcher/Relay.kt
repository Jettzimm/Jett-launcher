package com.jett.launcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * "Use it anywhere": the phone app away from home reaches the box through ntfy.sh, a free
 * public message relay (no account). The box keeps one connection open to its private,
 * random topic; the phone posts sealed requests there and the box posts sealed replies to
 * "<topic>-r". Requests run through the same code as the home-Wi-Fi app ([ControlServer]).
 *
 * If the box was asleep, ntfy keeps messages for 12 hours: every 15 minutes (even in deep
 * sleep) and on every reconnect the box fetches what it missed, so a change made from the
 * office still lands. Old actions are dropped (no turning the TV on hours late); settings
 * changes are applied up to 12 hours late.
 *
 * Budget: ntfy.sh allows 250 messages a day per network; each reply is usually one message.
 */
object Relay {
    private const val TAG = "JettRelay"
    const val BASE = "https://ntfy.sh/"
    const val SITE = "https://jettzimm.github.io/Jett-launcher/"
    private const val CATCH_UP_MS = 15 * 60_000L

    private lateinit var app: Context
    @Volatile var connected = false; private set
    @Volatile var lastSeenAt = 0L; private set
    @Volatile private var generation = 0
    @Volatile private var current: HttpURLConnection? = null
    @Volatile private var thread: Thread? = null

    // One at a time, in order (a settings change sent before a refresh lands first).
    private val pool = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, java.util.concurrent.ArrayBlockingQueue(30))
    private val seen = object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Boolean>?) = size > 500
    }

    private fun prefs() = app.getSharedPreferences("relay", Context.MODE_PRIVATE)

    /** This box's topic and key (made on first use). */
    @Synchronized fun creds(): Pair<String, ByteArray> {
        val p = prefs()
        val topic = p.getString("topic", null)
        val key = p.getString("key", null)?.let { RelayCrypto.dec(it) }
        if (topic != null && key != null && key.size == 32) return topic to key
        val t = RelayCrypto.newTopic(); val k = RelayCrypto.newKey()
        p.edit().putString("topic", t).putString("key", RelayCrypto.enc(k)).remove("since").commit()
        return t to k
    }

    /** The link that sets up the phone app away from home (key after #, which never leaves the phone). */
    fun link(): String { val (t, k) = creds(); return SITE + "#r=" + t + "." + RelayCrypto.enc(k) }

    /** New topic and key: every phone using the anywhere link has to be set up again. */
    @Synchronized fun reset() {
        prefs().edit().clear().commit(); doneCache = null; creds()
        generation++; try { current?.disconnect() } catch (e: Exception) { }
        thread?.interrupt()   // if it's waiting between retries, reconnect on the new topic now
    }

    fun start(ctx: Context) {
        app = ctx.applicationContext
        creds()
        thread = Thread({ loop() }, "relay").apply { isDaemon = true; start() }
        armCatchUp(app)
    }

    // ------------------------------------------------------------------ receiving

    private fun loop() {
        var backoff = 10_000L
        while (true) {
            val gen = generation
            try { stream(gen); backoff = 10_000L } catch (e: Exception) { Log.i(TAG, "relay link dropped: ${e.message}") }
            connected = false
            if (gen != generation) continue   // reset: reconnect at once on the new topic
            try { Thread.sleep(backoff) } catch (e: InterruptedException) { }
            backoff = minOf(backoff * 2, 120_000L)   // (each reconnect counts against ntfy's request limit)
        }
    }

    private fun open(path: String, readTimeout: Int) = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000; this.readTimeout = readTimeout
        setRequestProperty("User-Agent", "JettHome/1")
    }

    private fun stream(gen: Int) {
        val (topic, _) = creds()
        val since = prefs().getString("since", null) ?: "10m"
        held = false   // this fetch starts from the held point again
        val c = open("$topic/json?since=$since", 90_000)   // ntfy sends a keepalive every ~45 s
        current = c
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            connected = true
            c.inputStream.bufferedReader().use { r ->
                while (gen == generation) { val line = r.readLine() ?: break; onLine(line) }
            }
        } finally { c.disconnect(); current = null }
    }

    /** Fetches anything missed (from the 15-minute alarm). Waits for those requests to run. */
    fun catchUp() {
        if (!::app.isInitialized) return
        val (topic, _) = creds()
        val since = prefs().getString("since", null) ?: "10m"
        held = false
        val c = open("$topic/json?poll=1&since=$since", 15_000)
        val queued = ArrayList<java.util.concurrent.Future<*>>()
        try {
            if (c.responseCode == 200) c.inputStream.bufferedReader().use { r -> r.lineSequence().forEach { line -> onLine(line)?.let { queued += it } } }
        } catch (e: Exception) { Log.i(TAG, "catch-up failed: ${e.message}") } finally { c.disconnect() }
        // One deadline for all of it: Android allows a background broadcast about a minute.
        val end = System.currentTimeMillis() + 40_000
        for (f in queued) try { f.get(maxOf(0L, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS) } catch (e: Exception) { }
    }

    /**
     * One line from ntfy. Everything runs on the one [pool] thread, in arrival order, whichever
     * path (live link or catch-up) saw it first. "since" moves on only once a request has run, so
     * a restart mid-way runs it again (safe: each request id runs once, see [firstTime]).
     */
    private fun onLine(line: String): java.util.concurrent.Future<*>? {
        val j = try { JSONObject(line) } catch (e: Exception) { return null }
        lastSeenAt = System.currentTimeMillis()
        if (j.optString("event") != "message") return null
        val id = j.optString("id")
        val msg = j.optString("message")
        // Cheap filter before queueing: only something shaped like ours (junk can't crowd it out).
        if (!msg.startsWith("v1.") || msg.length > 4096) return null
        synchronized(seen) {
            if (seen.containsKey(id)) return null
            seen[id] = true
            return try { pool.submit { try { handle(msg) } finally { advance(id) } } }
            catch (e: java.util.concurrent.RejectedExecutionException) { seen.remove(id); held = true; null }   // full: hold "since" so it's fetched again
        }
    }

    @Volatile private var held = false
    private fun advance(id: String) { if (!held) prefs().edit().putString("since", id).apply() }

    private fun handle(sealed: String) {
        val (topic, key) = creds()
        val plain = RelayCrypto.open(key, sealed) ?: return   // not ours: ignore
        try {
            val q = JSONObject(plain)
            val id = q.optString("id").takeIf { it.matches(Regex("[a-z0-9]{6,24}")) } ?: return
            val age = System.currentTimeMillis() - q.optLong("t")
            val method = q.optString("m", "GET").uppercase()
            val path = q.optString("p")
            val write = path == "/api/settings" && method == "POST"
            // Too old to act on (a 9am "turn on" shouldn't run at noon), or from the future.
            if (age > (if (write) 12 * 3600_000L else 3 * 60_000L) || age < -10 * 60_000L) return
            // Each request runs once, even if someone re-posts the same sealed text (kept across restarts).
            if (!firstTime(id, q.optLong("t"))) return
            val res = ControlServer.relayRoute(method, path, q.optString("b"))
            if (age > 2 * 60_000L || q.optBoolean("nr")) return   // the phone stopped waiting long ago / wants no answer
            val reply = JSONObject().put("id", id).put("s", res.status).put("b", String(res.body, Charsets.UTF_8)).toString()
            for (piece in RelayCrypto.pieces(id, RelayCrypto.seal(key, reply))) post("$topic-r", piece)
        } catch (e: Throwable) { Log.w(TAG, "relay request failed", e) }
    }

    /**
     * Request ids already run, kept until they're too old to be accepted anyway (12 h + margin),
     * so a re-posted copy of an old request never runs twice. Kept across restarts.
     */
    private var doneCache: JSONObject? = null
    @Synchronized private fun firstTime(id: String, sentAt: Long): Boolean {
        val done = doneCache ?: (try { JSONObject(prefs().getString("done", "{}")!!) } catch (e: Exception) { JSONObject() })
        if (done.has(id)) return false
        val cutoff = System.currentTimeMillis() - 12 * 3600_000L - 15 * 60_000L
        val keep = JSONObject()
        for (k in done.keys()) if (done.optLong(k) > cutoff) keep.put(k, done.optLong(k))
        keep.put(id, sentAt)
        doneCache = keep
        prefs().edit().putString("done", keep.toString()).apply()   // written in the background
        return true
    }

    private fun post(topic: String, body: String) {
        val c = open(topic, 15_000)
        try {
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("X-Firebase", "no")      // don't forward to Google's push service
            c.setRequestProperty("Content-Type", "text/plain")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (c.responseCode != 200) Log.w(TAG, "reply not sent: HTTP ${c.responseCode}")
        } finally { c.disconnect() }
    }

    // ------------------------------------------------------------------ while asleep

    fun armCatchUp(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(ctx, 17, Intent(ctx, RelayReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Allowed in deep sleep (Android limits these to about one per 9 minutes, fine here).
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + CATCH_UP_MS, pi)
    }
}

class RelayReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Relay.armCatchUp(ctx)
        // Live link heard from in the last minute: nothing to fetch. (Not just "connected": in deep
        // sleep the link can look open while nothing gets through.)
        if (Relay.connected && System.currentTimeMillis() - Relay.lastSeenAt < 70_000) return
        val done = goAsync()
        Thread { try { Relay.catchUp() } finally { done.finish() } }.start()
    }
}
