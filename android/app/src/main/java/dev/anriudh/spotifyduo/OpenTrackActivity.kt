package dev.anriudh.spotifyduo

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * Trampoline for card taps.
 *
 * Android 14+ rejects a PendingIntent built from an implicit Intent, so the
 * widget targets this activity explicitly and the implicit ACTION_VIEW is
 * issued from here, where the restriction does not apply.
 *
 * It is handed an *album* URI, never a track one. Measured on device: both
 * spotify:track: and open.spotify.com/track/ links restart the song that is
 * playing, while the album page opens and leaves playback alone.
 */
class OpenTrackActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri == null || !openInSpotify(uri)) launchSpotify()
        finish()
    }

    private fun openInSpotify(uri: Uri): Boolean {
        val target = asOpenLink(uri)
        // Target the Spotify app so the link cannot land in a browser; fall
        // back to any handler if Spotify is not installed.
        val attempts = listOf(
            Intent(Intent.ACTION_VIEW, target).setPackage(SPOTIFY_PACKAGE),
            Intent(Intent.ACTION_VIEW, target),
        )
        return attempts.any { start(it) }
    }

    /** No navigation target: just bring Spotify to the front, wherever it was. */
    private fun launchSpotify() {
        packageManager.getLaunchIntentForPackage(SPOTIFY_PACKAGE)?.let { start(it) }
    }

    private fun start(intent: Intent): Boolean = runCatching {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    private fun asOpenLink(uri: Uri): Uri {
        val parts = uri.toString().split(':')
        if (parts.size != 3 || parts[0] != "spotify") return uri
        return Uri.parse("https://open.spotify.com/${parts[1]}/${parts[2]}")
    }

    private companion object {
        const val SPOTIFY_PACKAGE = "com.spotify.music"
    }
}
