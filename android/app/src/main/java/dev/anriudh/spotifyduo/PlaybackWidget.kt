package dev.anriudh.spotifyduo

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.text.format.DateUtils
import android.view.View
import android.widget.RemoteViews

const val ACTION_REFRESH = "dev.anriudh.spotifyduo.ACTION_REFRESH"
const val ACTION_ALARM = "dev.anriudh.spotifyduo.ACTION_ALARM"
const val ACTION_TOGGLE = "dev.anriudh.spotifyduo.ACTION_TOGGLE"

class PlaybackWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        RefreshScheduler.ensureScheduled(ctx)
        runOffMainThread(ctx, hitNetwork = true, forced = false)
    }

    override fun onEnabled(ctx: Context) = RefreshScheduler.ensureScheduled(ctx)

    /** Last widget removed: stop doing background work entirely. */
    override fun onDisabled(ctx: Context) = RefreshScheduler.cancel(ctx)

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        when (intent.action) {
            ACTION_TOGGLE -> {
                ctx.showingSelf = !ctx.showingSelf
                // Still off the main thread: the other person's album art may
                // not be cached yet, and fetching it is a network call.
                runOffMainThread(ctx, hitNetwork = false, forced = false)
            }

            ACTION_REFRESH -> runOffMainThread(ctx, hitNetwork = true, forced = true)

            ACTION_ALARM -> {
                // Keeps the chain alive if the render below fails; a successful
                // render replaces this with playback-aware timing.
                RefreshScheduler.ensureScheduled(ctx)
                // Deliberately runs regardless of screen state: skipping while
                // the screen was off left a finished track on screen the moment
                // it was next looked at, and Doze already throttles the idle case.
                runOffMainThread(ctx, hitNetwork = true, forced = false)
            }
        }
    }

    /**
     * onReceive runs on the main thread, but rendering can touch the network --
     * both the state fetch and, separately, downloading album art that is not
     * cached yet. So every render path goes through goAsync and a worker
     * thread. The system allows roughly ten seconds, well clear of our timeouts.
     */
    private fun runOffMainThread(ctx: Context, hitNetwork: Boolean, forced: Boolean) {
        if (widgetIds(ctx, AppWidgetManager.getInstance(ctx)).isEmpty()) return
        val pending = goAsync()
        Thread {
            try {
                // Renders exactly once: every render decodes album art and
                // re-arms the alarm, so drawing cached state first and then
                // again after the fetch doubled that work on every tick.
                val state = if (hitNetwork) {
                    StateRepository.refresh(ctx, forced) ?: StateRepository.cached(ctx)
                } else {
                    StateRepository.cached(ctx)
                }
                renderAll(ctx, state)
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        fun widgetIds(ctx: Context, mgr: AppWidgetManager): IntArray =
            mgr.getAppWidgetIds(ComponentName(ctx, PlaybackWidget::class.java))

        fun renderAll(ctx: Context, state: DuoState?) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val views = buildViews(ctx, state)
            widgetIds(ctx, mgr).forEach { mgr.updateAppWidget(it, views) }
            RefreshScheduler.scheduleNext(ctx, state)
        }

        private fun buildViews(ctx: Context, state: DuoState?): RemoteViews {
            val views = RemoteViews(ctx.packageName, R.layout.widget_card)
            views.setOnClickPendingIntent(R.id.refresh, broadcast(ctx, ACTION_REFRESH))
            views.setOnClickPendingIntent(R.id.who, broadcast(ctx, ACTION_TOGGLE))

            val now = state?.now() ?: System.currentTimeMillis()
            val user = pickUser(ctx, state)
            if (user == null) {
                views.setTextViewText(R.id.who, "—")
                views.setInt(R.id.who, "setBackgroundResource", R.drawable.chip_blue)
                views.setTextViewText(R.id.track, "Open the app to set up")
                views.setTextViewText(R.id.artist, "")
                views.setTextViewText(R.id.status, ctx.lastError ?: "")
                hideProgress(views)
                views.setInt(R.id.tint, "setBackgroundColor", 0xFF1F1F23.toInt())
                views.setViewVisibility(R.id.art, View.GONE)
                return views
            }

            val art = ArtCache.load(ctx, user.albumArtUrl, desaturate = !user.isPlaying)
            views.setInt(R.id.tint, "setBackgroundColor", art.accent)
            art.bitmap?.let { views.setImageViewBitmap(R.id.art, it) }
            views.setViewVisibility(R.id.art, if (art.bitmap != null) View.VISIBLE else View.GONE)

            // Own card says "you"; the custom name only ever shows on the partner's phone.
            views.setTextViewText(R.id.who, if (user.id == ctx.selfId) "you" else user.displayName)
            views.setInt(R.id.who, "setBackgroundResource", Person.chipFor(user.id))
            val bar = Person.barFor(user.id)
            views.setViewVisibility(Person.otherBar(bar), View.GONE)

            when {
                user.needsLogin -> {
                    views.setTextViewText(R.id.track, "Not signed in")
                    views.setTextViewText(R.id.artist, "Open the app to link Spotify")
                    hideProgress(views)
                }

                !user.hasTrack -> {
                    views.setTextViewText(R.id.track, "Nothing yet")
                    views.setTextViewText(R.id.artist, "")
                    hideProgress(views)
                }

                else -> {
                    views.setTextViewText(R.id.track, user.trackName)
                    views.setTextViewText(R.id.artist, user.artistName ?: "")
                    when {
                        user.hasLikelyEnded(now) -> {
                            // Finished, and we do not know what is playing now.
                            // Show it complete and stopped rather than ticking on.
                            views.setViewVisibility(bar, View.VISIBLE)
                            views.setViewVisibility(R.id.elapsed, View.GONE)
                            views.setProgressBar(bar, 100, 100, false)
                            views.setChronometer(R.id.elapsed, SystemClock.elapsedRealtime(), null, false)
                        }

                        user.isPlaying && user.durationMs > 0L -> {
                            val elapsed = user.elapsedMsAt(now)
                            views.setViewVisibility(bar, View.VISIBLE)
                            views.setViewVisibility(R.id.elapsed, View.VISIBLE)
                            views.setProgressBar(bar, user.durationMs.toInt(), elapsed.toInt(), false)
                            // Chronometer ticks on its own, keeping elapsed time live
                            // between refreshes without redrawing the widget.
                            views.setChronometer(R.id.elapsed, SystemClock.elapsedRealtime() - elapsed, null, true)
                        }

                        else -> hideProgress(views)
                    }
                }
            }

            views.setTextViewText(R.id.status, statusLine(user, now))
            // Album, not track: a track URI is a play command and restarts the
            // song, whereas the album page opens without touching playback.
            views.setOnClickPendingIntent(R.id.card, openInSpotify(ctx, user.albumUri))
            return views
        }

        private fun pickUser(ctx: Context, state: DuoState?): UserPlayback? {
            val users = state?.users?.takeIf { it.isNotEmpty() } ?: return null
            val self = ctx.selfId
            val partner = users.firstOrNull { it.id != self }
            // Partner leads: seeing them is the point of the widget.
            return if (ctx.showingSelf) users.firstOrNull { it.id == self } ?: partner
            else partner ?: users.first()
        }

        private fun hideProgress(views: RemoteViews) {
            views.setViewVisibility(R.id.progress_blue, View.GONE)
            views.setViewVisibility(R.id.progress_pink, View.GONE)
            views.setViewVisibility(R.id.elapsed, View.GONE)
            views.setChronometer(R.id.elapsed, SystemClock.elapsedRealtime(), null, false)
        }

        private fun statusLine(user: UserPlayback, now: Long): String = when {
            user.needsLogin -> ""
            user.hasLikelyEnded(now) -> "track ended · checking…"
            user.isPlaying -> user.deviceName?.let { "on $it" } ?: "playing"
            user.lastActiveAt != null ->
                "last played " + DateUtils.getRelativeTimeSpanString(user.lastActiveAt, now, DateUtils.MINUTE_IN_MILLIS)
            user.hasTrack -> "paused"
            else -> ""
        }

        private fun broadcast(ctx: Context, action: String): PendingIntent =
            PendingIntent.getBroadcast(
                ctx,
                action.hashCode(),
                Intent(ctx, PlaybackWidget::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        /**
         * Explicit target: Android 14+ rejects implicit intents in PendingIntents
         * here. A null uri still opens Spotify, just without navigating.
         */
        private fun openInSpotify(ctx: Context, uri: String?): PendingIntent =
            PendingIntent.getActivity(
                ctx,
                uri.hashCode(),
                Intent(ctx, OpenTrackActivity::class.java).apply { uri?.let { data = Uri.parse(it) } },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
