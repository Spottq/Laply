package com.flexy.f1live.live

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Icon
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import com.flexy.f1live.model.DriverTiming
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * Tracker icons for the Live Update progress bar: a driver's headshot when we can fetch one,
 * otherwise a team-coloured disc with the three-letter abbreviation.
 *
 * Icons are cached per driver for the lifetime of the process; the notification is rebuilt every
 * couple of seconds, so decoding a headshot each time would be wasteful.
 */
object AvatarIcons {

    /** Icon edge in pixels. Notification tracker icons are small; 128px is plenty. */
    private const val SIZE_PX = 128

    private val cache = ConcurrentHashMap<String, Icon>()

    /** Keys of headshots whose download already failed, so we stop retrying them. */
    private val failed = ConcurrentHashMap.newKeySet<String>()

    /** Stable cache key for a driver's preferred icon. */
    fun keyFor(driver: DriverTiming): String {
        val url = driver.headshotUrl?.trim().orEmpty()
        return if (url.isNotEmpty() && !failed.contains(url)) url else fallbackKey(driver)
    }

    private fun fallbackKey(driver: DriverTiming): String =
        "tla:${driver.tla}:${driver.racingNumber}:${driver.teamColorHex.orEmpty()}"

    /** Cached icon for [driver], or null if nothing has been produced for it yet. */
    fun cached(driver: DriverTiming): Icon? = cache[keyFor(driver)]

    /**
     * Returns the best icon available for [driver], downloading the headshot through Coil when
     * needed. Never throws: any failure degrades to the team-colour disc.
     */
    suspend fun load(context: Context, driver: DriverTiming): Icon {
        cached(driver)?.let { return it }
        val url = driver.headshotUrl?.trim().orEmpty()
        if (url.isNotEmpty() && !failed.contains(url)) {
            val bitmap = runCatching { downloadHeadshot(context, url) }.getOrNull()
            if (bitmap != null) {
                val icon = Icon.createWithBitmap(circleCrop(bitmap, accentOf(driver)))
                cache[url] = icon
                return icon
            }
            failed.add(url)
        }
        return fallback(driver)
    }

    /** Team-coloured disc with the driver's TLA; always available, never hits the network. */
    fun fallback(driver: DriverTiming): Icon {
        val key = fallbackKey(driver)
        cache[key]?.let { return it }
        val label = driver.tla.ifBlank { driver.racingNumber }.take(3).uppercase()
        val icon = Icon.createWithBitmap(teamCircleBitmap(label, accentOf(driver)))
        cache[key] = icon
        return icon
    }

    private fun accentOf(driver: DriverTiming): Int =
        LiveNotificationBuilder.parseTeamColor(driver.teamColorHex)
            ?: LiveNotificationBuilder.COLOR_FALLBACK

    private suspend fun downloadHeadshot(context: Context, url: String): Bitmap? {
        val loader = SingletonImageLoader.get(context)
        val request = ImageRequest.Builder(context)
            .data(url)
            .allowHardware(false)
            .build()
        val result = loader.execute(request)
        val image = (result as? SuccessResult)?.image ?: return null
        return (image as? BitmapImage)?.bitmap
    }

    // ---------------------------------------------------------------- drawing

    /** Centre-crops [source] into a circle and rings it with [ringColor]. */
    fun circleCrop(source: Bitmap, ringColor: Int): Bitmap {
        val output = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val radius = SIZE_PX / 2f

        // Background disc so transparent headshots still read against any wallpaper.
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ringColor }
        canvas.drawCircle(radius, radius, radius, bg)

        val srcW = max(1, source.width)
        val srcH = max(1, source.height)
        val scale = SIZE_PX / min(srcW, srcH).toFloat()
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(
                (SIZE_PX - srcW * scale) / 2f,
                (SIZE_PX - srcH * scale) / 2f,
            )
        }
        val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(matrix)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
        canvas.drawCircle(radius, radius, radius, paint)

        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = SIZE_PX * 0.07f
            color = ringColor
        }
        canvas.drawCircle(radius, radius, radius - ring.strokeWidth / 2f, ring)
        return output
    }

    /** A filled disc in [color] with [label] centred on it in a contrasting colour. */
    fun teamCircleBitmap(label: String, color: Int): Bitmap {
        val output = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val radius = SIZE_PX / 2f
        val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        canvas.drawCircle(radius, radius, radius, disc)

        if (label.isNotEmpty()) {
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = contrastingTextColor(color)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                textSize = SIZE_PX * 0.40f
            }
            // Shrink until the label fits inside the disc with a small margin.
            val bounds = Rect()
            var guard = 0
            while (guard++ < 12) {
                textPaint.getTextBounds(label, 0, label.length, bounds)
                if (bounds.width() <= SIZE_PX * 0.78f) break
                textPaint.textSize *= 0.9f
            }
            val baseline = radius - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(label, radius, baseline, textPaint)
        }
        return output
    }

    /** Black on light team colours, white on dark ones. */
    private fun contrastingTextColor(color: Int): Int {
        val luminance =
            0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)
        return if (luminance > 160) Color.BLACK else Color.WHITE
    }

    /** Drops every cached icon. Called when the service shuts down. */
    fun clear() {
        cache.clear()
        failed.clear()
    }
}
