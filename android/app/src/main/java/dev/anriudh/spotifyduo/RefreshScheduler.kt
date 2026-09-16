package dev.anriudh.spotifyduo

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Drives the widget's periodic refresh with a self-rescheduling alarm chain.
 *
 * setRepeating is inexact and gets batched, so each fire schedules the next one
 * instead. Doze throttles these to roughly 9 minutes, but Doze only engages
 * with the screen off and the phone stationary -- exactly when the widget is
 * invisible and the poll is skipped anyway.
 */
object RefreshScheduler {

    private const val INTERVAL_MS = 4 * 60 * 1000L

    fun ensureScheduled(ctx: Context) {
        if (hasWidgets(ctx)) schedule(ctx) else cancel(ctx)
    }

    fun schedule(ctx: Context) {
        if (!hasWidgets(ctx)) return
        val alarms = ctx.getSystemService(AlarmManager::class.java) ?: return
        val at = System.currentTimeMillis() + INTERVAL_MS
        val pending = alarmIntent(ctx)

        // SCHEDULE_EXACT_ALARM is denied by default on Android 13+. Degrade to an
        // inexact alarm rather than throwing; a few minutes of drift is harmless.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarms.canScheduleExactAlarms()) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } else {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    fun cancel(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(ctx))
    }

    private fun hasWidgets(ctx: Context): Boolean =
        PlaybackWidget.widgetIds(ctx, AppWidgetManager.getInstance(ctx)).isNotEmpty()

    private fun alarmIntent(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx,
            ACTION_ALARM.hashCode(),
            Intent(ctx, PlaybackWidget::class.java).setAction(ACTION_ALARM),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
