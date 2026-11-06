package com.jett.launcher

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Talks to the Samsung over the home Wi-Fi (Jett's QN55Q7F, Nov 2026):
 *  - reads the exact volume from its UPnP service (port 9197; reading works, setting is refused);
 *  - presses volume keys through Samsung's own remote-control connection (port 8002), which
 *    asks once on the TV to allow "Jett Home" and then remembers the box.
 * Volume is set by reading it, pressing up/down the difference, and reading again.
 * Plain sockets, so the app's no-cleartext rule for internet traffic doesn't apply to these
 * local calls. Network: call off the main thread.
 */
object SamsungTv {
    private const val TAG = "JettSamsung"
    private const val APP_NAME = "Jett Home"

    // ---------------------------------------------------------------- read volume (UPnP)

    private fun soap(ip: String, action: String, args: String): String? = try {
        val body = """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1"><InstanceID>0</InstanceID><Channel>Master</Channel>$args</u:$action></s:Body></s:Envelope>"""
        val bytes = body.toByteArray(Charsets.UTF_8)
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, 9197), 1500); s.soTimeout = 3000
            val head = "POST /upnp/control/RenderingControl1 HTTP/1.1\r\nHost: $ip:9197\r\nContent-Type: text/xml; charset=\"utf-8\"\r\n" +
                "SOAPACTION: \"urn:schemas-upnp-org:service:RenderingControl:1#$action\"\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            s.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
            s.getInputStream().bufferedReader().readText()
        }
    } catch (e: Exception) { null }

    fun getVolume(ip: String): Int? =
        soap(ip, "GetVolume", "")?.let { Regex("<CurrentVolume>(\\d+)</CurrentVolume>").find(it)?.groupValues?.get(1)?.toInt() }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("updater", Context.MODE_PRIVATE)

    /** The TV's address: the last one that worked, else the feed's / built-in one, else a scan of the network. */
    @Volatile private var lastIp: String? = null
    @Volatile private var lastIpAt = 0L
    @Volatile private var lastScanAt = 0L

    fun find(ctx: Context): String? {
        // Recently confirmed: skip the check (the phone remote presses keys in quick bursts).
        lastIp?.let { if (System.currentTimeMillis() - lastIpAt < 60_000) return it }
        return findNow(ctx)?.also { lastIp = it; lastIpAt = System.currentTimeMillis() }
    }

    private fun findNow(ctx: Context): String? {
        val known = listOfNotNull(prefs(ctx).getString("tvIpFound", null), prefs(ctx).getString("tvIp", null), Config.TV_IP).distinct()
        known.firstOrNull { getVolume(it) != null }?.let { return it }
        // A full network search at most every 2 minutes (it's 254 probes).
        if (System.currentTimeMillis() - lastScanAt < 120_000) return null
        lastScanAt = System.currentTimeMillis()
        val base = Config.TV_IP.substringBeforeLast('.')
        val pool = Executors.newFixedThreadPool(32)
        val found = java.util.concurrent.atomic.AtomicReference<String?>(null)
        for (i in 1..254) pool.execute { if (found.get() == null) { val ip = "$base.$i"; if (getVolume(ip) != null) found.compareAndSet(null, ip) } }
        pool.shutdown(); pool.awaitTermination(20, TimeUnit.SECONDS)
        return found.get()?.also { prefs(ctx).edit().putString("tvIpFound", it).apply(); Log.i(TAG, "TV found at $it") }
    }

    // ------------------------------------------------------------- remote keys (WebSocket)

    /** A connected remote-control session; send keys with [key]. */
    private class Remote(private val sock: SSLSocket) : AutoCloseable {
        private val out: OutputStream = sock.outputStream
        private val inp: InputStream = sock.inputStream
        private val rnd = SecureRandom()

        fun sendText(text: String) {
            val payload = text.toByteArray(Charsets.UTF_8)
            val mask = ByteArray(4).also { rnd.nextBytes(it) }
            val frame = java.io.ByteArrayOutputStream()
            frame.write(0x81)
            when {
                payload.size < 126 -> frame.write(0x80 or payload.size)
                payload.size < 65536 -> { frame.write(0x80 or 126); frame.write(payload.size shr 8); frame.write(payload.size and 0xFF) }
                else -> error("too long")
            }
            frame.write(mask)
            payload.forEachIndexed { i, b -> frame.write(b.toInt() xor mask[i % 4].toInt()) }
            out.write(frame.toByteArray()); out.flush()
        }

        /** Next text message from the TV, or null on timeout/close. */
        fun readText(): String? {
            try {
                while (true) {
                    val b0 = inp.read(); if (b0 < 0) return null
                    var len = (inp.read() and 0x7F).toLong()
                    if (len == 126L) len = ((inp.read() shl 8) or inp.read()).toLong()
                    else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or inp.read().toLong() } }
                    val data = ByteArray(len.toInt()); var off = 0
                    while (off < data.size) { val n = inp.read(data, off, data.size - off); if (n < 0) return null; off += n }
                    when (b0 and 0x0F) {
                        1 -> return String(data, Charsets.UTF_8)
                        8 -> return null
                        else -> {}   // ping/pong/binary: ignore
                    }
                }
            } catch (e: Exception) { return null }
        }

        fun key(code: String) = sendText(JSONObject().put("method", "ms.remote.control").put("params", JSONObject()
            .put("Cmd", "Click").put("DataOfCmd", code).put("Option", "false").put("TypeOfRemote", "SendRemoteKey")).toString())

        override fun close() { try { sock.close() } catch (e: Exception) { } }
    }

    private fun trustAll(): SSLContext = SSLContext.getInstance("TLS").apply {
        // The TV's certificate is self-signed; this is only ever used for the TV on the home network.
        init(null, arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
        }), SecureRandom())
    }

    /** Opens the remote connection (first time: waits up to 30 s for Allow on the TV). */
    private fun connect(ctx: Context, ip: String): Remote? {
        val token = prefs(ctx).getString("tvToken", null)
        val name = Base64.encodeToString(APP_NAME.toByteArray(), Base64.NO_WRAP)
        val sock = try {
            (trustAll().socketFactory.createSocket() as SSLSocket).apply {
                connect(InetSocketAddress(ip, 8002), 3000); soTimeout = 35_000; startHandshake()
            }
        } catch (e: Exception) { Log.w(TAG, "Remote connect failed", e); return null }
        val key = Base64.encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP)
        val path = "/api/v2/channels/samsung.remote.control?name=$name" + (token?.let { "&token=$it" } ?: "")
        sock.outputStream.write(("GET $path HTTP/1.1\r\nHost: $ip:8002\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray())
        sock.outputStream.flush()
        // Response headers.
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) { val c = sock.inputStream.read(); if (c < 0) break; head.append(c.toChar()) }
        if (!head.startsWith("HTTP/1.1 101")) { Log.w(TAG, "Remote refused: ${head.lineSequence().firstOrNull()}"); sock.close(); return null }
        val r = Remote(sock)
        val hello = r.readText() ?: run { r.close(); return null }
        val j = try { JSONObject(hello) } catch (e: Exception) { JSONObject() }
        if (j.optString("event") != "ms.channel.connect") { Log.w(TAG, "Remote not allowed: ${j.optString("event")}"); r.close(); return null }
        j.optJSONObject("data")?.optString("token")?.takeIf { it.isNotEmpty() }?.let {
            prefs(ctx).edit().putString("tvToken", it).apply(); Log.i(TAG, "TV allowed this box")
        }
        sock.soTimeout = 5000
        return r
    }

    /** Presses one key on the TV's remote (e.g. "KEY_ENTER"); the TV passes it to the box over HDMI. */
    // Phone-remote presses go one at a time, in order, over one connection kept open briefly.
    private val keys = Executors.newSingleThreadExecutor()
    private var open: Remote? = null
    private var openAt = 0L

    fun pressKey(ctx: Context, code: String): Boolean = try {
        keys.submit<Boolean> {
            val ip = find(ctx) ?: return@submit false
            var r = open?.takeIf { System.currentTimeMillis() - openAt < 20_000 }
            if (r == null) { open?.close(); r = connect(ctx, ip) ?: return@submit false; open = r }
            openAt = System.currentTimeMillis()
            try { r.key(code); Thread.sleep(120); true } catch (e: Exception) {
                // Connection went stale: one fresh try.
                r.close(); open = null
                val again = connect(ctx, ip) ?: return@submit false
                open = again; again.key(code); Thread.sleep(120); true
            }
        }.get(25, TimeUnit.SECONDS)
    } catch (e: Exception) { false }

    fun getMute(ip: String): Boolean? =
        soap(ip, "GetMute", "")?.let { Regex("<CurrentMute>(\\d)</CurrentMute>").find(it)?.groupValues?.get(1)?.let { v -> v == "1" } }

    /**
     * Sets the TV to [level] (0–100): reads the volume, presses up/down the difference, checks,
     * corrects. Keeps trying for about [waitSeconds] while the TV's network starts. True if it
     * ends exactly at [level].
     */
    fun setVolume(ctx: Context, level: Int, waitSeconds: Int = 40): Boolean {
        val target = level.coerceIn(0, 100)
        val until = System.currentTimeMillis() + waitSeconds * 1000L
        while (System.currentTimeMillis() < until) {
            val ip = find(ctx)
            if (ip == null) { Thread.sleep(2000); continue }
            val remote = connect(ctx, ip)
            if (remote == null) { Thread.sleep(2000); continue }
            remote.use { r ->
                repeat(4) {
                    val now = getVolume(ip) ?: return@use
                    if (now == target) { Log.i(TAG, "TV volume is $target"); return true }
                    val code = if (now > target) "KEY_VOLDOWN" else "KEY_VOLUP"
                    repeat(Math.abs(now - target)) { r.key(code); Thread.sleep(130) }
                    Thread.sleep(700)
                }
            }
            getVolume(ip)?.let { if (it == target) return true }
        }
        Log.w(TAG, "Couldn't set the TV volume"); return false
    }
}
