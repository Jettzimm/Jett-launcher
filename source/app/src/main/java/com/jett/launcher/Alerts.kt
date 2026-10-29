package com.jett.launcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Close-game alerts: while the TV is on, if a favourite team (Dolphins, Michigan, Texas,
 * Florida Panthers) or a top-10 college team is in a close game late, a banner slides in over
 * whatever is playing. OK = watch it, anything else = dismiss. Once per game.
 */
object Alerts {
    const val ACTION = "com.jett.launcher.ALERT_CHECK"
    private val LEAGUES = listOf("nfl", "cfb", "nhl")

    /** Worth watching for: a favourite team, or (college) a top-10 team on either side. */
    fun candidate(g: Game) = g.pinned || (g.league.key == "cfb" &&
        ((g.awayRank ?: 99) <= Config.ALERT_TOP_RANK || (g.homeRank ?: 99) <= Config.ALERT_TOP_RANK))

    /** Close and late: football within 8 in the 4th/OT; hockey within 1 in the 3rd/OT. */
    fun isClose(g: Game): Boolean {
        if (!g.isLive) return false
        val diff = abs((g.awayScore.toIntOrNull() ?: return false) - (g.homeScore.toIntOrNull() ?: return false))
        return when (g.league.key) {
            "nhl" -> g.period >= 3 && diff <= 1
            else -> g.period >= 4 && diff <= 8
        }
    }

    fun schedule(ctx: Context, minutes: Long) {
        val pi = PendingIntent.getBroadcast(ctx, 12, Intent(ctx, AlertReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        ctx.getSystemService(AlarmManager::class.java)
            .setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + minutes * 60_000L, pi)
    }

    /** One check. Network: run off the main thread. Returns minutes until the next check (null = stop). */
    fun check(ctx: Context): Long? {
        if (!ctx.getSystemService(PowerManager::class.java).isInteractive) return null   // TV off: stop until it's on
        val games = LEAGUES.flatMap { key ->
            val lg = Config.LEAGUES.firstOrNull { it.key == key } ?: return@flatMap emptyList()
            try { Sports.fetchLeague(lg) } catch (e: Exception) { emptyList() }
        }.filter { candidate(it) && it.seasonType != 1 }
        val prefs = ctx.getSharedPreferences("alerts", Context.MODE_PRIVATE)
        games.filter { isClose(it) && !prefs.getBoolean("done_${it.id}", false) }.minByOrNull { it.start }?.let { g ->
            prefs.edit().putBoolean("done_${g.id}", true).apply()
            Handler(Looper.getMainLooper()).post { Banner.show(ctx.applicationContext, g) }
        }
        val now = java.time.Instant.now()
        return when {
            games.any { it.isLive } -> 2L
            games.any { it.state == "pre" && it.start.isBefore(now.plusSeconds(3600)) } -> 10L
            else -> 30L
        }
    }
}

class AlertReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Alerts.ACTION) return
        val pending = goAsync()
        Thread {
            try { Alerts.check(ctx)?.let { Alerts.schedule(ctx, it) } } catch (e: Exception) { Alerts.schedule(ctx, 10) }
            finally { pending.finish() }
        }.start()
    }
}

/** The banner itself: top-right, over any app, 15 seconds. */
object Banner {
    private var view: FrameLayout? = null

    fun show(ctx: Context, g: Game) {
        if (view != null) return
        val wm = ctx.getSystemService(WindowManager::class.java)
        val dp = ctx.resources.displayMetrics.density * Config.UI_SCALE
        fun px(v: Int) = (v * dp).toInt()
        val handler = Handler(Looper.getMainLooper())
        val root = object : FrameLayout(ctx) {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                if (e.action != KeyEvent.ACTION_UP) return true
                hide(wm)
                if (e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER) Router.openGame(ctx, g)
                return true
            }
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(16), px(24), px(16))
            background = GradientDrawable().apply { cornerRadius = 18f * dp; setColor(0xEE15181E.toInt()) }
        }
        card.addView(TextView(ctx).apply {
            text = "CLOSE GAME"; textSize = 10f; letterSpacing = 0.14f; setTextColor(0xFFFFC857.toInt())
        })
        card.addView(TextView(ctx).apply {
            text = "${g.awayAbbr} ${g.awayScore}   ${g.homeAbbr} ${g.homeScore}"
            textSize = 21f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, px(4), 0, 0)
        })
        card.addView(TextView(ctx).apply {
            text = "${g.detail}   ·   OK to watch"; textSize = 12f; setTextColor(0xCCFFFFFF.toInt())
            setPadding(0, px(4), 0, 0)
        })
        root.addView(card)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, 0, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = px(48); y = px(48) }
        try {
            wm.addView(root, lp); view = root
            root.isFocusable = true; root.requestFocus()
            root.alpha = 0f; root.translationX = px(60).toFloat()
            root.animate().alpha(1f).translationX(0f).setDuration(350).start()
            handler.postDelayed({ hide(wm) }, 15_000)
        } catch (e: Exception) { view = null }
    }

    private fun hide(wm: WindowManager) {
        val v = view ?: return
        view = null
        try { wm.removeView(v) } catch (e: Exception) { }
    }
}
