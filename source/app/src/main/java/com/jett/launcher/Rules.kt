package com.jett.launcher

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.KeyEvent
import android.widget.LinearLayout
import android.widget.TextView
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/** Something the TV should be showing right now. */
data class AutoTarget(val id: String, val title: String, val channel: String)

object Rules {

    private val ZONE: ZoneId = Config.ZONE
    private val DATE = DateTimeFormatter.BASIC_ISO_DATE

    /** What should be on right now, or null. Does network calls — call off the main thread. */
    fun current(now: ZonedDateTime = ZonedDateTime.now(ZONE)): AutoTarget? {
        for (r in Config.POWER_ON_RULES) {
            if (now.dayOfWeek == r.day && now.hour >= r.startHour && now.hour < r.endHour) {
                // GameDay only on college-football Saturdays (not in April).
                if (r.id == "gameday" && Morning.mode(now) !== Config.COLLEGE_SATURDAY) continue
                return AutoTarget(r.id, r.title, r.channel)
            }
        }
        return sundayTarget(now)
    }

    /** RedZone while afternoon games are on, then the primetime game's channel. */
    fun sundayTarget(now: ZonedDateTime): AutoTarget? {
        if (now.dayOfWeek != Config.NFL_SUNDAY) return null
        if (now.hour < Config.REDZONE_START_HOUR || now.hour >= Config.PRIMETIME_CUTOFF_HOUR) return null
        val nfl = Config.LEAGUES.first { it.key == "nfl" }
        val games = try { Sports.fetchLeague(nfl, DATE.format(now.toLocalDate())) } catch (e: Exception) { return null }
        val today = games.filter { it.start.atZone(ZONE).toLocalDate() == now.toLocalDate() }
        val afternoon = today.filter { it.start.atZone(ZONE).hour in 12..17 }
        val primetime = today.filter { it.start.atZone(ZONE).hour >= 18 }.minByOrNull { it.start }

        // Playoff / Super Bowl Sundays (no RedZone): follow each game on its own channel,
        // whatever time it kicks off (incl. 6:30pm playoff and Super Bowl starts).
        if (today.any { it.seasonType == 3 }) {
            // Live now, or kicking off within 90 minutes (don't switch hours early).
            val soon = now.toInstant().plusSeconds(90 * 60L)
            val g = today.filter { it.state == "in" || (it.state == "pre" && it.start.isBefore(soon)) }
                .minByOrNull { it.start } ?: return null
            val ch = Router.normalize(g.nationalNetworks.firstOrNull() ?: "CBS")
            return AutoTarget("playoff_${g.id}", "${g.awayAbbr} @ ${g.homeAbbr} on $ch", ch)
        }
        val liveAfternoon = afternoon.filter { it.state != "post" }
        if (liveAfternoon.isNotEmpty()) return AutoTarget("redzone", "NFL RedZone", Config.REDZONE_CHANNEL)
        if (primetime != null && primetime.state != "post") {
            val channel = Router.normalize(primetime.nationalNetworks.firstOrNull() ?: "NBC")
            return AutoTarget("primetime", "${primetime.awayAbbr} @ ${primetime.homeAbbr} on $channel", channel)
        }
        return null
    }

    // ------------------------------------------------------------- alarms
    const val ACTION_SUNDAY_CHECK = "com.jett.launcher.SUNDAY_CHECK"

    /** Next Sunday 1pm (exact). Called on launcher start and boot. */
    fun scheduleNextSunday(ctx: Context) {
        val now = ZonedDateTime.now(ZONE)
        var next = now.with(TemporalAdjusters.nextOrSame(Config.NFL_SUNDAY))
            .withHour(Config.REDZONE_START_HOUR).withMinute(0).withSecond(0).withNano(0)
        if (!next.isAfter(now)) {
            // Already past 1pm Sunday: if still in the window, poll; else next week.
            if (now.dayOfWeek == Config.NFL_SUNDAY && now.hour < Config.PRIMETIME_CUTOFF_HOUR) {
                schedule(ctx, now.plusMinutes(5)); return
            }
            next = next.plusWeeks(1)
        }
        schedule(ctx, next)
    }

    fun schedule(ctx: Context, at: ZonedDateTime) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, RuleReceiver::class.java).setAction(ACTION_SUNDAY_CHECK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val t = at.toInstant().toEpochMilli()
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
        }
    }

    // Remembers what we last switched to today so we never switch to the same thing twice.
    fun alreadyDone(ctx: Context, target: AutoTarget, sessionKey: String): Boolean {
        val prefs = ctx.getSharedPreferences("rules", Context.MODE_PRIVATE)
        return prefs.getString("done_${target.id}", null) == sessionKey
    }

    fun markDone(ctx: Context, target: AutoTarget, sessionKey: String) {
        ctx.getSharedPreferences("rules", Context.MODE_PRIVATE).edit()
            .putString("done_${target.id}", sessionKey).apply()
    }

    fun today() = LocalDate.now(ZONE).toString()

    fun showCountdown(ctx: Context, target: AutoTarget) {
        ctx.startActivity(
            Intent(ctx, CountdownActivity::class.java)
                .putExtra("title", target.title)
                .putExtra("channel", target.channel)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** Fires at Sunday 1pm, then every 5 minutes until the primetime switch is done. */
class RuleReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Rules.ACTION_SUNDAY_CHECK && intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        Thread {
            try {
                val now = ZonedDateTime.now(Config.ZONE)
                val target = Rules.sundayTarget(now)
                val awake = ctx.getSystemService(PowerManager::class.java).isInteractive
                if (target != null && awake && !Rules.alreadyDone(ctx, target, Rules.today())) {
                    Rules.markDone(ctx, target, Rules.today())
                    Handler(Looper.getMainLooper()).post { Rules.showCountdown(ctx, target) }
                }
                // Keep polling during the Sunday window (cheap: one small request every 5 min).
                if (now.dayOfWeek == Config.NFL_SUNDAY && now.hour >= Config.REDZONE_START_HOUR &&
                    now.hour < Config.PRIMETIME_CUTOFF_HOUR
                ) {
                    Rules.schedule(ctx, now.plusMinutes(5))
                } else {
                    Rules.scheduleNextSunday(ctx)
                }
            } finally { pending.finish() }
        }.start()
    }
}

/** "Switching to RedZone in 10… press Back to stay." */
class CountdownActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var remaining = Config.COUNTDOWN_SECONDS
    private lateinit var countText: TextView
    private var channel = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        channel = intent.getStringExtra("channel").orEmpty()
        val title = intent.getStringExtra("title").orEmpty()
        val dp = resources.displayMetrics.density

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((48 * dp).toInt(), (32 * dp).toInt(), (48 * dp).toInt(), (32 * dp).toInt())
            background = GradientDrawable().apply { setColor(0xE6121418.toInt()); cornerRadius = 28 * dp }
        }
        card.addView(TextView(this).apply {
            text = "Switching to $title"; setTextColor(Color.WHITE); textSize = 30f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); gravity = Gravity.CENTER
        })
        countText = TextView(this).apply {
            setTextColor(0xFFBFC6D4.toInt()); textSize = 20f; gravity = Gravity.CENTER
            setPadding(0, (12 * dp).toInt(), 0, 0)
        }
        card.addView(countText)
        card.addView(TextView(this).apply {
            text = "OK  go now     ·     BACK  stay here"
            setTextColor(0x99FFFFFF.toInt()); textSize = 15f; gravity = Gravity.CENTER
            setPadding(0, (18 * dp).toInt(), 0, 0)
        })
        val root = LinearLayout(this).apply { gravity = Gravity.CENTER; addView(card) }
        setContentView(root)
        tick()
    }

    private fun tick() {
        if (remaining <= 0) { go(); return }
        countText.text = "in $remaining…"
        remaining--
        handler.postDelayed({ tick() }, 1000)
    }

    private fun go() {
        handler.removeCallbacksAndMessages(null)
        Router.openChannel(this, channel)
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { go(); return true }
            KeyEvent.KEYCODE_BACK -> { handler.removeCallbacksAndMessages(null); finish(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() {
        super.onStop()
        // Home pressed during the countdown: cancel, don't switch from the background.
        if (!isFinishing) { handler.removeCallbacksAndMessages(null); finish() }
    }

    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
}

/**
 * Wakes the app at the start of each power-on window (e.g. Saturday 9:00) so the TV-on
 * listener is definitely running, even if Android had closed the app in the background.
 * Shows nothing itself.
 */
object WindowAlarm {
    fun scheduleNext(ctx: Context) {
        val now = ZonedDateTime.now(Config.ZONE)
        val next = Config.POWER_ON_RULES.map { r ->
            var t = now.with(java.time.temporal.TemporalAdjusters.nextOrSame(r.day))
                .withHour(r.startHour).withMinute(0).withSecond(0).withNano(0)
            if (!t.isAfter(now)) t = t.plusWeeks(1)
            t
        }.minOrNull() ?: return
        val pi = android.app.PendingIntent.getBroadcast(ctx, 11, Intent(ctx, WindowAlarmReceiver::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        ctx.getSystemService(android.app.AlarmManager::class.java)
            .setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), pi)
    }
}

class WindowAlarmReceiver : BroadcastReceiver() {
    // Receiving this starts the app process (JettApp registers the TV-on listener); then re-arm.
    override fun onReceive(ctx: Context, intent: Intent) { WindowAlarm.scheduleNext(ctx) }
}
