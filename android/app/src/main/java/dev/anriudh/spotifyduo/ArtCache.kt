package dev.anriudh.spotifyduo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.palette.graphics.Palette
import java.io.File
import java.net.URL

/**
 * Album art for the widget, cached on disk by URL.
 *
 * RemoteViews cross a Binder transaction capped at roughly 1MB, and exceeding
 * it throws TransactionTooLargeException at runtime rather than failing to
 * build. A 200px RGB_565 bitmap is ~80KB, so two cards sit far inside the cap.
 * Raising TARGET_PX or switching to ARGB_8888 quadruples that -- don't, without
 * re-checking the budget.
 */
object ArtCache {

    private const val TARGET_PX = 200
    private const val FALLBACK_ACCENT = 0xFF1F1F23.toInt()

    /**
     * Roughly 2MB at ~33KB per album. cacheDir is cleared by the system under
     * storage pressure anyway, but an explicit bound keeps it from quietly
     * growing with every new album ever listened to.
     */
    private const val MAX_CACHED = 60

    data class Art(val bitmap: Bitmap?, val accent: Int)

    /** @param desaturate render the art grey, signalling that nobody is listening. */
    fun load(ctx: Context, url: String?, desaturate: Boolean): Art {
        if (url.isNullOrBlank()) return Art(null, FALLBACK_ACCENT)

        val file = File(ctx.cacheDir, "art_${url.hashCode().toUInt()}.jpg")
        if (!file.exists()) {
            if (!download(url, file)) return Art(null, FALLBACK_ACCENT)
            prune(ctx.cacheDir)
        }

        val bitmap = decodeSampled(file) ?: return Art(null, FALLBACK_ACCENT)
        val accent = accentOf(bitmap)
        return if (desaturate) Art(toGrey(bitmap), greyOf(accent)) else Art(bitmap, accent)
    }

    private fun toGrey(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.RGB_565)
        android.graphics.Canvas(out).drawBitmap(
            src,
            0f,
            0f,
            android.graphics.Paint().apply {
                colorFilter = android.graphics.ColorMatrixColorFilter(
                    android.graphics.ColorMatrix().apply { setSaturation(0f) },
                )
            },
        )
        src.recycle()
        return out
    }

    private fun greyOf(color: Int): Int {
        val hsl = FloatArray(3)
        androidx.core.graphics.ColorUtils.colorToHSL(color, hsl)
        hsl[1] = 0f
        return androidx.core.graphics.ColorUtils.HSLToColor(hsl)
    }

    private fun download(url: String, dest: File): Boolean = runCatching {
        URL(url).openStream().use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        true
    }.getOrElse { e ->
        // Never silent: a swallowed failure here is indistinguishable from a
        // track genuinely having no artwork, which is painful to diagnose.
        android.util.Log.w("ArtCache", "album art download failed: $url", e)
        dest.delete()
        false
    }

    /** Drops the least recently modified art beyond the cap. */
    private fun prune(dir: File) {
        val files = dir.listFiles { f -> f.name.startsWith("art_") } ?: return
        if (files.size <= MAX_CACHED) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_CACHED)
            .forEach { it.delete() }
    }

    private fun decodeSampled(file: File): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_PX) sample *= 2

        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            },
        ) ?: return null

        if (decoded.width <= TARGET_PX) decoded
        else Bitmap.createScaledBitmap(decoded, TARGET_PX, TARGET_PX, true)
            .also { if (it !== decoded) decoded.recycle() }
    }.getOrNull()

    /**
     * A dark, album-derived backdrop. Cheaper than blurring the art into a
     * second bitmap, and keeps light text readable regardless of the cover.
     */
    private fun accentOf(bitmap: Bitmap): Int = runCatching {
        val palette = Palette.from(bitmap).clearFilters().generate()
        val base = palette.getDarkMutedColor(
            palette.getDarkVibrantColor(palette.getMutedColor(FALLBACK_ACCENT)),
        )
        darken(base)
    }.getOrElse { FALLBACK_ACCENT }

    private fun darken(color: Int): Int {
        val hsl = FloatArray(3)
        androidx.core.graphics.ColorUtils.colorToHSL(color, hsl)
        hsl[2] = hsl[2].coerceAtMost(0.22f)
        hsl[1] = hsl[1].coerceAtMost(0.55f)
        return Color.parseColor("#FF000000") or androidx.core.graphics.ColorUtils.HSLToColor(hsl)
    }
}
