package dev.anriudh.spotifyduo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.palette.graphics.Palette
import java.io.File
import java.net.URL

/**
 * Album art for the widget, cached on disk by URL, with the card's visual
 * treatment baked in so exactly one bitmap crosses to the launcher.
 *
 * RemoteViews cross a Binder transaction capped at roughly 1MB, and exceeding
 * it throws TransactionTooLargeException at runtime rather than failing to
 * build. Budget, RGB_565: 200px = 80KB, 400px = 320KB, 640px = 820KB (too
 * close). ARGB_8888 doubles each. The source is Spotify's 640px variant,
 * decoded *down* to TARGET_PX, so the detail is real rather than upscaled.
 */
object ArtCache {

    private const val TARGET_PX = 400
    private const val FALLBACK_ACCENT = 0xFF1F1F23.toInt()

    /**
     * Roughly 3MB at ~45KB per 640px album (measured). cacheDir is cleared by the system
     * under storage pressure anyway, but an explicit bound keeps it from quietly
     * growing with every new album ever listened to.
     */
    private const val MAX_CACHED = 60

    /** Frosted band: fully sharp above BLUR_FROM, fully blurred below BLUR_TO. */
    private const val BLUR_FROM = 0.34f
    private const val BLUR_TO = 0.72f

    data class Art(val bitmap: Bitmap?, val accent: Int)

    /** @param desaturate render the art grey, signalling that nobody is listening. */
    fun load(ctx: Context, url: String?, desaturate: Boolean): Art {
        if (url.isNullOrBlank()) return Art(null, FALLBACK_ACCENT)

        val file = File(ctx.cacheDir, "art_${url.hashCode().toUInt()}.jpg")
        if (!file.exists()) {
            if (!download(url, file)) return Art(null, FALLBACK_ACCENT)
            prune(ctx.cacheDir)
        }

        val sharp = decodeSampled(file) ?: return Art(null, FALLBACK_ACCENT)
        val accent = accentOf(sharp)
        val bitmap = frostBottom(sharp)
        return if (desaturate) Art(toGrey(bitmap), greyOf(accent)) else Art(bitmap, accent)
    }

    /**
     * Blurs the lower part of the art, feathered in from BLUR_FROM to BLUR_TO,
     * so text sits on frosted glass while the top of the cover stays crisp.
     * Baked into the same bitmap: a separate blurred layer would be a second
     * bitmap across Binder, and this costs nothing extra.
     *
     * The card is centerCrop, so on a very wide widget the top and bottom of
     * the (square) art are cropped symmetrically; the band still lands under
     * the text, just a little higher up the card.
     */
    private fun frostBottom(sharp: Bitmap): Bitmap {
        val w = sharp.width
        val h = sharp.height
        val blur = blurred(sharp)

        // Blur layer with alpha ramped by a vertical gradient (DST_IN keeps
        // the blur only where the gradient is opaque -- the bottom).
        val layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(layer).apply {
            drawBitmap(blur, 0f, 0f, null)
            drawRect(
                0f, 0f, w.toFloat(), h.toFloat(),
                android.graphics.Paint().apply {
                    shader = android.graphics.LinearGradient(
                        0f, h * BLUR_FROM, 0f, h * BLUR_TO,
                        0x00000000, 0xFF000000.toInt(),
                        android.graphics.Shader.TileMode.CLAMP,
                    )
                    xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
                },
            )
        }
        blur.recycle()

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        android.graphics.Canvas(out).apply {
            drawBitmap(sharp, 0f, 0f, null)
            drawBitmap(layer, 0f, 0f, null)
        }
        layer.recycle()
        sharp.recycle()
        return out
    }

    /**
     * Cheap, dependency-free blur: shrink with bilinear filtering and scale
     * back up, twice. RenderScript is gone and RenderEffect is view-only, and
     * this reads as a proper frost at the sizes involved.
     */
    private fun blurred(src: Bitmap, factor: Int = 8): Bitmap {
        var current = src
        repeat(2) {
            val small = Bitmap.createScaledBitmap(
                current, (src.width / factor).coerceAtLeast(1), (src.height / factor).coerceAtLeast(1), true,
            )
            val back = Bitmap.createScaledBitmap(small, src.width, src.height, true)
            small.recycle()
            if (current !== src) current.recycle()
            current = back
        }
        return current
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
