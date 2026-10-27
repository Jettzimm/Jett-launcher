package com.jett.launcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Bedroom-mode sleep timer: puts the box to sleep, and CEC turns the Samsung off. */
object Sleep {
    private const val PREFS = "sleep"
    private const val ACTION = "com.jett.launcher.SLEEP_NOW"

    fun endsAt(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("endsAt", 0).takeIf { it > System.currentTimeMillis() } ?: 0

    fun minutesLeft(ctx: Context): Int {
        val e = endsAt(ctx); return if (e == 0L) 0 else ((e - System.currentTimeMillis()) / 60_000 + 1).toInt()
    }

    /** Cycles 30 → 60 → 90 → off. Returns minutes now set (0 = off). */
    fun cycle(ctx: Context): Int {
        val left = minutesLeft(ctx)
        val next = Config.SLEEP_STEPS_MIN.firstOrNull { it > left + 1 } ?: 0
        set(ctx, next); return next
    }

    fun set(ctx: Context, minutes: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(ctx, 9, Intent(ctx, SleepReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.cancel(pi)
        val end = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("endsAt", end).apply()
        if (minutes > 0) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, pi)
    }

    /** After a reboot, re-arm a timer that was still running (alarms don't survive reboots). */
    fun rearm(ctx: Context) {
        val left = minutesLeft(ctx)
        if (left > 0) set(ctx, left) else ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("endsAt", 0).apply()
    }

    fun sleepNow(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("endsAt", 0).apply()
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        if (dpm.isAdminActive(ComponentName(ctx, SleepAdmin::class.java))) dpm.lockNow()
    }
}

class SleepReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) { Sleep.sleepNow(ctx) }
}

/** Undo step: `adb shell am broadcast -a com.jett.launcher.RELEASE_ADMIN -n com.jett.launcher/.AdminReleaseReceiver` */
class AdminReleaseReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        ctx.getSystemService(DevicePolicyManager::class.java).removeActiveAdmin(ComponentName(ctx, SleepAdmin::class.java))
    }
}

/** Lets the app turn the box off. Switched on once during install (see README). */
class SleepAdmin : DeviceAdminReceiver()
