package dev.anriudh.spotifyduo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.text.format.DateUtils
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService

class WidgetStackService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        WidgetStackFactory(applicationContext)
}

/**
 * Supplies the swipeable cards. onDataSetChanged runs on a binder thread and is
 * allowed to block, which is why the network fetch and art decoding live here
 * rather than in the provider.
 */
class WidgetStackFactory(private val ctx: Context) : RemoteViewsService.RemoteViewsFactory {

    private data class Card(val user: UserPlayback, val art: ArtCache.Art, val now: Long)

    private var cards: List<Card> = emptyList()

    override fun onCreate() = Unit
    override fun onDestroy() = Unit
    override fun getCount(): Int = cards.size
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = true
    override fun getLoadingView(): RemoteViews? = null

    override fun onDataSetChanged() {
        val forced = ctx.pendingForcedRefresh
        ctx.pendingForcedRefresh = false

        val state = StateRepository.refresh(ctx, forced) ?: StateRepository.cached(ctx)
        cards = if (state == null) emptyList() else buildCards(state)
    }

    private fun buildCards(state: DuoState): List<Card> {
        val self = ctx.selfId
        val now = state.now()
        // Partner leads: the point of the widget is seeing them, not yourself.
        return state.users
            .sortedBy { it.id == self }
            .map { Card(it, ArtCache.load(ctx, it.albumArtUrl, desaturate = !it.isPlaying), now) }
    }

    override fun getViewAt(position: Int): RemoteViews {
        val card = cards.getOrNull(position) ?: return RemoteViews(ctx.packageName, R.layout.widget_card)
        val user = card.user
        val views = RemoteViews(ctx.packageName, R.layout.widget_card)

        views.setInt(R.id.card, "setBackgroundColor", card.art.accent)
        views.setTextViewText(R.id.who, if (user.id == ctx.selfId) "You" else user.displayName)

        card.art.bitmap?.let { views.setImageViewBitmap(R.id.art, it) }
        views.setViewVisibility(R.id.art, if (card.art.bitmap != null) View.VISIBLE else View.GONE)

        when {
            user.needsLogin -> {
                views.setTextViewText(R.id.track, "Not linked yet")
                views.setTextViewText(R.id.artist, "Open the app to sign in")
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
                renderProgress(views, user, card.now)
            }
        }

        views.setTextViewText(R.id.status, statusLine(user, card.now))

        user.trackUri?.let {
            views.setOnClickFillInIntent(R.id.card, Intent().setData(Uri.parse(it)))
        }
        return views
    }

    private fun renderProgress(views: RemoteViews, user: UserPlayback, now: Long) {
        if (!user.isPlaying || user.durationMs <= 0L) {
            hideProgress(views)
            return
        }
        val elapsed = user.elapsedMsAt(now)
        views.setViewVisibility(R.id.progress, View.VISIBLE)
        views.setViewVisibility(R.id.elapsed, View.VISIBLE)
        views.setProgressBar(R.id.progress, user.durationMs.toInt(), elapsed.toInt(), false)
        // A Chronometer ticks by itself, so elapsed time stays live between
        // refreshes without the widget being redrawn.
        views.setChronometer(R.id.elapsed, SystemClock.elapsedRealtime() - elapsed, null, true)
    }

    private fun hideProgress(views: RemoteViews) {
        views.setViewVisibility(R.id.progress, View.GONE)
        views.setViewVisibility(R.id.elapsed, View.GONE)
        views.setChronometer(R.id.elapsed, SystemClock.elapsedRealtime(), null, false)
    }

    private fun statusLine(user: UserPlayback, now: Long): String = when {
        user.needsLogin -> ""
        user.isPlaying -> user.deviceName?.let { "on $it" } ?: "playing"
        user.lastActiveAt != null -> "last played " + DateUtils.getRelativeTimeSpanString(
            user.lastActiveAt,
            now,
            DateUtils.MINUTE_IN_MILLIS,
        )
        user.hasTrack -> "paused"
        else -> ""
    }
}
