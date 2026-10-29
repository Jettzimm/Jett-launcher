package com.jett.launcher

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The online photo library (full 4K). The app ships with a smaller starter set (assets/bg); the full library
 * lives online and is mirrored to the box's storage in the background:
 *
 *   photos.json = { "version": 3, "base": "https://…/photos/",
 *                   "photos": { "dawn": [["<id>", "Kyoto, Japan"], …], "morning": […], … } }
 *   photo files = <base><phase>/<id>.webp
 *
 * New photos are downloaded, photos removed from the list are deleted, and the home screen
 * picks them up the next time it reshuffles. Nothing here ever touches the built-in set.
 */
object PhotoLibrary {
    private const val TAG = "JettPhotos"
    private const val PREFS = "photos"
    private const val JOB_ID = 43
    private val PHASES = listOf("dawn", "morning", "midday", "golden", "dusk", "night")
    private val SAFE_ID = Regex("[A-Za-z0-9_-]{6,80}")

    fun dir(ctx: Context) = File(ctx.filesDir, "photos")

    /** Downloaded photos for one time of day (complete files only). */
    fun files(ctx: Context, phase: String): List<File> =
        File(dir(ctx), phase).listFiles { f -> f.isFile && f.name.endsWith(".webp") }?.toList().orEmpty()

    /** Captions for downloaded photos (id → place). */
    fun captions(ctx: Context): Map<String, String> = try {
        val j = JSONObject(File(dir(ctx), "captions.json").readText())
        j.keys().asSequence().associateWith { j.getString(it) }
    } catch (e: Exception) { emptyMap() }

    /**
     * Called by the update check with the feed's library link and version. Queues a background
     * sync when the library changed or the last sync didn't finish.
     */
    fun onFeed(ctx: Context, url: String, version: Int) {
        if (!url.startsWith("https://")) return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val changed = url != prefs.getString("url", null) || version != prefs.getInt("syncedVersion", -1)
        prefs.edit().putString("url", url).putInt("wantVersion", version).apply()
        if (changed || !prefs.getBoolean("complete", false)) schedule(ctx)
    }

    private fun schedule(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        if (js.getPendingJob(JOB_ID) != null) return
        js.schedule(JobInfo.Builder(JOB_ID, ComponentName(ctx, PhotoSyncJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true).build())
    }

    /** Runs inside the sync job; [stopped] turns true when the system stops that job. Returns false to retry later. */
    @Synchronized fun sync(ctx: Context, stopped: java.util.concurrent.atomic.AtomicBoolean): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val url = prefs.getString("url", null) ?: return true
        val manifest = try { JSONObject(Sports.httpGet("$url?t=${System.currentTimeMillis() / 60_000}")) }
            catch (e: Exception) { Log.w(TAG, "Manifest fetch failed", e); return false }
        val version = prefs.getInt("wantVersion", manifest.optInt("version", 0))   // the feed's number
        val base = manifest.optString("base").let { if (it.endsWith("/")) it else "$it/" }
        if (!base.startsWith("https://")) return true
        val lists = manifest.optJSONObject("photos") ?: return true
        // Start from the current captions, so photos kept on disk (e.g. a time of day missing from
        // this list) keep their locations; this list's entries overwrite.
        val captions = try { JSONObject(File(dir(ctx), "captions.json").readText()) } catch (e: Exception) { JSONObject() }
        val wanted = PHASES.associateWith { p ->
            val arr = lists.optJSONArray(p) ?: return@associateWith emptySet<String>()
            (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONArray(i) ?: return@mapNotNull null
                val id = e.optString(0).takeIf { SAFE_ID.matches(it) } ?: return@mapNotNull null
                e.optString(1).takeIf { it.isNotEmpty() }?.let { captions.put(id, it) }
                id
            }.toSet()
        }
        // An empty or broken list must never wipe the library.
        if (wanted.values.sumOf { it.size } < 20) return true
        prefs.edit().putBoolean("complete", false).apply()

        val root = dir(ctx); root.mkdirs()
        File(root, "captions.json.tmp").apply { writeText(captions.toString()); renameTo(File(root, "captions.json")) }

        var failures = 0
        var skipped = false   // photos passed over during a rate-limit pause (picked up next run)
        for (p in PHASES) {
            val folder = File(root, p).apply { mkdirs() }
            val want = wanted.getValue(p)
            if (want.isEmpty()) continue   // a time of day missing from the list: leave its photos alone
            // Remove photos dropped from the library (and leftovers from interrupted downloads).
            folder.listFiles()?.forEach { f ->
                if (!f.name.endsWith(".webp") || f.name.removeSuffix(".webp") !in want) f.delete()
            }
            for (id in want) {
                if (stopped.get()) return false
                val out = File(folder, "$id.webp")
                if (out.exists()) continue
                if (root.usableSpace < 1_000_000_000L) { Log.w(TAG, "Low storage, pausing"); return false }   // retry later
                when (fetch("$base$p/$id.webp", out)) {
                    1 -> {}
                    429 -> { skipped = true; Thread.sleep(60_000); if (stopped.get()) return false }   // GitHub rate limit: pause, not a failure
                    else -> if (++failures >= 25) return false
                }
            }
        }
        val done = failures == 0 && !skipped
        prefs.edit().putInt("syncedVersion", version).putBoolean("complete", done).apply()
        return done
    }

    /** One photo: must be a complete WebP, else it's discarded. 1 = saved, 429 = rate-limited, 0 = failed. */
    private fun fetch(url: String, out: File): Int {
        val part = File(out.parentFile, out.name + ".part")
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000; conn.readTimeout = 30_000
            try {
                val code = conn.responseCode
                if (code == 429) return 429
                if (code != 200) return 0
                val expected = conn.contentLengthLong
                conn.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
                val head = part.inputStream().use { val b = ByteArray(12); it.read(b); String(b, Charsets.ISO_8859_1) }
                val ok = head.startsWith("RIFF") && head.endsWith("WEBP") && part.length() > 8_000 &&
                    (expected <= 0 || part.length() == expected)
                if (ok) part.renameTo(out) else part.delete()
                if (ok) 1 else 0
            } finally { conn.disconnect() }
        } catch (e: Exception) { part.delete(); 0 }
    }
}

/** Mirrors the online library in the background (survives reboots; retries when offline). */
class PhotoSyncJob : JobService() {
    // The current run's stop flag (only one sync job runs at a time). Each run gets its own, so
    // a restarted run can't un-stop an older one still finishing a file.
    @Volatile private var current: java.util.concurrent.atomic.AtomicBoolean? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        current = stopped
        Thread {
            val ok = try { PhotoLibrary.sync(this, stopped) } catch (e: Exception) { false }
            if (!stopped.get()) jobFinished(params, !ok)
        }.start()
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { current?.set(true); return true }
}
