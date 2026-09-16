package dev.anriudh.spotifyduo

import android.content.Context

const val DEFAULT_BASE_URL = "https://spotify-duo.spotify-duo-worker.workers.dev"

private const val PREFS_FILE = "spotifyduo"

private fun Context.prefs() = getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

var Context.baseUrl: String
    get() = prefs().getString("base_url", DEFAULT_BASE_URL)!!.trimEnd('/')
    set(v) = prefs().edit().putString("base_url", v.trim().trimEnd('/')).apply()

var Context.deviceToken: String
    get() = prefs().getString("device_token", "")!!
    set(v) = prefs().edit().putString("device_token", v.trim()).apply()

/** Which person this phone is, so the widget can lead with the other one. */
var Context.selfId: String
    get() = prefs().getString("self_id", "")!!
    set(v) = prefs().edit().putString("self_id", v.trim()).apply()

var Context.cachedStateJson: String?
    get() = prefs().getString("state_json", null)
    set(v) = prefs().edit().putString("state_json", v).apply()

var Context.etag: String?
    get() = prefs().getString("etag", null)
    set(v) = prefs().edit().putString("etag", v).apply()

/** Local clock reading when the cached payload arrived, used for skew correction. */
var Context.cachedAtLocal: Long
    get() = prefs().getLong("cached_at_local", 0L)
    set(v) = prefs().edit().putLong("cached_at_local", v).apply()

/** Which card the widget is showing. Defaults to the partner, who is the point. */
var Context.showingSelf: Boolean
    get() = prefs().getBoolean("showing_self", false)
    set(v) = prefs().edit().putBoolean("showing_self", v).apply()

var Context.lastError: String?
    get() = prefs().getString("last_error", null)
    set(v) = prefs().edit().putString("last_error", v).apply()

val Context.isConfigured: Boolean
    get() = deviceToken.isNotBlank() && baseUrl.isNotBlank()
