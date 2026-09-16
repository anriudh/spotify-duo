package dev.anriudh.spotifyduo

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Drives the widget's refresh with a self-rescheduling alarm chain.
 *
 * setRepeating is inexact and gets batched, so each fire schedules the next.
 * Doze throttles these to roughly 9 minutes, but Doze only engages with the
 * screen off and the phone stationary -- exactly when the widget is invisible
 * and the poll is skipped anyway.
 */
object RefreshScheduler {

    const val IDLE_INTERVAL_MS = 4 * 60 * 1000L

    /**
     * Cadence while someone is actually listening. Stopping playback or
     * skipping to another track cannot be predicted or detected -- there is no
     * signal to listen for -- so they can only be discovered by asking. This is
     * the worst-case delay before either is noticed.
     */
    private const val PLAYING_INTERVAL_MS = 45_000L

    /** Floor, so a slightly-off duration cannot spin the alarm in a tight loop. */
    private const val MIN_DELAY_MS = 15_000L

    fun ensureScheduled(ctx: Context) {
        if (hasWidgets(ctx)) scheduleIn(ctx, IDLE_INTERVAL_MS) else cancel(ctx)
    }

    /**
     * Picks the sooner of two wake-ups:
     *
     *  - the moment the current track is due to end, which is predictable from
     *    its duration, so the next song appears as it starts;
     *  - a steady cadence while playing, which is the only way to notice the
     *    unpredictable events -- a stop, or a skip to a different track.
     *
     * Nobody listening means neither applies, so it falls back to a lazy
     * interval rather than waking for nothing.
     */
    fun scheduleNext(ctx: Context, state: DuoState?) {
        val now = state?.now() ?: System.currentTimeMillis()
        val playing = state?.users?.filter { it.isPlaying && it.durationMs > 0L }.orEmpty()

        val delay = if (playing.isEmpty()) {
            IDLE_INTERVAL_MS
        } else {
            val untilSoonestEnd = playing.minOf { it.durationMs - it.elapsedMsAt(now) + 2_000L }
            minOf(untilSoonestEnd, PLAYING_INTERVAL_MS)
        }

        scheduleIn(ctx, delay.coerceIn(MIN_DELAY_MS, IDLE_INTERVAL_MS))
    }

    fun scheduleIn(ctx: Context, delayMs: Long) {
        if (!hasWidgets(ctx)) return
        val alarms = ctx.getSystemService(AlarmManager::class.java) ?: return
        val at = System.currentTimeMillis() + delayMs
        val pending = alarmIntent(ctx)

        // SCHEDULE_EXACT_ALARM is denied by default on Android 13+. Degrade to an
        // inexact alarm rather than throwing; a little drift is harmless.
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
