package dev.anriudh.spotifyduo

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager

const val ACTION_REFRESH = "dev.anriudh.spotifyduo.ACTION_REFRESH"
const val ACTION_ALARM = "dev.anriudh.spotifyduo.ACTION_ALARM"

class PlaybackWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(ctx, mgr, it) }
        RefreshScheduler.ensureScheduled(ctx)
    }

    override fun onEnabled(ctx: Context) = RefreshScheduler.ensureScheduled(ctx)

    /** Last widget removed: stop doing background work entirely. */
    override fun onDisabled(ctx: Context) = RefreshScheduler.cancel(ctx)

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        when (intent.action) {
            ACTION_REFRESH -> {
                ctx.pendingForcedRefresh = true
                invalidateData(ctx)
            }

            ACTION_ALARM -> {
                RefreshScheduler.schedule(ctx)
                // The widget is only ever seen with the screen on, so polling
                // while it is off is pure battery and data cost for nothing.
                val power = ctx.getSystemService(PowerManager::class.java)
                if (power?.isInteractive == true) invalidateData(ctx)
            }
        }
    }

    companion object {
        fun render(ctx: Context, mgr: AppWidgetManager, widgetId: Int) {
            val views = android.widget.RemoteViews(ctx.packageName, R.layout.widget_root)

            val adapter = Intent(ctx, WidgetStackService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                // Distinct data per widget, otherwise instances share one factory.
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.stack, adapter)
            views.setEmptyView(R.id.stack, R.id.empty)

            views.setOnClickPendingIntent(R.id.refresh, broadcast(ctx, ACTION_REFRESH))
            views.setPendingIntentTemplate(R.id.stack, openTemplate(ctx))

            mgr.updateAppWidget(widgetId, views)
        }

        /** Re-runs the factory, which is where the network fetch happens. */
        fun invalidateData(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = widgetIds(ctx, mgr)
            if (ids.isEmpty()) return
            ids.forEach { render(ctx, mgr, it) }
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.stack)
        }

        fun widgetIds(ctx: Context, mgr: AppWidgetManager): IntArray =
            mgr.getAppWidgetIds(ComponentName(ctx, PlaybackWidget::class.java))

        private fun broadcast(ctx: Context, action: String): PendingIntent =
            PendingIntent.getBroadcast(
                ctx,
                action.hashCode(),
                Intent(ctx, PlaybackWidget::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        /**
         * Card taps fill in a Spotify URI against this template. It must be
         * mutable for the fill-in to apply, and Android 14+ rejects mutable
         * PendingIntents built from implicit Intents -- hence the explicit
         * target, which forwards to Spotify itself.
         */
        private fun openTemplate(ctx: Context): PendingIntent =
            PendingIntent.getActivity(
                ctx,
                1,
                Intent(ctx, OpenTrackActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
    }
}
