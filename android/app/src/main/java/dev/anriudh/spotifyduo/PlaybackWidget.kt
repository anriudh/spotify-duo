package dev.anriudh.spotifyduo

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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

    /** The frost region is measured from the widget's size, so a resize needs a redraw. */
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, opts: Bundle) {
        runOffMainThread(ctx, hitNetwork = false, forced = false)
    }

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
            // Per instance: the frosted region depends on each widget's own size.
            widgetIds(ctx, mgr).forEach { mgr.updateAppWidget(it, buildViews(ctx, state, mgr, it)) }
            RefreshScheduler.scheduleNext(ctx, state)
        }

        private fun buildViews(ctx: Context, state: DuoState?, mgr: AppWidgetManager, widgetId: Int): RemoteViews {
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

            val opts = mgr.getAppWidgetOptions(widgetId)
            val compact = isCompact(opts)
            val showsBar = user.hasLikelyEnded(now) || (user.isPlaying && user.durationMs > 0L)
            val art = ArtCache.load(
                ctx, user.albumArtUrl,
                desaturate = !user.isPlaying,
                frost = frostFor(ctx, opts, widgetId, user, showsBar, compact),
            )
            applyCompact(ctx, views, compact)
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
                    // Compact has no status line, so it rides along after the
                    // artist; ellipsizing at the end keeps the artist readable.
                    val artist = user.artistName ?: ""
                    val status = statusLine(user, now)
                    views.setTextViewText(
                        R.id.artist,
                        if (compact && status.isNotEmpty()) "$artist · $status" else artist,
                    )
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

        /**
         * The rectangle the track and artist lines occupy on screen, plus a
         * margin, in card pixels. Reproduces widget_card.xml's geometry: root
         * padding, then from the bottom up the status line, the bar row and the
         * two text lines. Text widths are measured with the same size and
         * weight the TextViews use and capped at the available width, which is
         * where they ellipsize.
         */
        private fun frostFor(
            ctx: Context,
            opts: Bundle,
            widgetId: Int,
            user: UserPlayback,
            showsBar: Boolean,
            compact: Boolean,
        ): ArtCache.Frost? {
            if (!user.hasTrack) return null
            val dm = ctx.resources.displayMetrics
            val cardWdp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val cardHdp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            val cardW = cardWdp * dm.density
            val cardH = cardHdp * dm.density
            if (cardW <= 0f || cardH <= 0f) return null
            android.util.Log.d("SpotifyDuo", "widget $widgetId ${cardWdp}x${cardHdp}dp compact=$compact")

            fun dp(v: Float) = v * dm.density
            fun sp(v: Float) = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, v, dm)
            fun paint(size: Float, bold: Boolean) = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = sp(size)
                if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            fun lineH(p: android.text.TextPaint) = p.fontMetrics.let { it.descent - it.ascent }

            val track = paint(19f, bold = true)
            val artist = paint(14f, bold = false)
            val small = paint(11f, bold = false)

            val pad = dp(if (compact) COMPACT_PAD_DP else FULL_PAD_DP)
            val avail = cardW - 2 * pad
            val textW = maxOf(
                minOf(track.measureText(user.trackName ?: ""), avail),
                minOf(artist.measureText(user.artistName ?: ""), avail),
            )

            // Bar row keeps its 8dp top margin even when its children are gone;
            // compact removes the status line.
            val barRow = dp(8f) + if (showsBar) maxOf(dp(6f), lineH(small)) else 0f
            val statusRow = if (compact) 0f else lineH(small) + dp(2f)
            val bottom = cardH - pad - statusRow - barRow
            val top = bottom - lineH(track) - lineH(artist)

            val mx = dp(12f)
            val my = dp(6f)
            return ArtCache.Frost(
                cardW, cardH,
                android.graphics.RectF(pad - mx, top - my, pad + textW + mx, bottom + my),
                featherPx = dp(10f),
            )
        }

        private const val FULL_PAD_DP = 14f
        private const val COMPACT_PAD_DP = 10f
        /**
         * Below this the full stack (chip, two lines, bar, status) no longer
         * fits. One launcher row on a Moto G73 reports 134dp, and the status
         * line clips there: the reported height includes launcher padding.
         */
        private const val COMPACT_BELOW_DP = 150

        private fun isCompact(opts: Bundle): Boolean {
            val h = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            return h in 1 until COMPACT_BELOW_DP
        }

        /** Short cards: tighter padding and no status line (it moves into the artist line). */
        private fun applyCompact(ctx: Context, views: RemoteViews, compact: Boolean) {
            val pad = ((if (compact) COMPACT_PAD_DP else FULL_PAD_DP) * ctx.resources.displayMetrics.density).toInt()
            views.setViewPadding(R.id.content, pad, pad, pad, pad)
            views.setViewVisibility(R.id.status, if (compact) View.GONE else View.VISIBLE)
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
