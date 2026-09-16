package dev.anriudh.spotifyduo

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Trampoline for card taps.
 *
 * A collection widget's PendingIntent template has to be mutable so each card
 * can fill in its own track URI, but Android 14+ rejects a mutable
 * PendingIntent built from an implicit Intent. So the template targets this
 * activity explicitly, and the implicit ACTION_VIEW is issued from here, where
 * the restriction does not apply.
 */
class OpenTrackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let { uri ->
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        finish()
    }
}
