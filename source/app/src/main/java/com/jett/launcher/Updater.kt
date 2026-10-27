package com.jett.launcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.PowerManager
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime

/**
 * Self-updates. The feed (Config.UPDATE_FEED_URL) is a small JSON file:
 *   { "versionCode": 3, "apk": "https://…/JettLauncher.apk",
 *     "channels": { "ESPN": "abc123", "NFL RedZone": "def456", … },
 *     "photos": "https://…/photos.json", "photosVersion": 1 }
 * Channel codes are saved straight away. A newer APK is downloaded and installed only
 * while the TV is off, so it never interrupts what you're watching.
 */
object Updater {
    private const val TAG = "JettUpdater"
    private const val PREFS = "updater"
    const val ACTION_CHECK = "com.jett.launcher.UPDATE_CHECK"
    const val ACTION_INSTALLED = "com.jett.launcher.UPDATE_INSTALLED"

    /** "app" or "web": set by the feed after testing on the box, else the Config default. */
    fun linkStyle(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("linkStyle", null) ?: Config.YTTV_LINK_STYLE

    fun channelCode(ctx: Context, channel: String): String {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.all.entries.firstOrNull { it.key.equals("ch_$channel", true) }?.value?.toString()
            ?.takeIf { it.isNotEmpty() }?.let { return it }
        return Config.YTTV_CHANNEL_CODES.entries.firstOrNull { it.key.equals(channel, true) }?.value.orEmpty()
    }

    fun scheduleDaily(ctx: Context) {
        val now = ZonedDateTime.now(Config.ZONE)
        var next = now.withHour(Config.UPDATE_CHECK_HOUR).withMinute(7).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val pi = PendingIntent.getBroadcast(ctx, 7, Intent(ctx, UpdateReceiver::class.java).setAction(ACTION_CHECK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        ctx.getSystemService(AlarmManager::class.java)
            .setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), pi)
    }

    /**
     * Network work — call off the main thread. Saves fresh channel codes; if there's a newer
     * version, queues its download (a background job, so a slow 95MB download can't be cut off).
     */
    fun check(ctx: Context) {
        val feedUrl = Config.UPDATE_FEED_URL
        if (feedUrl.isEmpty()) return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        try {
            val feed = JSONObject(Sports.httpGet("$feedUrl?t=${System.currentTimeMillis() / 60_000}"))
            val e = prefs.edit()
            feed.optJSONObject("channels")?.let { ch -> ch.keys().forEach { k -> e.putString("ch_$k", ch.optString(k)) } }
            e.putLong("lastFeed", System.currentTimeMillis())
            feed.optString("linkStyle").takeIf { it == "app" || it == "web" }?.let { e.putString("linkStyle", it) }
            feed.optString("photos").takeIf { it.isNotEmpty() }?.let { PhotoLibrary.onFeed(ctx, it, feed.optInt("photosVersion", 0)) }
            val remote = feed.optInt("versionCode", 0)
            if (remote > localVersion(ctx) && remote != prefs.getInt("readyVersion", 0) && prefs.getInt("fails_$remote", 0) < 3) {
                e.putInt("wantVersion", remote).putString("wantApk", feed.getString("apk"))
                e.apply()
                scheduleDownload(ctx)
            } else e.apply()
        } catch (e: Exception) { Log.w(TAG, "Update check failed", e) }
    }

    /** Refresh at most every 6 hours (cheap, called on each home-screen visit while the TV is on). */
    fun refreshChannelsIfStale(ctx: Context) {
        val last = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("lastFeed", 0)
        if (System.currentTimeMillis() - last > 6 * 3600_000L) check(ctx)
    }

    private fun localVersion(ctx: Context) = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode

    private fun scheduleDownload(ctx: Context) {
        val js = ctx.getSystemService(android.app.job.JobScheduler::class.java)
        js.schedule(android.app.job.JobInfo.Builder(42, android.content.ComponentName(ctx, UpdateDownloadJob::class.java))
            .setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true).build())
    }

    fun readyFile(ctx: Context) = File(ctx.filesDir, "update.apk")

    /** Runs inside the download job. */
    @Synchronized fun download(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val v = prefs.getInt("wantVersion", 0); val url = prefs.getString("wantApk", null) ?: return true
        if (v <= localVersion(ctx)) return true
        val part = File(ctx.filesDir, "update.part")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true; conn.connectTimeout = 15_000; conn.readTimeout = 60_000
        if (conn.responseCode != 200) { conn.disconnect(); return false }   // retry later
        val expected = conn.contentLengthLong
        conn.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
        // Reject partial downloads and error pages (a real APK is a zip: starts with "PK").
        val head = part.inputStream().use { val b = ByteArray(2); it.read(b); b }
        if ((expected > 0 && part.length() != expected) || head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) {
            part.delete(); return false
        }
        part.renameTo(readyFile(ctx))
        prefs.edit().putInt("readyVersion", v).apply()
        // Already off? Install straight away; otherwise it installs when the TV next turns off.
        if (!ctx.getSystemService(PowerManager::class.java).isInteractive) installIfReady(ctx)
        return true
    }

    /** Installs a downloaded update, only while the TV is off. Local file, takes seconds. */
    @Synchronized fun installIfReady(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val file = readyFile(ctx)
        if (!file.exists()) return
        if (prefs.getInt("readyVersion", 0) <= localVersion(ctx)) { file.delete(); return }
        if (ctx.getSystemService(PowerManager::class.java).isInteractive) return
        try {
            val installer = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                .apply { setAppPackageName(ctx.packageName) }
            if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                try {
                    session.openWrite("base.apk", 0, file.length()).use { out ->
                        file.inputStream().use { it.copyTo(out) }; session.fsync(out)
                    }
                } catch (e: Exception) { session.abandon(); throw e }
                // TV came back on during the copy? Don't restart the app in front of you.
                if (ctx.getSystemService(PowerManager::class.java).isInteractive) { session.abandon(); return }
                val pi = PendingIntent.getBroadcast(ctx, 8, Intent(ctx, InstallResultReceiver::class.java).setAction(ACTION_INSTALLED),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(pi.intentSender)
            }
            Log.i(TAG, "Update committed")
        } catch (e: Exception) { Log.w(TAG, "Install failed", e) }
    }
}

/** Downloads a new version in the background with a network guarantee. */
class UpdateDownloadJob : android.app.job.JobService() {
    override fun onStartJob(params: android.app.job.JobParameters): Boolean {
        Thread {
            val ok = try { Updater.download(this) } catch (e: Exception) { false }
            jobFinished(params, !ok)   // retry later on failure
        }.start()
        return true
    }
    override fun onStopJob(params: android.app.job.JobParameters) = true
}

class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Updater.ACTION_CHECK, Intent.ACTION_BOOT_COMPLETED -> {
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) { Sleep.rearm(ctx); WindowAlarm.scheduleNext(ctx) }
                val pending = goAsync()
                Thread {
                    try { Updater.check(ctx); Updater.installIfReady(ctx) } finally {
                        Updater.scheduleDaily(ctx); pending.finish()
                    }
                }.start()
            }

        }
    }
}

/** Install results. Not exported: only the system installer (via our PendingIntent) reaches it. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val prefs = ctx.getSharedPreferences("updater", Context.MODE_PRIVATE)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status != PackageInstaller.STATUS_SUCCESS && status != PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Bad file or failed install: clear it; after 3 failures, stop retrying that version.
            Updater.readyFile(ctx).delete()
            val v = prefs.getInt("readyVersion", 0)
            val fails = prefs.getInt("fails_$v", 0) + 1
            prefs.edit().remove("readyVersion").putInt("fails_$v", fails).apply()
        }
        // Only the very first self-update may need a one-time OK on screen.
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            (intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(it)
            }
        }
    }
}
