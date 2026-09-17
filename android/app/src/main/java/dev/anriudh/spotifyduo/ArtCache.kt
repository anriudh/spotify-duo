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

    data class Art(val bitmap: Bitmap?, val accent: Int)

    /**
     * Where to frost, in *card* pixels: the rectangle the text occupies on
     * screen plus a margin, along with the card's size so it can be mapped
     * through the centerCrop transform into bitmap coordinates.
     */
    data class Frost(val cardW: Float, val cardH: Float, val rect: android.graphics.RectF, val featherPx: Float)

    /**
     * @param desaturate render the art grey, signalling that nobody is listening.
     * @param frost region to blur behind the text, or null for untouched art.
     */
    fun load(ctx: Context, url: String?, desaturate: Boolean, frost: Frost?): Art {
        if (url.isNullOrBlank()) return Art(null, FALLBACK_ACCENT)

        val file = File(ctx.cacheDir, "art_${url.hashCode().toUInt()}.jpg")
        if (!file.exists()) {
            if (!download(url, file)) return Art(null, FALLBACK_ACCENT)
            prune(ctx.cacheDir)
        }

        val sharp = decodeSampled(file) ?: return Art(null, FALLBACK_ACCENT)
        val accent = accentOf(sharp)
        val bitmap = if (frost != null) frostRegion(sharp, frost) else sharp
        return if (desaturate) Art(toGrey(bitmap), greyOf(accent)) else Art(bitmap, accent)
    }

    /**
     * Blurs only the region behind the text -- a rounded rectangle hugging the
     * track and artist lines, with soft edges -- and leaves the rest of the
     * cover crisp. Baked into the same bitmap, so still one bitmap across
     * Binder.
     *
     * The card is centerCrop of this square bitmap, so the card-space rect is
     * mapped through that transform: scale by the larger card/bitmap ratio,
     * then subtract the centring offset.
     */
    private fun frostRegion(sharp: Bitmap, f: Frost): Bitmap {
        val size = sharp.width.toFloat()
        val scale = maxOf(f.cardW / size, f.cardH / size)
        val offX = (f.cardW - size * scale) / 2f
        val offY = (f.cardH - size * scale) / 2f
        val r = android.graphics.RectF(
            (f.rect.left - offX) / scale,
            (f.rect.top - offY) / scale,
            (f.rect.right - offX) / scale,
            (f.rect.bottom - offY) / scale,
        )
        val feather = f.featherPx / scale
        val corner = r.height() * 0.28f

        val blur = blurred(sharp)
        // Mask first: a soft-edged rounded rect on a transparent layer. Then the
        // blur is drawn through it with SRC_IN, which keeps blurred pixels only
        // where the mask has alpha and leaves everything else transparent.
        // (DST_IN the other way round would not touch pixels outside the shape,
        // leaving the whole image blurred.)
        val layer = Bitmap.createBitmap(sharp.width, sharp.height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(layer).apply {
            drawRoundRect(
                r, corner, corner,
                android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFF000000.toInt()
                    maskFilter = android.graphics.BlurMaskFilter(feather, android.graphics.BlurMaskFilter.Blur.NORMAL)
                },
            )
            drawBitmap(
                blur, 0f, 0f,
                android.graphics.Paint().apply {
                    xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
                },
            )
        }
        blur.recycle()

        val out = Bitmap.createBitmap(sharp.width, sharp.height, Bitmap.Config.RGB_565)
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
