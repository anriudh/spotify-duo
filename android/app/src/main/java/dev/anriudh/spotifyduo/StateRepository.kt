package dev.anriudh.spotifyduo

import android.content.Context
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the Worker and keeps the last good payload on disk, so the widget
 * still renders while offline.
 *
 * Every call here blocks. Only invoke it from a background thread; the widget
 * does so via goAsync, the setup screen via a plain Thread.
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
        val body = org.json.JSONObject().put("display_name", name).toString()
        return request(ctx, "/me", "POST", body = body, useEtag = false)
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
        return if (forced) request(ctx, "/refresh", "POST", useEtag = false)
        else request(ctx, "/state", "GET", useEtag = true)
    }

    /**
     * One round trip. A 200 replaces the cache; a 304 means the cache is
     * already correct; anything else records the error and serves the cache.
     */
    private fun request(ctx: Context, path: String, method: String, body: String? = null, useEtag: Boolean): DuoState? {
        val conn = (URL(ctx.baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer ${ctx.deviceToken}")
            if (useEtag) ctx.etag?.let { setRequestProperty("If-None-Match", it) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }

        return try {
            body?.let { b -> conn.outputStream.use { it.write(b.toByteArray()) } }
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    val receivedAt = System.currentTimeMillis()
                    val state = DuoState.parse(text, receivedAt)
                    ctx.cachedStateJson = text
                    ctx.cachedAtLocal = receivedAt
                    conn.getHeaderField("ETag")?.let { ctx.etag = it }
                    ctx.lastError = null
                    state
                }

                HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    ctx.lastError = null
                    cached(ctx)
                }

                HttpURLConnection.HTTP_UNAUTHORIZED -> {
                    ctx.lastError = "Device token rejected"
                    cached(ctx)
                }

                else -> {
                    // The Worker's 4xx bodies are short and human-readable.
                    ctx.lastError = conn.errorStream?.bufferedReader()?.use { it.readText() }
                        ?.takeIf { it.isNotBlank() } ?: "Server returned $code"
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
