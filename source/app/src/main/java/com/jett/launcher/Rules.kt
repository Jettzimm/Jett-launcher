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
        return nflTarget(now)
    }

    /** Today's NFL games (Eastern date), or null if ESPN couldn't be reached. */
    fun nflGamesToday(now: ZonedDateTime): List<Game>? {
        val nfl = Config.LEAGUES.first { it.key == "nfl" }
        val games = try { Sports.fetchLeague(nfl, DATE.format(now.toLocalDate())) } catch (e: Exception) { return null }
        return games.filter { it.start.atZone(ZONE).toLocalDate() == now.toLocalDate() }
    }

    private fun isNight(g: Game) = g.start.atZone(ZONE).hour >= Config.NIGHT_GAME_HOUR
    private fun isSundayAfternoon(g: Game) =
        g.start.atZone(ZONE).dayOfWeek == Config.NFL_SUNDAY && g.start.atZone(ZONE).hour in 12..17

    /** Games today that can trigger a switch: playoffs, Sunday afternoon (RedZone), night games. */
    fun watched(today: List<Game>) = today.filter { it.seasonType == 3 || isNight(it) || isSundayAfternoon(it) }

    private fun channelOf(g: Game, default: String) = Router.normalize(g.nationalNetworks.firstOrNull() ?: default)

    /**
     * What the NFL rules want on right now, or null.
     * Playoffs: each game on its own channel. Sundays: RedZone, then the night game.
     * Thursday / Monday / any other night game: its channel from just before kickoff.
     */
    fun nflTarget(now: ZonedDateTime, gamesToday: List<Game>? = null): AutoTarget? {
        val today = gamesToday ?: nflGamesToday(now) ?: return null
        val nowI = now.toInstant()
        fun startsWithin(g: Game, minutes: Long) = g.state == "in" || (g.state == "pre" && g.start.isBefore(nowI.plusSeconds(minutes * 60)))

        // Playoff days (no RedZone): follow each game, earliest live one first.
        if (today.any { it.seasonType == 3 }) {
            val g = today.filter { it.seasonType == 3 && startsWithin(it, Config.PLAYOFF_SWITCH_MINUTES_BEFORE) }
                .minByOrNull { it.start } ?: return null
            val ch = channelOf(g, "CBS")
            return AutoTarget("playoff_${g.id}", "${g.awayAbbr} @ ${g.homeAbbr} on $ch", ch)
        }
        // Sunday afternoon: RedZone while any afternoon game is still on.
        if (now.dayOfWeek == Config.NFL_SUNDAY && now.hour >= Config.REDZONE_START_HOUR &&
            today.any { isSundayAfternoon(it) && it.state != "post" }
        ) return AutoTarget("redzone", "NFL RedZone", Config.REDZONE_CHANNEL)

        // Night game. Sundays switch as soon as RedZone ends (the pregame show); other nights just
        // before kickoff. Same id all night, so a Monday doubleheader doesn't yank you to game 2.
        val night = today.filter { isNight(it) && it.state != "post" }
        val g = (if (now.dayOfWeek == Config.NFL_SUNDAY && now.hour >= Config.REDZONE_START_HOUR) night
                 else night.filter { startsWithin(it, Config.NIGHT_SWITCH_MINUTES_BEFORE) })
            .minByOrNull { it.start } ?: return null
        val ch = channelOf(g, "NBC")
        return AutoTarget("night", "${g.awayAbbr} @ ${g.homeAbbr} on $ch", ch)
    }

    // ------------------------------------------------------------- alarms
    const val ACTION_NFL_CHECK = "com.jett.launcher.NFL_CHECK"

    /** Re-plans the NFL checks (called on launcher start and boot): one check in a minute. */
    fun scheduleNflCheck(ctx: Context) = schedule(ctx, ZonedDateTime.now(ZONE).plusMinutes(1))

    /** Tomorrow's (or today's, if still ahead) daily check. */
    fun scheduleDailyCheck(ctx: Context, now: ZonedDateTime) {
        var next = now.withHour(Config.NFL_DAILY_CHECK_HOUR).withMinute(0).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        schedule(ctx, next)
    }

    fun schedule(ctx: Context, at: ZonedDateTime) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, RuleReceiver::class.java).setAction(ACTION_NFL_CHECK),
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

/**
 * NFL checks: once a day at noon; on days with watched games, every 5 minutes from shortly
 * before the first kickoff until the last watched game ends. Off days cost one small request.
 */
class RuleReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Rules.ACTION_NFL_CHECK && intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        Thread {
            val now = ZonedDateTime.now(Config.ZONE)
            try {
                val today = Rules.nflGamesToday(now)
                if (today == null) {   // offline: try again soon
                    Rules.schedule(ctx, now.plusMinutes(10)); return@Thread
                }
                val target = Rules.nflTarget(now, today)
                val awake = ctx.getSystemService(PowerManager::class.java).isInteractive
                if (target != null && awake && !Rules.alreadyDone(ctx, target, Rules.today())) {
                    Rules.markDone(ctx, target, Rules.today())
                    Handler(Looper.getMainLooper()).post { Rules.showCountdown(ctx, target) }
                }
                val left = Rules.watched(today).filter { it.state != "post" }
                if (left.isEmpty()) { Rules.scheduleDailyCheck(ctx, now); return@Thread }
                // Poll every 5 min while something's live; otherwise wake ~40 min before the next kickoff.
                val soon = now.plusMinutes(5)
                val nextKick = left.filter { it.state == "pre" }.minOfOrNull { it.start }?.atZone(Config.ZONE)?.minusMinutes(40)
                val live = left.any { it.state == "in" } || (now.dayOfWeek == Config.NFL_SUNDAY && now.hour >= Config.REDZONE_START_HOUR)
                Rules.schedule(ctx, if (live || nextKick == null || nextKick.isBefore(soon)) soon else nextKick)
            } catch (e: Exception) {
                Rules.schedule(ctx, now.plusMinutes(10))
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
