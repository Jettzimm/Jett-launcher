package com.jett.launcher

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper

/**
 * App-wide listener for the TV turning on/off. Lives as long as the launcher's process
 * (always, as the home app), so it works even when the box woke up inside another app.
 */
class JettApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Started in the background (alarm, job) with the TV off: bring the home screen back so
        // Android treats the launcher as the active home app (and keeps TV-on signals flowing).
        try { if (!getSystemService(android.os.PowerManager::class.java).isInteractive) HomeRestore.run(this) }
        catch (e: Exception) { }
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    Intent.ACTION_SCREEN_ON -> PowerOn.onWake(c)
                    // TV just went off: a downloaded update installs now, unseen.
                    Intent.ACTION_SCREEN_OFF -> {
                        // Hold a wakelock so the box doesn't suspend mid-install. Never let this
                        // crash the app (it's the TV-on listener too).
                        try {
                            val wl = c.getSystemService(android.os.PowerManager::class.java)
                                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "jett:update")
                            wl.acquire(3 * 60_000L)
                            Thread {
                                try { Updater.installIfReady(c) } catch (e: Exception) { }
                                finally { if (wl.isHeld) wl.release() }
                            }.start()
                        } catch (e: Exception) { android.util.Log.w("JettApp", "screen-off", e) }
                    }
                }
            }
        }, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) })
    }
}

/** On each TV power-on: Saturday GameDay, Sunday RedZone, night games, playoffs. */
object PowerOn {
    @Volatile private var lastHandled = 0L

    fun onWake(ctx: Context) {
        // Only when the TV is actually on.
        if (!ctx.getSystemService(android.os.PowerManager::class.java).isInteractive) return
        val now = System.currentTimeMillis()
        if (now - lastHandled < 30_000) return   // one check per power-on
        lastHandled = now
        val app = ctx.applicationContext
        Thread {
            // Wi-Fi can take a few seconds to come back after wake; retry briefly.
            var target: AutoTarget? = null
            for (attempt in 0 until 4) {
                Rules.lastFetchFailed = false
                target = try { Rules.current() } catch (e: Exception) { null }
                // Only retry when ESPN couldn't be reached (Wi-Fi still reconnecting after wake).
                if (target != null || !Rules.lastFetchFailed) break
                Thread.sleep(4000)
            }
            target ?: return@Thread
            // Already offered (or dismissed with Back) today: don't ask again.
            if (Rules.alreadyDone(app, target, Rules.today())) return@Thread
            Rules.markDone(app, target, Rules.today())   // so the Sunday poller doesn't repeat it
            Handler(Looper.getMainLooper()).post { Rules.showCountdown(app, target) }
        }.start()
    }
}
