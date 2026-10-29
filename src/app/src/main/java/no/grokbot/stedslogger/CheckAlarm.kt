package no.grokbot.stedslogger

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Wakes the phone for each periodic check with AlarmManager (RTC_WAKEUP, allowed while idle), so
 * checks keep coming in Doze / deep sleep with the screen off. A Handler timer alone is uptime-based
 * and stalls in deep sleep, and some phones ignore the app's wake lock.
 *
 * Exact alarms (setExactAndAllowWhileIdle) are used when allowed (USE_EXACT_ALARM on Android 13+,
 * SCHEDULE_EXACT_ALARM before); otherwise setAndAllowWhileIdle (may be a few minutes late in Doze).
 */
object CheckAlarm {
    private const val TAG = "LocationLogger"
    const val ACTION_TICK = "no.grokbot.stedslogger.ALARM_TICK"
    private const val REQ = 42

    private fun pending(ctx: Context, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            ctx, REQ, Intent(ctx, CheckAlarmReceiver::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_IMMUTABLE or flags
        )

    fun canExact(ctx: Context): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
    }

    /** Schedules (replacing any earlier) the next check alarm at wall-clock time [atMs]. */
    fun schedule(ctx: Context, atMs: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(ctx, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val at = atMs.coerceAtLeast(System.currentTimeMillis() + 1_000L)
        try {
            if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            EventLog.error(ctx, "Eksakt alarm nektet, bruker unøyaktig", e)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        pending(ctx, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
    }
}

/** Receives the check alarm and hands it to the running service (or restarts the service if it was killed). */
class CheckAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CheckAlarm.ACTION_TICK) return
        if (!LogStore.prefs(context).getBoolean(LogStore.KEY_RUNNING, false)) return
        val svc = LocationService.instance
        if (svc != null) {
            svc.onAlarm()
        } else {
            // Service was killed: an exact allow-while-idle alarm may start a foreground service
            EventLog.add(context, EventLog.SERVICE, "Alarm: tjenesten var stoppet av Android – starter den igjen")
            try { LocationService.start(context, LocationService.ACTION_ALARM) }
            catch (e: Exception) { EventLog.error(context, "Kunne ikke starte tjenesten fra alarmen", e) }
        }
    }
}
