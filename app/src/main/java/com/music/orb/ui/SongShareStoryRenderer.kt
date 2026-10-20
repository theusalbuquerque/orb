package com.music.orb.ui

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import androidx.core.content.FileProvider
import com.music.orb.R
import com.music.orb.data.model.Song
import com.music.orb.data.model.artworkAt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import kotlin.math.max

/** Shared glass-card artwork for the sheet preview and portrait exports. */
object SongShareStoryRenderer {
    const val WIDTH = 1080
    const val HEIGHT = 1080
    fun link(song: Song): String = "https://music.youtube.com/watch?v=${Uri.encode(song.videoId)}"

    suspend fun render(context: Context, song: Song, story: Boolean = false, cardOnly: Boolean = false): Bitmap = withContext(Dispatchers.IO) {
        val artwork = runCatching {
            val connection = URL(song.artworkAt(1200)).openConnection()
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.getInputStream().use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
        if (cardOnly) return@withContext renderGlassCardPreview(song, artwork)
        if (story) return@withContext renderStory(context, song, artwork)
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val rect = RectF(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat())
        canvas.drawColor(Color.rgb(23, 35, 43))
        if (artwork != null) {
            drawCenterCrop(canvas, artwork, rect, paint)
            val small = Bitmap.createScaledBitmap(artwork, 90, 90, true)
            val blurred = boxBlur(small, 7)
            val layer = canvas.saveLayer(rect, null)
            drawCenterCrop(canvas, blurred, rect, paint)
            paint.shader = LinearGradient(0f, HEIGHT * .52f, 0f, HEIGHT * .80f,
                Color.TRANSPARENT, Color.WHITE, Shader.TileMode.CLAMP)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            canvas.drawRect(rect, paint)
            paint.shader = null
            paint.xfermode = null
            canvas.restoreToCount(layer)
            small.recycle()
            blurred.recycle()
            artwork.recycle()
        }
        paint.shader = LinearGradient(0f, HEIGHT * .53f, 0f, HEIGHT.toFloat(),
            Color.TRANSPARENT, Color.argb(180, 0, 8, 14), Shader.TileMode.CLAMP)
        canvas.drawRect(rect, paint)
        paint.shader = null
        paint.color = Color.WHITE
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        paint.textSize = 58f
        canvas.drawText(ellipsize(song.title, paint, WIDTH - 112f), 56f, HEIGHT - 220f, paint)
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        paint.textSize = 40f
        paint.color = Color.rgb(232, 238, 241)
        canvas.drawText(ellipsize(song.artist, paint, WIDTH - 112f), 56f, HEIGHT - 150f, paint)
        paint.textSize = 34f
        val album = listOfNotNull(song.albumName?.takeIf { it.isNotBlank() }, song.releaseYear?.toString()).joinToString(" · ")
        if (album.isNotBlank()) canvas.drawText(ellipsize(album, paint, WIDTH - 112f), 56f, HEIGHT - 92f, paint)
        drawBrandFooter(context, bitmap, canvas, 1006f, 28f, 20f)
        bitmap
    }

    /** Vertical export: original square cover, caption and glass frame in safe central area. */
    private fun renderStory(context: Context, song: Song, artwork: Bitmap?): Bitmap {
        val output = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val background = RectF(0f, 0f, 1080f, 1920f)
        canvas.drawColor(Color.rgb(30, 38, 55))
        if (artwork != null) {
            val small = Bitmap.createScaledBitmap(artwork, 64, 64, true)
            val first = boxBlur(small, 12)
            val blurred = boxBlur(first, 12)
            drawCenterCrop(canvas, blurred, background, paint)
            small.recycle(); first.recycle(); blurred.recycle()
        }
        drawGlassCard(canvas, song, artwork)
        drawBrandFooter(context, output, canvas, 1660f, 56f, 28f)
        artwork?.recycle()
        return output
    }

    /** Only the framed story card; transparent outside its rounded outline. */
    private fun renderGlassCardPreview(song: Song, artwork: Bitmap?): Bitmap {
        val output = Bitmap.createBitmap(872, 1108, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.translate(-104f, -406f)
        // Without the blurred story backdrop, a translucent tint inside the frame
        // keeps the white caption readable over either sheet theme.
        val tint = Paint(Paint.ANTI_ALIAS_FLAG)
        val sampled = artwork?.getPixel(artwork.width / 2, artwork.height - 1)
            ?: Color.rgb(30, 38, 55)
        tint.color = Color.argb(220, (Color.red(sampled) * .25f).toInt(),
            (Color.green(sampled) * .25f).toInt(), (Color.blue(sampled) * .25f).toInt())
        canvas.drawRoundRect(RectF(104f, 406f, 976f, 1514f), 44f, 44f, tint)
        drawGlassCard(canvas, song, artwork)
        artwork?.recycle()
        return output
    }

    private fun drawGlassCard(canvas: Canvas, song: Song, artwork: Bitmap?) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val frame = RectF(104f, 406f, 976f, 1514f)
        paint.color = Color.argb(48, 255, 255, 255)
        canvas.drawRoundRect(frame, 44f, 44f, paint)
        paint.shader = LinearGradient(0f, 1230f, 0f, 1514f,
            Color.TRANSPARENT, Color.argb(100, 0, 12, 28), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(frame, 44f, 44f, paint)
        paint.shader = null
        val cover = RectF(136f, 438f, 944f, 1246f)
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(cover, 26f, 26f, Path.Direction.CW) })
        paint.color = Color.WHITE
        if (artwork != null) drawCenterCrop(canvas, artwork, cover, paint)
        canvas.restore()
        paint.color = Color.WHITE
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        paint.textSize = 56f
        // Reserve actual space for E inside the same title row in Story/Status
        // exports and in the sharing preview (both use this card renderer).
        drawStoryTitleWithExplicitBadge(canvas, song, paint)
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        paint.textSize = 40f
        canvas.drawText(ellipsize(song.artist, paint, 780f), 152f, 1390f, paint)
        paint.color = Color.argb(190, 255, 255, 255)
        paint.textSize = 32f
        val album = listOfNotNull(song.albumName?.takeIf { it.isNotBlank() }, song.releaseYear?.toString()).joinToString(" · ")
        canvas.drawText(ellipsize(album, paint, 780f), 152f, 1450f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.shader = LinearGradient(104f, 406f, 976f, 1514f,
            Color.argb(210, 255, 255, 255), Color.argb(95, 255, 255, 255), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(frame, 44f, 44f, paint)
    }

    /** Compact, high-contrast E marker immediately following the visible title. */
    private fun drawStoryTitleWithExplicitBadge(canvas: Canvas, song: Song, titlePaint: Paint) {
        val left = 152f
        val baseline = 1330f
        val badgeWidth = 38f
        val badgeGap = 12f
        val reserved = if (song.isExplicit) badgeWidth + badgeGap else 0f
        val displayedTitle = ellipsize(song.title, titlePaint, 780f - reserved)
        canvas.drawText(displayedTitle, left, baseline, titlePaint)
        if (!song.isExplicit) return

        val badgeLeft = left + titlePaint.measureText(displayedTitle) + badgeGap
        // Center the seal on the *visible glyphs*, not on a guessed offset from
        // the text baseline. Descenders (e.g. the "y" in "Pyramids") otherwise
        // make a fixed-position E appear slightly too high alongside the title.
        val measuredTitle = displayedTitle.ifBlank { "H" }
        val titleGlyphBounds = Rect()
        titlePaint.getTextBounds(measuredTitle, 0, measuredTitle.length, titleGlyphBounds)
        val visualTitleCenterY = baseline + (titleGlyphBounds.top + titleGlyphBounds.bottom) / 2f
        val badgeHeight = 37f
        val badgeTop = visualTitleCenterY - badgeHeight / 2f
        val bounds = RectF(badgeLeft, badgeTop, badgeLeft + badgeWidth, badgeTop + badgeHeight)
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(222, 241, 241, 241)
        }
        canvas.drawRoundRect(bounds, 7f, 7f, badgePaint)
        badgePaint.apply {
            color = Color.rgb(28, 28, 28)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            textSize = 27f
            textAlign = Paint.Align.CENTER
        }
        val centeredBaseline = bounds.centerY() - (badgePaint.ascent() + badgePaint.descent()) / 2f
        canvas.drawText("E", bounds.centerX(), centeredBaseline, badgePaint)
    }

    private fun drawBrandFooter(
        context: Context,
        bitmap: Bitmap,
        canvas: Canvas,
        top: Float,
        logoHeight: Float,
        textSize: Float,
    ) {
        val dark = Color.luminance(bitmap.getPixel(bitmap.width / 2, top.toInt())) < 0.45f
        val resource = if (dark) R.drawable.orb_share_wordmark_light else R.drawable.orb_share_wordmark_dark
        val logo = BitmapFactory.decodeResource(context.resources, resource) ?: return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val width = logoHeight * logo.width.toFloat() / logo.height
        val left = (bitmap.width - width) / 2f
        canvas.drawBitmap(logo, null, RectF(left, top, left + width, top + logoHeight), paint)
        logo.recycle()
        paint.color = if (dark) Color.WHITE else Color.BLACK
        paint.textSize = textSize
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(context.getString(R.string.song_share_android_exclusive),
            bitmap.width / 2f, top + logoHeight + textSize + 8f, paint)
    }

    suspend fun prepare(context: Context, bitmap: Bitmap): Uri = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir, "song_share").apply { mkdirs() }
        val file = File(folder, "song-${java.util.UUID.randomUUID()}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        FileProvider.getUriForFile(context, "${context.packageName}.orb-updates.files", file)
    }

    fun sharePrepared(
        context: Context,
        song: Song,
        uri: Uri,
        targetPackage: String? = null,
        shareLinkOverride: String? = null,
    ) {
        if (targetPackage == "com.instagram.android") {
            val storyIntent = Intent("com.instagram.share.ADD_TO_STORY").apply {
                setDataAndType(uri, "image/png")
                setPackage(targetPackage)
                clipData = android.content.ClipData.newUri(context.contentResolver, "song-story", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try { context.startActivity(storyIntent); return }
            catch (_: android.content.ActivityNotFoundException) { /* Fall back to image sharing. */ }
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "${song.title} — ${song.artist}\n${shareLinkOverride ?: link(song)}")
            putExtra(Intent.EXTRA_SUBJECT, song.title)
            clipData = android.content.ClipData.newUri(context.contentResolver, "song-share", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (targetPackage != null) {
            intent.setPackage(targetPackage)
            try { context.startActivity(intent); return } catch (_: android.content.ActivityNotFoundException) {
                intent.setPackage(null)
            }
        }
        context.startActivity(Intent.createChooser(intent, song.title))
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
