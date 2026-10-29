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
 * Channel codes are saved straight away. A newer APK is downloaded in the background and
 * installed while the TV is off (or while you're not using the home screen), so it never
 * interrupts what you're watching.
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
        val pi = PendingIntent.getBroadcast(ctx, 7, Intent(ctx, UpdateAlarmReceiver::class.java).setAction(ACTION_CHECK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        ctx.getSharedPreferences("alarms", Context.MODE_PRIVATE).edit().putLong("update", next.toInstant().toEpochMilli()).apply()
        ctx.getSystemService(AlarmManager::class.java)
            .setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), pi)
    }

    /**
     * Network work — call off the main thread. Saves fresh channel codes; if there's a newer
     * version, queues its download (a background job, so a slow 47MB download can't be cut off).
     */
    fun check(ctx: Context) {
        val feedUrl = Config.UPDATE_FEED_URL
        if (feedUrl.isEmpty()) return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        try {
            val feed = JSONObject(Sports.httpGet("$feedUrl?t=${System.currentTimeMillis() / 60_000}"))
            val e = prefs.edit()
            feed.optJSONObject("channels")?.let { ch -> ch.keys().forEach { k -> e.putString("ch_$k", ch.optString(k)) } }
            e.putLong("lastFeed", System.currentTimeMillis()).putBoolean("feedFailed", false)
            feed.optString("linkStyle").takeIf { it == "app" || it == "web" }?.let { e.putString("linkStyle", it) }
            val remote = feed.optInt("versionCode", 0)
            val apk = feed.optString("apk")
            val alreadyHave = remote == prefs.getInt("readyVersion", 0) && readyFile(ctx).exists()
            if (remote > localVersion(ctx) && apk.startsWith("https://") && !alreadyHave &&
                prefs.getInt("fails_$remote", 0) < 3) {
                e.putInt("wantVersion", remote).putString("wantApk", apk)
                e.apply()
                try { scheduleDownload(ctx) } catch (ex: Exception) { Log.w(TAG, "Couldn't queue download", ex) }
            } else e.apply()
            // Photos last and on their own, so a problem there can never block updates or codes.
            try {
                feed.optString("photos").takeIf { it.isNotEmpty() }?.let { PhotoLibrary.onFeed(ctx, it, feed.optInt("photosVersion", 0)) }
            } catch (ex: Exception) { Log.w(TAG, "Photo library check failed", ex) }
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed", e)
            // e.g. Wi-Fi not up yet right after boot: retry on the next home-screen visit.
            prefs.edit().putBoolean("feedFailed", true).putLong("feedAttempt", System.currentTimeMillis()).apply()
        }
    }

    /** Refresh at most every 6 hours (cheap, called on each home-screen visit while the TV is on). */
    fun refreshChannelsIfStale(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val stale = now - prefs.getLong("lastFeed", 0) > 6 * 3600_000L
        val retry = prefs.getBoolean("feedFailed", false) && now - prefs.getLong("feedAttempt", 0) > 2 * 60_000L
        if (stale || retry) check(ctx)
    }

    private fun localVersion(ctx: Context) = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode

    private fun scheduleDownload(ctx: Context) {
        val js = ctx.getSystemService(android.app.job.JobScheduler::class.java)
        // Re-scheduling the same job would cancel a download in progress.
        if (js.getPendingJob(42) != null) return
        js.schedule(android.app.job.JobInfo.Builder(42, android.content.ComponentName(ctx, UpdateDownloadJob::class.java))
            .setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true).build())
    }

    fun readyFile(ctx: Context) = File(ctx.filesDir, "update.apk")

    private val downloadLock = Any()

    /** Runs inside the download job. (Own lock, so a slow download never blocks an install check.) */
    fun download(ctx: Context): Boolean = synchronized(downloadLock) { downloadLocked(ctx) }

    private fun downloadLocked(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val v = prefs.getInt("wantVersion", 0); val url = prefs.getString("wantApk", null) ?: return true
        if (v <= localVersion(ctx)) return true
        if (prefs.getInt("readyVersion", 0) == v && readyFile(ctx).exists()) return true   // already downloaded
        if (prefs.getInt("fails_$v", 0) >= 3) return true   // gave up on this version
        val part = File(ctx.filesDir, "update.part")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true; conn.connectTimeout = 15_000; conn.readTimeout = 60_000
        if (conn.responseCode != 200) {
            val code = conn.responseCode; conn.disconnect()
            // A broken link (404 etc.) counts toward the 3-strikes limit; rate limits / outages don't.
            // At most one strike per hour, so a feed published a few minutes before its APK isn't written off.
            val now = System.currentTimeMillis()
            if (code in 400..499 && code != 429 && now - prefs.getLong("strikeAt_$v", 0) > 3600_000L)
                prefs.edit().putInt("fails_$v", prefs.getInt("fails_$v", 0) + 1).putLong("strikeAt_$v", now).apply()
            return false
        }
        val expected = conn.contentLengthLong
        conn.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
        // Reject partial downloads and error pages (a real APK is a zip: starts with "PK").
        val head = part.inputStream().use { val b = ByteArray(2); it.read(b); b }
        if ((expected > 0 && part.length() != expected) || head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) {
            part.delete(); return false
        }
        // Must really be the version the feed promised, of this app (else a mislabelled APK would
        // reinstall itself forever).
        @Suppress("DEPRECATION")
        val info = ctx.packageManager.getPackageArchiveInfo(part.path, 0)
        if (info == null) {   // unreadable (corrupt download): try again later, at most 3 times
            part.delete(); prefs.edit().putInt("fails_$v", prefs.getInt("fails_$v", 0) + 1).apply(); return false
        }
        if (info.packageName != ctx.packageName || info.longVersionCode != v.toLong()) {
            part.delete()
            prefs.edit().putInt("fails_$v", 3).apply()   // never retry a mislabelled version
            return true
        }
        // Record the version first, then move the file in (an install check in between just sees no file yet).
        prefs.edit().putInt("readyVersion", v).commit()
        part.renameTo(readyFile(ctx))
        // Already off? Install straight away; otherwise it installs when the TV next turns off.
        if (!ctx.getSystemService(PowerManager::class.java).isInteractive) installIfReady(ctx) else installIfIdleOnHome(ctx)
        return true
    }

    /**
     * Android asked for a one-time OK (usually only the very first self-update). That prompt is
     * shown the next time you're on the home screen with the TV on, never into a dark screen.
     */
    fun confirmIfNeeded(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("needsOk", false)) {
            val v = prefs.getInt("readyVersion", 0)
            val asks = prefs.getInt("asks_$v", 0)
            // Pressed Back on it: don't ask again for 12 hours; after 3 asks, wait for the next version.
            if (System.currentTimeMillis() - prefs.getLong("askedAt", 0) < 12 * 3600_000L) return
            // An install of this version is still in flight: don't spend an ask on it.
            if (prefs.getInt("committedVersion", 0) == v &&
                System.currentTimeMillis() - prefs.getLong("committedAt", 0) < 10 * 60_000L) return
            prefs.edit().putBoolean("needsOk", false).putInt("asks_$v", asks + 1)
                .putLong("askedAt", System.currentTimeMillis()).apply()
            if (asks >= 3) return
            installIfReady(ctx, whileOn = true)
        }
    }

    /**
     * Fallback for a box that never goes to standby (HDMI-CEC not putting it to sleep): install
     * anyway unless you're actively using the home screen. Updating only restarts this launcher,
     * so a show playing in another app (or the screensaver) isn't interrupted.
     */
    fun installIfIdleOnHome(ctx: Context) {
        val activeOnHome = MainActivity.onHome && System.currentTimeMillis() - MainActivity.lastKeyAt < 2 * 3600_000L
        if (!activeOnHome && !CountdownActivity.showing) installIfReady(ctx, whileOn = true)
    }

    /** Installs a downloaded update (normally while the TV is off). Local file, takes seconds. */
    @Synchronized fun installIfReady(ctx: Context, whileOn: Boolean = false) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val file = readyFile(ctx)
        if (!file.exists()) return
        val ready = prefs.getInt("readyVersion", 0)
        if (ready <= localVersion(ctx)) { file.delete(); return }
        // Already handed this version to Android in the last 10 minutes: don't install it twice.
        if (prefs.getInt("committedVersion", 0) == ready &&
            System.currentTimeMillis() - prefs.getLong("committedAt", 0) < 10 * 60_000L) return
        if (!whileOn && ctx.getSystemService(PowerManager::class.java).isInteractive) return
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
                if (!whileOn && ctx.getSystemService(PowerManager::class.java).isInteractive) { session.abandon(); return }
                val pi = PendingIntent.getBroadcast(ctx, 8, Intent(ctx, InstallResultReceiver::class.java).setAction(ACTION_INSTALLED),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                prefs.edit().putInt("committedVersion", ready).putLong("committedAt", System.currentTimeMillis()).commit()
                session.commit(pi.intentSender)
            }
            Log.i(TAG, "Update committed")
        } catch (e: Exception) {
            Log.w(TAG, "Install failed", e)
            // Count it, so a version that can never install stops being retried after 3 tries.
            val v = prefs.getInt("readyVersion", 0)
            val fails = prefs.getInt("fails_$v", 0) + 1
            prefs.edit().putInt("fails_$v", fails).apply()
            if (fails >= 3) { file.delete(); prefs.edit().remove("readyVersion").apply() }
        }
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

open class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        // Exported one: system signals only. Our own alarms go to the private UpdateAlarmReceiver.
        val mine = intent.action == Updater.ACTION_CHECK || intent.action == HomeRestore.ACTION
        if (mine != (this is UpdateAlarmReceiver)) return
        when (intent.action) {
            // Just updated itself: the app was restarted; re-arm everything so the TV-on listener
            // and alarms are live before the next power-on.
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val prefs = ctx.getSharedPreferences("updater", Context.MODE_PRIVATE)
                val local = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
                if (prefs.getInt("readyVersion", 0) <= local) { Updater.readyFile(ctx).delete(); prefs.edit().remove("readyVersion").apply() }
                Sleep.rearm(ctx); WindowAlarm.scheduleNext(ctx); Rules.scheduleNflCheck(ctx); Updater.scheduleDaily(ctx)
                HomeRestore.run(ctx)
                // Fresh version: pick up the latest channel codes right away.
                val pending = goAsync()
                Thread { try { Updater.check(ctx) } catch (e: Exception) { } finally { pending.finish() } }.start()
            }
            HomeRestore.ACTION -> HomeRestore.run(ctx)
            Updater.ACTION_CHECK, Intent.ACTION_BOOT_COMPLETED -> {
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) { Sleep.rearm(ctx); WindowAlarm.scheduleNext(ctx) }
                val pending = goAsync()
                Thread {
                    try {
                        Updater.check(ctx)
                        if (ctx.getSystemService(PowerManager::class.java).isInteractive) Updater.installIfIdleOnHome(ctx)
                        else Updater.installIfReady(ctx)
                    }
                    catch (e: Exception) { } finally {
                        Updater.scheduleDaily(ctx); pending.finish()
                    }
                }.start()
            }

        }
    }
}

/** Private (not exported) target for the update-check and home-restore alarms. */
class UpdateAlarmReceiver : UpdateReceiver()

/** Install results. Not exported: only the system installer (via our PendingIntent) reaches it. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val prefs = ctx.getSharedPreferences("updater", Context.MODE_PRIVATE)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        // Cancelled (we set it aside for later, or you pressed Back on the OK screen): keep the
        // file and ask again on the home screen. Not a failure.
        if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
            prefs.edit().putBoolean("needsOk", true).apply(); return
        }
        if (status != PackageInstaller.STATUS_SUCCESS && status != PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Bad file or failed install: clear it; after 3 failures, stop retrying that version.
            Updater.readyFile(ctx).delete()
            val v = prefs.getInt("readyVersion", 0)
            val fails = prefs.getInt("fails_$v", 0) + 1
            prefs.edit().remove("readyVersion").putInt("fails_$v", fails).apply()
        }
        // Only the very first self-update may need a one-time OK on screen. TV off: drop this
        // attempt and ask next time you're on the home screen instead.
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Only show it if you're on the home screen right now (not into a dark TV or a show).
            val on = ctx.getSystemService(PowerManager::class.java).isInteractive && MainActivity.onHome
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (on && confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(confirm)
            } else {
                val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
                if (id >= 0) try { ctx.packageManager.packageInstaller.abandonSession(id) } catch (e: Exception) { }
                prefs.edit().putBoolean("needsOk", true).apply()
            }
        }
    }
}

/**
 * After a self-update Android restarts this app without its home screen, and Android 14 then
 * holds back TV-on/off signals from it. Bringing the home screen back (only while the TV is
 * off, so nothing on screen is interrupted) restores normal behaviour; otherwise retry every
 * 10 minutes for up to a day.
 */
object HomeRestore {
    const val ACTION = "com.jett.launcher.HOME_RESTORE"
    fun run(ctx: Context) {
        val prefs = ctx.getSharedPreferences("updater", Context.MODE_PRIVATE)
        // A home screen exists in this process (on top or not): Android already treats us as the
        // home app, nothing to restore.
        if (MainActivity.alive) { prefs.edit().remove("restoreTries").apply(); return }
        if (!ctx.getSystemService(PowerManager::class.java).isInteractive) {
            // Android can block this silently, so don't assume it worked: the follow-up alarm
            // below re-checks (and stops once the home screen exists).
            try {
                ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setPackage(ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) { Log.w("JettUpdater", "Home restore failed", e) }
        }
        // TV on: retry every 10 min for up to a day (other alarms keep calling this after that).
        val tries = prefs.getInt("restoreTries", 0)
        if (tries >= 144) return
        prefs.edit().putInt("restoreTries", tries + 1).apply()
        val pi = PendingIntent.getBroadcast(ctx, 9, Intent(ctx, UpdateAlarmReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        ctx.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 10 * 60_000L, pi)
    }
}
