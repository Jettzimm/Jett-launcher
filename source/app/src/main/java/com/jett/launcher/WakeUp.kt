package com.jett.launcher

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.time.ZonedDateTime

/**
 * Scheduled wake-ups, set in the phone app (Settings "wake"): each weekday has its own plan
 * (turn on at a time with a channel or a nature video at a volume, optionally switch to
 * another channel at a set time and volume, optionally turn off), and any date can have a
 * one-off plan or be skipped. Only turns on while the TV is off, so it never interrupts you.
 * The switch and the turn-off only happen if the TV is still on from that wake-up (turn it
 * off and on yourself and it's left alone).
 */
object WakeUp {
    private const val TAG = "JettWake"
    private const val PREFS = "updater"

    /** The old feed list is no longer used (the phone app owns the schedule); kept harmless. */
    fun onFeed(ctx: Context, json: String) { schedule(ctx) }

    private fun pi(ctx: Context, code: Int, extra: Intent.() -> Unit = {}) = PendingIntent.getBroadcast(ctx, code,
        Intent(ctx, WakeUpReceiver::class.java).apply(extra),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun arm(ctx: Context, at: ZonedDateTime, p: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        try {
            // An alarm clock: the most dependable way to wake a sleeping box at an exact minute.
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at.toInstant().toEpochMilli(), null), p)
        } catch (e: SecurityException) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toInstant().toEpochMilli(), p)
        }
    }

    /** The next on / switch / off, from the phone app's schedule. */
    fun upcoming(ctx: Context, afterSeconds: Long = 0) =
        ControlCore.nextEvents(AppSettings.wake(), ZonedDateTime.now(Config.ZONE), Config.ZONE, afterSeconds)

    /** Bedtime: the next automatic turn-off at night, or null. */
    fun nextNight(afterSeconds: Long = 0) =
        ControlCore.nextNightly(AppSettings.section("night"), ZonedDateTime.now(Config.ZONE), Config.ZONE, afterSeconds)

    /**
     * Arms the next turn-on, switch, turn-off and bedtime (or clears them). [afterSeconds]: an
     * alarm that just fired passes a minute so the same moment isn't armed again.
     */
    fun schedule(ctx: Context, afterSeconds: Long = 0) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val next = upcoming(ctx, afterSeconds)
        nextNight(afterSeconds)?.let { arm(ctx, it, pi(ctx, 16) { action = ACTION_NIGHT }) }
            ?: am.cancel(pi(ctx, 16) { action = ACTION_NIGHT })
        next[ControlCore.Step.ON]?.let { e ->
            arm(ctx, e.at, pi(ctx, 12) { action = ACTION_ON; putExtra("channel", e.m.content); putExtra("volume", e.m.volume); putExtra("date", e.date.toString()) })
            Log.i(TAG, "Next wake-up ${e.at} → ${e.m.content}")
        } ?: am.cancel(pi(ctx, 12) { action = ACTION_ON })
        next[ControlCore.Step.SWITCH]?.let { e ->
            arm(ctx, e.at, pi(ctx, 15) { action = ACTION_SWITCH; putExtra("channel", e.m.switchTo); putExtra("volume", e.m.switchVolume) })
        } ?: am.cancel(pi(ctx, 15) { action = ACTION_SWITCH })
        next[ControlCore.Step.OFF]?.let { e -> arm(ctx, e.at, pi(ctx, 13) { action = ACTION_OFF }) }
            ?: am.cancel(pi(ctx, 13) { action = ACTION_OFF })
    }

    /** "Turn on" / "Try my morning now" from the phone app. */
    fun wakeNow(ctx: Context, content: String, volume: Int) {
        val app = ctx.applicationContext
        if (app.getSystemService(PowerManager::class.java).isInteractive) {
            // Already on: just do the volume and the channel.
            setVolume(app, volume) { openAndPlay(app, content) }
            return
        }
        // From the phone: not a scheduled morning, so the 9:00 switch and 11:00 off don't apply to it.
        app.startActivity(Intent(app, WakeUpActivity::class.java).putExtra("channel", content).putExtra("volume", volume)
            .putExtra("scheduled", false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
    }

    /** After waking: let the TV power up and switch input, set the volume, then tune. */
    fun runSequence(app: Context, channel: String, volume: Int, scheduled: Boolean = true) {
        Handler(Looper.getMainLooper()).postDelayed({
            if (scheduled) app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("wokeAt", System.currentTimeMillis()).apply()
            setVolume(app, volume) {
                Log.i(TAG, "Tuning $channel")
                openAndPlay(app, channel)
            }
        }, 5_000)
    }

    fun armTest(ctx: Context, at: ZonedDateTime, channel: String, volume: Int) =
        arm(ctx, at, pi(ctx, 14) { action = ACTION_ON; putExtra("channel", channel); putExtra("volume", volume) })

    /**
     * Opens a channel or video, then makes sure it actually plays: YouTube sometimes stops on its
     * "Who's watching?" screen. If nothing is playing a few seconds later, presses OK once
     * (picks the highlighted account, which is the last one used); checks again, and tries
     * once more. Never presses anything while sound is playing, so it can't pause a video.
     */
    fun openAndPlay(app: Context, target: String) {
        val opened = try { Router.openTarget(app, target) } catch (e: Exception) { Log.w(TAG, "Tune failed", e); false }
        Log.i(TAG, "Tuned $target: $opened")
        if (!target.startsWith("youtube:")) return
        val am = app.getSystemService(android.media.AudioManager::class.java)
        Thread {
            repeat(2) { attempt ->
                Thread.sleep(if (attempt == 0) 10_000 else 8_000)
                if (am.isMusicActive) { Log.i(TAG, "Playing"); return@Thread }
                Log.i(TAG, "Nothing playing (account screen?): pressing OK")
                try { SamsungTv.pressKey(app, "KEY_ENTER") } catch (e: Exception) { }
            }
        }.start()
    }

    const val ACTION_ON = "com.jett.launcher.WAKE_ON"
    const val ACTION_OFF = "com.jett.launcher.WAKE_OFF"
    const val ACTION_SWITCH = "com.jett.launcher.WAKE_SWITCH"
    const val ACTION_NIGHT = "com.jett.launcher.WAKE_NIGHT"

    /** The TV was switched off (by anyone): a later turn-off time no longer applies. */
    fun onScreenOff(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("wokeAt").apply()

    /**
     * Sets the TV's volume to [percent] before the channel starts (Samsung remote over Wi-Fi,
     * checked against the TV's own reading). The box's own volume stays at full. [done] is
     * always called, within about 45 seconds, whether or not the volume could be set.
     */
    fun setVolume(ctx: Context, percent: Int, done: () -> Unit) {
        val am = ctx.getSystemService(android.media.AudioManager::class.java)
        val s = android.media.AudioManager.STREAM_MUSIC
        // Turning the box down instead makes everything near-silent at any TV volume.
        if (!am.isVolumeFixed) am.setStreamVolume(s, am.getStreamMaxVolume(s), 0)
        if (percent < 0) { done(); return }
        val main = Handler(Looper.getMainLooper())
        val once = java.util.concurrent.atomic.AtomicBoolean(false)
        val finish = { if (once.compareAndSet(false, true)) main.post(done) }
        main.postDelayed({ if (!once.get()) Log.w(TAG, "Volume took too long; tuning anyway"); finish() }, 45_000)
        Thread {
            try { SamsungTv.setVolume(ctx, percent) } catch (e: Exception) { Log.w(TAG, "Volume failed", e) }
            finish()
        }.start()
    }
}

class WakeUpReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        WakeUp.schedule(ctx, afterSeconds = 2)   // arm the next ones first (not this one again)
        val on = ctx.getSystemService(PowerManager::class.java).isInteractive
        val prefs = ctx.getSharedPreferences("updater", Context.MODE_PRIVATE)
        when (intent.action) {
            WakeUp.ACTION_ON -> {
                val channel = intent.getStringExtra("channel").orEmpty()
                // TV already on: you're using it, leave it alone.
                if (channel.isEmpty() || on) return
                Log.i("JettWake", "Waking for $channel")
                try {
                    // Belt and braces: a screen wake lock also turns the box on where allowed.
                    @Suppress("DEPRECATION")
                    ctx.getSystemService(PowerManager::class.java).newWakeLock(
                        PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "jett:wake").acquire(20_000)
                } catch (e: Exception) { Log.w("JettWake", "Wake lock refused", e) }
                try {
                    ctx.startActivity(Intent(ctx, WakeUpActivity::class.java).putExtra("channel", channel)
                        .putExtra("volume", intent.getIntExtra("volume", -1))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
                } catch (e: Exception) { Log.w("JettWake", "Couldn't wake", e) }
            }
            WakeUp.ACTION_SWITCH -> {
                // e.g. 9am: from the morning nature video to CNBC, only if still on from the wake-up.
                val woke = prefs.getLong("wokeAt", 0)
                val ch = intent.getStringExtra("channel").orEmpty()
                if (on && ch.isNotEmpty() && woke > 0 && System.currentTimeMillis() - woke < 12 * 3600_000L) {
                    Log.i("JettWake", "Switching to $ch")
                    val app = ctx.applicationContext
                    // New volume first (if one is set), then the new channel.
                    WakeUp.setVolume(app, intent.getIntExtra("volume", -1)) {
                        WakeUp.openAndPlay(app, ch)
                    }
                }
            }
            WakeUp.ACTION_NIGHT -> {
                // Bedtime: if the TV is still on, a 5-minute sleep timer with a heads-up on screen.
                if (on && Sleep.minutesLeft(ctx) !in 1..5) {
                    Sleep.set(ctx, 5)
                    Handler(Looper.getMainLooper()).post { Router.toast(ctx.applicationContext, "Bedtime — the TV turns off in 5 minutes") }
                }
            }
            WakeUp.ACTION_OFF -> {
                // Only if it's still on from this morning's wake-up (within the last 12 hours).
                val woke = prefs.getLong("wokeAt", 0)
                if (on && woke > 0 && System.currentTimeMillis() - woke < 12 * 3600_000L) {
                    prefs.edit().remove("wokeAt").apply()
                    Sleep.sleepNow(ctx)
                }
            }
        }
    }
}

/**
 * Invisible screen that turns the box (and over HDMI-CEC the TV) on. The rest (volume, then the
 * channel) runs app-wide, so the home screen coming back on wake can't cancel it.
 */
class WakeUpActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true); setTurnScreenOn(true)
        WakeUp.runSequence(applicationContext, intent.getStringExtra("channel").orEmpty(), intent.getIntExtra("volume", -1),
            intent.getBooleanExtra("scheduled", true))
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1500)
    }
}

/**
 * Test from a computer: `adb shell am broadcast -a com.jett.launcher.TEST_WAKE -n com.jett.launcher/.WakeTestReceiver --ei delay 90`
 * then turn the TV off; it should come back on CNBC after [delay] seconds. Only adb can send it.
 */
class WakeTestReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val delay = intent.getIntExtra("delay", 90).coerceIn(20, 3600)
        val ch = intent.getStringExtra("channel") ?: "CNBC"
        val at = ZonedDateTime.now(Config.ZONE).plusSeconds(delay.toLong())
        WakeUp.armTest(ctx, at, ch, intent.getIntExtra("volume", 10))
        Log.i("JettWake", "Test wake at $at → $ch")
    }
}

/**
 * Test the volume alone, from a computer, with the TV on:
 * `adb shell am broadcast -a com.jett.launcher.TEST_VOLUME -n com.jett.launcher/.VolumeTestReceiver --ei volume 10`
 * The first time, the TV asks to allow "Jett Home": choose Allow.
 */
class VolumeTestReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val v = intent.getIntExtra("volume", 10)
        val app = ctx.applicationContext
        Thread { Log.i("JettSamsung", "Test volume $v: " + SamsungTv.setVolume(app, v)) }.start()
    }
}
