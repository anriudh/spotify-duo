package dev.anriudh.spotifyduo

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * Trampoline for card taps.
 *
 * A collection widget's PendingIntent template has to be mutable so each card
 * can fill in its own track URI, but Android 14+ rejects a mutable
 * PendingIntent built from an implicit Intent. So the widget targets this
 * activity explicitly, and the implicit ACTION_VIEW is issued from here, where
 * the restriction does not apply.
 */
class OpenTrackActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let(::openInSpotify)
        finish()
    }

    private fun openInSpotify(uri: Uri) {
        val target = asOpenLink(uri)
        // Prefer the Spotify app so the link cannot land in a browser, but fall
        // back to whatever can handle it if Spotify is not installed.
        val attempts = listOf(
            Intent(Intent.ACTION_VIEW, target).setPackage(SPOTIFY_PACKAGE),
            Intent(Intent.ACTION_VIEW, target),
        )
        for (intent in attempts) {
            val started = runCatching {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
            if (started) return
        }
    }

    /**
     * `spotify:track:<id>` is a play command -- opening it restarts the track.
     * The equivalent open.spotify.com link navigates to the same page and
     * leaves playback alone, which is what tapping the card should do.
     */
    private fun asOpenLink(uri: Uri): Uri {
        val parts = uri.toString().split(':')
        if (parts.size != 3 || parts[0] != "spotify") return uri
        return Uri.parse("https://open.spotify.com/${parts[1]}/${parts[2]}")
    }

    private companion object {
        const val SPOTIFY_PACKAGE = "com.spotify.music"
    }
}
