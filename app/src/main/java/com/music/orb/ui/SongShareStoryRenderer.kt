package com.music.orb.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.core.content.FileProvider
import com.music.orb.R
import com.music.orb.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import kotlin.math.max

/** Renders a 9:16 music card ready for Stories-style image sharing. */
object SongShareStoryRenderer {
    const val WIDTH = 1080
    const val HEIGHT = 1920
    private const val SHARE_LINK = "https://theusalbuquerque.github.io/orb/"

    suspend fun render(context: Context, song: Song): Bitmap = withContext(Dispatchers.IO) {
        val output = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val artwork = load(song.thumbnailUrl)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        if (artwork != null) drawBlurredBackdrop(canvas, artwork, paint)
        else canvas.drawColor(Color.rgb(45, 61, 52))

        // Dim the blurred cover so the central card remains the focus.
        paint.color = Color.argb(92, 10, 18, 13)
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)

        // Faithful central card: portrait card with square cover on top and text below.
        // Tuned to match the approved reference more closely.
        val card = RectF(206f, 404f, 874f, 1228f)
        drawStoryCard(canvas, artwork, song, card)
        drawFooterBranding(canvas, context, artwork)
        output
    }

    suspend fun share(context: Context, song: Song) {
        val bitmap = render(context, song)
        val uri = withContext(Dispatchers.IO) {
            val folder = File(context.cacheDir, "song_share").apply { mkdirs() }
            val file = File(folder, "orb-song-${song.videoId}.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            FileProvider.getUriForFile(context, "${context.packageName}.orb-updates.files", file)
        }
        withContext(Dispatchers.Main) {
            val shareText = "${song.title} — ${song.artist}\n$SHARE_LINK"
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                // ACTION_SEND targets match primarily on the MIME type. Do not
                // also set the intent data to the Orb https link: doing that
                // requires receiving apps to advertise ACTION_SEND + image/png
                // + https simultaneously, which removes almost every normal
                // share target from Android's chooser. The URL belongs in
                // EXTRA_TEXT; the generated story artwork stays in EXTRA_STREAM.
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, shareText)
                putExtra(Intent.EXTRA_SUBJECT, song.title)
                clipData = android.content.ClipData.newUri(context.contentResolver, "orb-song-share", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val initialIntents = mutableListOf<Intent>()
            val instagramStoryIntent = Intent("com.instagram.share.ADD_TO_STORY").apply {
                setDataAndType(uri, "image/png")
                putExtra("content_url", SHARE_LINK)
                putExtra("source_application", context.packageName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newUri(context.contentResolver, "orb-song-share", uri)
                `package` = "com.instagram.android"
            }
            if (instagramStoryIntent.resolveActivity(context.packageManager) != null) {
                initialIntents += instagramStoryIntent
            }
            val chooser = Intent.createChooser(sendIntent, context.getString(R.string.song_action_share)).apply {
                if (initialIntents.isNotEmpty()) {
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, initialIntents.toTypedArray())
                }
            }
            context.startActivity(chooser)
        }
    }

    private fun drawStoryCard(canvas: Canvas, artwork: Bitmap?, song: Song, card: RectF) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.color = Color.argb(220, 45, 46, 49)
        paint.setShadowLayer(70f, 0f, 28f, Color.argb(136, 0, 0, 0))
        canvas.drawRoundRect(card, 30f, 30f, paint)
        paint.clearShadowLayer()

        val sidePadding = 34f
        val topPadding = 34f
        val coverSize = card.width() - sidePadding * 2f
        val coverRect = RectF(
            card.left + sidePadding,
            card.top + topPadding,
            card.left + sidePadding + coverSize,
            card.top + topPadding + coverSize,
        )
        if (artwork != null) {
            drawCenterCrop(canvas, artwork, coverRect, paint)
        } else {
            paint.color = Color.rgb(206, 206, 206)
            canvas.drawRoundRect(coverRect, 8f, 8f, paint)
        }

        val textLeft = coverRect.left
        val textWidth = coverRect.width()
        val titleBase = coverRect.bottom + 64f

        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        paint.textSize = 44f
        paint.color = Color.WHITE
        paint.isSubpixelText = true
        val title = ellipsize(song.title, paint, textWidth)
        canvas.drawText(title, textLeft, titleBase, paint)

        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        paint.textSize = 34f
        paint.color = Color.argb(232, 255, 255, 255)
        val artist = ellipsize(song.artist, paint, textWidth)
        canvas.drawText(artist, textLeft, titleBase + 40f, paint)
    }

    private fun drawFooterBranding(canvas: Canvas, context: Context, artwork: Bitmap?) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val listen = context.getString(R.string.song_share_listen_on)
        paint.color = Color.argb(238, 255, 255, 255)
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        paint.textSize = 34f
        val listenWidth = paint.measureText(listen)
        canvas.drawText(listen, (WIDTH - listenWidth) / 2f, 1512f, paint)

        // Use the official Orb share logo resources derived from the app assets.
        val logoRes = if (artwork != null && averageLuminance(artwork) < 0.42f) {
            R.drawable.orb_share_logo_light
        } else {
            R.drawable.orb_share_logo_dark
        }
        val logo = trimTransparent(resourceBitmap(context, logoRes))
        if (logo != null) {
            val logoWidth = 192f
            val logoHeight = logoWidth * (logo.height.toFloat() / logo.width.toFloat())
            val dst = RectF(
                (WIDTH - logoWidth) / 2f,
                1540f,
                (WIDTH + logoWidth) / 2f,
                1540f + logoHeight,
            )
            canvas.drawBitmap(logo, null, dst, paint)
        }
    }

    private fun load(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        return runCatching {
            URL(url.replace("w120-h120", "w1200-h1200")).openStream().use(BitmapFactory::decodeStream)
        }.getOrNull()
    }

    private fun resourceBitmap(context: Context, @DrawableRes resId: Int): Bitmap? =
        runCatching { BitmapFactory.decodeResource(context.resources, resId) }.getOrNull()

    private fun averageLuminance(bitmap: Bitmap): Float {
        val sample = Bitmap.createScaledBitmap(bitmap, 24, 24, true)
        val pixels = IntArray(sample.width * sample.height)
        sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
        var sum = 0f
        for (color in pixels) sum += luminance(color)
        sample.recycle()
        return if (pixels.isNotEmpty()) sum / pixels.size else 0f
    }

    private fun luminance(color: Int): Float {
        fun c(channel: Int): Float {
            val value = channel / 255f
            return if (value <= 0.03928f) value / 12.92f else Math.pow(((value + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }
        return 0.2126f * c(Color.red(color)) + 0.7152f * c(Color.green(color)) + 0.0722f * c(Color.blue(color))
    }

    private fun trimTransparent(bitmap: Bitmap?): Bitmap? {
        if (bitmap == null) return null
        val width = bitmap.width
        val height = bitmap.height
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val alpha = Color.alpha(pixels[y * width + x])
                if (alpha > 8) {
                    if (x < left) left = x
                    if (y < top) top = y
                    if (x > right) right = x
                    if (y > bottom) bottom = y
                }
            }
        }
        return if (right < left || bottom < top) bitmap else Bitmap.createBitmap(bitmap, left, top, right - left + 1, bottom - top + 1)
    }

    private fun drawBlurredBackdrop(canvas: Canvas, artwork: Bitmap, paint: Paint) {
        val smallW = WIDTH / 8
        val smallH = HEIGHT / 8
        val small = Bitmap.createBitmap(smallW, smallH, Bitmap.Config.ARGB_8888)
        drawCenterCrop(Canvas(small), artwork, RectF(0f, 0f, smallW.toFloat(), smallH.toFloat()), paint)
        val blurredSmall = boxBlur(small, radius = 14)
        val blurred = Bitmap.createScaledBitmap(blurredSmall, WIDTH, HEIGHT, true)
        canvas.drawBitmap(blurred, 0f, 0f, paint)
        if (blurredSmall !== small) blurredSmall.recycle()
        small.recycle()
        blurred.recycle()
    }

    private fun boxBlur(source: Bitmap, radius: Int): Bitmap {
        if (radius <= 0) return source
        val width = source.width
        val height = source.height
        val input = IntArray(width * height)
        val temp = IntArray(input.size)
        val output = IntArray(input.size)
        source.getPixels(input, 0, width, 0, 0, width, height)
        val window = radius * 2 + 1

        for (y in 0 until height) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            fun add(color: Int, sign: Int) {
                a += Color.alpha(color) * sign
                r += Color.red(color) * sign
                g += Color.green(color) * sign
                b += Color.blue(color) * sign
            }
            for (x in -radius..radius) add(input[y * width + x.coerceIn(0, width - 1)], 1)
            for (x in 0 until width) {
                temp[y * width + x] = Color.argb(a / window, r / window, g / window, b / window)
                add(input[y * width + (x - radius).coerceIn(0, width - 1)], -1)
                add(input[y * width + (x + radius + 1).coerceIn(0, width - 1)], 1)
            }
        }

        for (x in 0 until width) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            fun add(color: Int, sign: Int) {
                a += Color.alpha(color) * sign
                r += Color.red(color) * sign
                g += Color.green(color) * sign
                b += Color.blue(color) * sign
            }
            for (y in -radius..radius) add(temp[y.coerceIn(0, height - 1) * width + x], 1)
            for (y in 0 until height) {
                output[y * width + x] = Color.argb(a / window, r / window, g / window, b / window)
                add(temp[(y - radius).coerceIn(0, height - 1) * width + x], -1)
                add(temp[(y + radius + 1).coerceIn(0, height - 1) * width + x], 1)
            }
        }
        return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun drawCenterCrop(canvas: Canvas, bitmap: Bitmap, dst: RectF, paint: Paint) {
        val scale = max(dst.width() / bitmap.width.toFloat(), dst.height() / bitmap.height.toFloat())
        val scaledW = bitmap.width * scale
        val scaledH = bitmap.height * scale
        val left = dst.left + (dst.width() - scaledW) / 2f
        val top = dst.top + (dst.height() - scaledH) / 2f
        canvas.save()
        canvas.clipRect(dst)
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + scaledW, top + scaledH), paint)
        canvas.restore()
    }

    private fun ellipsize(value: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(value) <= maxWidth) return value
        val suffix = "…"
        var text = value
        while (text.isNotEmpty() && paint.measureText(text + suffix) > maxWidth) {
            text = text.dropLast(1)
        }
        return text.trimEnd() + suffix
    }
}
