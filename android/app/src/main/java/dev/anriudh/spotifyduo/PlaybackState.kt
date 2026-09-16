package dev.anriudh.spotifyduo

import org.json.JSONObject

data class UserPlayback(
    val id: String,
    val displayName: String,
    val needsLogin: Boolean,
    val isPlaying: Boolean,
    val trackName: String?,
    val artistName: String?,
    val albumArtUrl: String?,
    val trackUri: String?,
    val deviceName: String?,
    val progressMs: Long,
    val durationMs: Long,
    val polledAt: Long,
    val lastActiveAt: Long?,
) {
    val hasTrack: Boolean get() = !trackName.isNullOrBlank()

    /**
     * Where playback has reached *now*, extrapolated from the server's reading.
     * Only meaningful while playing; it self-corrects on the next refresh if the
     * track was seeked or paused in between.
     */
    fun elapsedMsAt(now: Long): Long =
        if (!isPlaying) progressMs
        else (progressMs + (now - polledAt)).coerceIn(0L, durationMs.coerceAtLeast(0L))

    /**
     * The track has run past its own duration since we last polled, so it has
     * certainly finished -- but we do not yet know what replaced it. The widget
     * must stop presenting it as currently playing at this point, otherwise it
     * shows a finished song with a clock ticking past the end of it.
     */
    fun hasLikelyEnded(now: Long): Boolean =
        isPlaying && durationMs > 0L && (progressMs + (now - polledAt)) >= durationMs
}

data class DuoState(
    val serverTime: Long,
    val users: List<UserPlayback>,
    /**
     * serverTime minus local time at fetch. Added to the local clock so
     * progress stays right even if the phone's clock is off.
     */
    val clockSkewMs: Long,
) {
    fun now(): Long = System.currentTimeMillis() + clockSkewMs

    companion object {
        fun parse(json: String, receivedAtLocal: Long): DuoState {
            val root = JSONObject(json)
            val serverTime = root.optLong("server_time", receivedAtLocal)
            val arr = root.optJSONArray("users")
            val users = buildList {
                for (i in 0 until (arr?.length() ?: 0)) {
                    val o = arr!!.getJSONObject(i)
                    add(
                        UserPlayback(
                            id = o.optString("id"),
                            displayName = o.optString("display_name"),
                            needsLogin = o.optBoolean("needs_login", false),
                            isPlaying = o.optBoolean("is_playing", false),
                            trackName = o.optStringOrNull("track_name"),
                            artistName = o.optStringOrNull("artist_name"),
                            albumArtUrl = o.optStringOrNull("album_art_url"),
                            trackUri = o.optStringOrNull("track_uri"),
                            deviceName = o.optStringOrNull("device_name"),
                            progressMs = o.optLong("progress_ms", 0L),
                            durationMs = o.optLong("duration_ms", 0L),
                            polledAt = o.optLong("polled_at", 0L),
                            lastActiveAt = o.optLong("last_active_at", 0L).takeIf { it > 0L },
                        )
                    )
                }
            }
            return DuoState(serverTime, users, serverTime - receivedAtLocal)
        }
    }
}

/** org.json turns JSON null into the string "null", which would render literally. */
private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
