package dev.anriudh.spotifyduo

import android.content.Context
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the Worker's /state and /refresh endpoints and keeps the last good
 * payload on disk, so the widget still renders while offline.
 *
 * Every call here blocks. Only invoke it from a binder or background thread --
 * RemoteViewsFactory.onDataSetChanged is explicitly allowed to block, which is
 * where the normal refresh happens.
 */
object StateRepository {

    private const val TIMEOUT_MS = 15_000

    fun cached(ctx: Context): DuoState? {
        val json = ctx.cachedStateJson ?: return null
        val at = ctx.cachedAtLocal.takeIf { it > 0L } ?: System.currentTimeMillis()
        return runCatching { DuoState.parse(json, at) }.getOrNull()
    }

    /**
     * Sets how this person's name appears on the *other* phone. Server-side by
     * necessity: it is the partner's widget that renders it. Returns the fresh
     * state the Worker sends back, already cached, or null on failure.
     */
    fun setDisplayName(ctx: Context, name: String): DuoState? {
        if (!ctx.isConfigured) return null
        val conn = (URL(ctx.baseUrl + "/me").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${ctx.deviceToken}")
            setRequestProperty("Content-Type", "application/json")
        }
        return try {
            conn.outputStream.use {
                it.write(org.json.JSONObject().put("display_name", name).toString().toByteArray())
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                ctx.lastError = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Server returned ${conn.responseCode}"
                return null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val receivedAt = System.currentTimeMillis()
            ctx.cachedStateJson = body
            ctx.cachedAtLocal = receivedAt
            conn.getHeaderField("ETag")?.let { ctx.etag = it }
            ctx.lastError = null
            DuoState.parse(body, receivedAt)
        } catch (e: IOException) {
            ctx.lastError = e.message ?: "Network error"
            null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * @param forced POST /refresh, making the server poll Spotify immediately
     *   and bypass its own staleness window. Used by the widget's refresh button.
     * @return the newest state available, falling back to cache on failure.
     */
    fun refresh(ctx: Context, forced: Boolean): DuoState? {
        if (!ctx.isConfigured) {
            ctx.lastError = "Not set up yet"
            return null
        }

        val path = if (forced) "/refresh" else "/state"
        val conn = (URL(ctx.baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = if (forced) "POST" else "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer ${ctx.deviceToken}")
            // A 304 means nothing changed, so the cache is already correct.
            if (!forced) ctx.etag?.let { setRequestProperty("If-None-Match", it) }
        }

        return try {
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    ctx.lastError = null
                    cached(ctx)
                }

                HttpURLConnection.HTTP_OK -> {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val receivedAt = System.currentTimeMillis()
                    val state = DuoState.parse(body, receivedAt)
                    ctx.cachedStateJson = body
                    ctx.cachedAtLocal = receivedAt
                    conn.getHeaderField("ETag")?.let { ctx.etag = it }
                    ctx.lastError = null
                    state
                }

                HttpURLConnection.HTTP_UNAUTHORIZED -> {
                    ctx.lastError = "Device token rejected"
                    cached(ctx)
                }

                else -> {
                    ctx.lastError = "Server returned $code"
                    cached(ctx)
                }
            }
        } catch (e: IOException) {
            ctx.lastError = e.message ?: "Network error"
            cached(ctx)
        } finally {
            conn.disconnect()
        }
    }
}
