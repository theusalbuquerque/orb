package com.music.orb.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.DrawableRes
import androidx.core.content.FileProvider
import com.music.orb.data.canvas.ArtistArtworkRepository
import com.music.orb.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Colors captured from the user's active Material 3 color scheme. */
data class StatsSharePalette(
    val background: Int,
    val surface: Int,
    val surfaceHigh: Int,
    val primary: Int,
    val primaryContainer: Int,
    val secondaryContainer: Int,
    val tertiaryContainer: Int,
    val onBackground: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val outline: Int,
)

object StatsShareStoryRenderer {
    const val WIDTH = 1080
    const val HEIGHT = 1920

    suspend fun render(
        context: Context,
        snapshot: StatsShareSnapshot,
        palette: StatsSharePalette,
    ): Bitmap = withContext(Dispatchers.IO) {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val bold = Typeface.create("sans-serif", Typeface.BOLD)
        val regular = Typeface.create("sans-serif", Typeface.NORMAL)

        fun mix(colorA: Int, colorB: Int, amount: Float): Int {
            val t = amount.coerceIn(0f, 1f)
            val inv = 1f - t
            return Color.argb(
                (Color.alpha(colorA) * inv + Color.alpha(colorB) * t).toInt().coerceIn(0, 255),
                (Color.red(colorA) * inv + Color.red(colorB) * t).toInt().coerceIn(0, 255),
                (Color.green(colorA) * inv + Color.green(colorB) * t).toInt().coerceIn(0, 255),
                (Color.blue(colorA) * inv + Color.blue(colorB) * t).toInt().coerceIn(0, 255),
            )
        }

        fun withAlpha(color: Int, alpha: Float): Int =
            Color.argb((alpha.coerceIn(0f, 1f) * 255f).toInt(), Color.red(color), Color.green(color), Color.blue(color))

        fun luminance(color: Int): Float {
            fun c(channel: Int): Float {
                val value = channel / 255f
                return if (value <= 0.03928f) value / 12.92f else Math.pow(((value + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
            }
            return 0.2126f * c(Color.red(color)) + 0.7152f * c(Color.green(color)) + 0.0722f * c(Color.blue(color))
        }

        fun capitalizeFirst(value: String): String =
            value.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

        fun trimTransparent(bitmap: Bitmap?): Bitmap? {
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
            if (right <= left || bottom <= top) return bitmap
            return Bitmap.createBitmap(bitmap, left, top, right - left + 1, bottom - top + 1)
        }

        val bg0 = mix(palette.background, Color.BLACK, 0.86f)
        val bg1 = mix(palette.primaryContainer, Color.BLACK, 0.58f)
        val bg2 = mix(palette.secondaryContainer, Color.BLACK, 0.64f)
        val bg3 = mix(palette.tertiaryContainer, Color.BLACK, 0.68f)
        val cardColor = withAlpha(mix(palette.surfaceHigh, Color.BLACK, 0.42f), 0.84f)
        val featuredCardColor = withAlpha(mix(palette.tertiaryContainer, Color.BLACK, 0.24f), 0.84f)
        val rhythmCardColor = withAlpha(mix(palette.primaryContainer, Color.BLACK, 0.34f), 0.84f)
        val accentCardColor = withAlpha(mix(palette.secondaryContainer, Color.BLACK, 0.28f), 0.84f)
        val outlineColor = withAlpha(mix(palette.outline, Color.WHITE, 0.18f), 0.58f)
        val textPrimary = Color.WHITE
        val textSecondary = withAlpha(Color.WHITE, 0.82f)
        val textTertiary = withAlpha(Color.WHITE, 0.64f)

        paint.shader = LinearGradient(
            0f,
            0f,
            WIDTH.toFloat(),
            HEIGHT.toFloat(),
            intArrayOf(bg1, bg0, bg2, bg3),
            floatArrayOf(0f, 0.34f, 0.72f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
        paint.shader = LinearGradient(
            0f,
            0f,
            0f,
            HEIGHT.toFloat(),
            intArrayOf(withAlpha(Color.WHITE, 0.04f), Color.TRANSPARENT, withAlpha(Color.BLACK, 0.12f)),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
        paint.shader = null

        fun card(rect: RectF, color: Int = cardColor, radius: Float = 34f) {
            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.color = color
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.2f
            paint.color = outlineColor
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.style = Paint.Style.FILL
        }

        fun text(
            value: String,
            x: Float,
            y: Float,
            size: Float,
            color: Int = textPrimary,
            isBold: Boolean = false,
            maxWidth: Float? = null,
            maxLines: Int = Int.MAX_VALUE,
            lineSpacing: Float = 1.12f,
        ): Float {
            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.color = color
            paint.textSize = size
            paint.typeface = if (isBold) bold else regular
            if (maxWidth == null) {
                canvas.drawText(value, x, y, paint)
                return y + size * lineSpacing
            }
            val tokens = value.trim().split(Regex("""\s+"""))
            if (tokens.isEmpty()) return y
            var line = ""
            var yy = y
            var used = 0
            fun commit(raw: String, ellipsize: Boolean = false) {
                var draw = raw.trim()
                if (ellipsize) {
                    val suffix = "…"
                    while (draw.isNotEmpty() && paint.measureText(draw + suffix) > maxWidth) draw = draw.dropLast(1)
                    draw = draw.trimEnd() + suffix
                }
                if (draw.isNotBlank()) {
                    canvas.drawText(draw, x, yy, paint)
                    yy += size * lineSpacing
                    used++
                }
            }
            for (token in tokens) {
                val candidate = if (line.isBlank()) token else "$line $token"
                if (paint.measureText(candidate) <= maxWidth) {
                    line = candidate
                } else {
                    if (used >= maxLines - 1) {
                        commit(line.ifBlank { token }, ellipsize = true)
                        return yy
                    }
                    commit(line)
                    line = token
                }
            }
            if (line.isNotBlank() && used < maxLines) commit(line, ellipsize = false)
            return yy
        }

        suspend fun image(url: String?): Bitmap? {
            if (url.isNullOrBlank()) return null
            return runCatching {
                val uri = Uri.parse(url)
                when (uri.scheme) {
                    "content", "file", "android.resource" -> context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    "http", "https" -> URL(url).openConnection().apply {
                        connectTimeout = 7000
                        readTimeout = 7000
                    }.getInputStream().use { BitmapFactory.decodeStream(it) }
                    else -> null
                }
            }.getOrNull()
        }

        fun resourceBitmap(@DrawableRes resId: Int): Bitmap? =
            runCatching { BitmapFactory.decodeResource(context.resources, resId) }.getOrNull()

        fun cover(source: Bitmap?, rect: RectF, radius: Float = 24f) {
            paint.alpha = 255
            paint.color = withAlpha(Color.WHITE, 0.06f)
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(rect, radius, radius, paint)
            if (source == null) return
            val scale = max(rect.width() / source.width.toFloat(), rect.height() / source.height.toFloat())
            val sw = (rect.width() / scale).toInt().coerceAtLeast(1)
            val sh = (rect.height() / scale).toInt().coerceAtLeast(1)
            val sx = ((source.width - sw) / 2).coerceAtLeast(0)
            val sy = ((source.height - sh) / 2).coerceAtLeast(0)
            val src = android.graphics.Rect(sx, sy, min(source.width, sx + sw), min(source.height, sy + sh))
            val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                isFilterBitmap = true
                alpha = 255
            }
            canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) })
            canvas.drawBitmap(source, src, rect, bitmapPaint)
            canvas.restore()
        }

        fun drawChip(rect: RectF, label: String, selected: Boolean) {
            val fill = if (selected) withAlpha(Color.WHITE, 0.90f) else withAlpha(Color.WHITE, 0.08f)
            val stroke = if (selected) withAlpha(mix(palette.primary, Color.WHITE, 0.12f), 0.92f) else withAlpha(Color.WHITE, 0.24f)
            val labelColor = if (selected) mix(bg0, Color.BLACK, 0.08f) else textSecondary
            paint.style = Paint.Style.FILL
            paint.color = fill
            canvas.drawRoundRect(rect, 28f, 28f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = stroke
            canvas.drawRoundRect(rect, 28f, 28f, paint)
            paint.textSize = 18f
            paint.typeface = bold
            paint.style = Paint.Style.FILL
            val textWidth = paint.measureText(label)
            text(label, rect.centerX() - textWidth / 2f, rect.centerY() + 9f, 18f, labelColor, true)
        }

        fun formatTime(ms: Long): String {
            val totalMin = (ms.coerceAtLeast(0L) / 60_000L)
            val h = totalMin / 60
            val m = totalMin % 60
            return if (h > 0) "%02dh%02d".format(h, m) else "%02dmin".format(m)
        }

        fun periodTitle(period: StatsPeriod): String = capitalizeFirst(context.getString(
            when (period) {
                StatsPeriod.WEEK -> R.string.stats_share_story_title_week
                StatsPeriod.MONTH -> R.string.stats_share_story_title_month
                StatsPeriod.QUARTER -> R.string.stats_share_story_title_quarter
                StatsPeriod.SEMESTER -> R.string.stats_share_story_title_semester
                StatsPeriod.YEAR -> R.string.stats_share_story_title_year
            },
        ))

        fun periodLabel(period: StatsPeriod): String = capitalizeFirst(when (period) {
            StatsPeriod.WEEK -> context.getString(R.string.stats_period_week)
            StatsPeriod.MONTH -> context.getString(R.string.stats_period_month)
            StatsPeriod.QUARTER -> context.getString(R.string.stats_period_quarter)
            StatsPeriod.SEMESTER -> context.getString(R.string.stats_period_semester)
            StatsPeriod.YEAR -> context.getString(R.string.stats_period_year)
        })

        fun monthShort(date: LocalDate): String =
            date.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

        fun rangeLabel(start: LocalDate, end: LocalDate): String {
            return when {
                start == end -> start.dayOfMonth.toString()
                start.month == end.month -> "${start.dayOfMonth}–${end.dayOfMonth}"
                else -> "${start.dayOfMonth}/${start.monthValue}–${end.dayOfMonth}/${end.monthValue}"
            }
        }

        fun sparseStoryLabels(labels: List<String>, maxVisible: Int): List<String> {
            if (labels.size <= maxVisible) return labels
            val effectiveSlots = maxVisible.coerceAtLeast(2)
            val step = ceil((labels.size - 1).toDouble() / (effectiveSlots - 1).toDouble()).toInt().coerceAtLeast(1)
            return labels.mapIndexed { index, label ->
                val keep = index == 0 || index == labels.lastIndex || index % step == 0
                if (keep) label else ""
            }
        }

        fun storyChartLabels(snapshot: StatsShareSnapshot, pointCount: Int): List<String> {
            if (pointCount <= 0) return emptyList()
            val start = snapshot.periodStart
            val end = snapshot.periodEnd
            val base = when (snapshot.period) {
                StatsPeriod.WEEK -> List(pointCount) { index ->
                    val day = start.plusDays(index.toLong())
                    day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                }

                StatsPeriod.MONTH -> List(pointCount) { index ->
                    val blockStart = start.plusDays((index * 7L))
                    val blockEnd = minOf(blockStart.plusDays(6), end)
                    rangeLabel(blockStart, blockEnd)
                }

                StatsPeriod.QUARTER -> List(pointCount) { index ->
                    val weekStart = start.plusDays((index * 7L))
                    val weekEnd = minOf(weekStart.plusDays(6), end)
                    if (weekStart > end) "" else rangeLabel(weekStart, weekEnd)
                }

                StatsPeriod.SEMESTER -> List(pointCount) { index ->
                    monthShort(start.plusMonths(index.toLong()))
                }

                StatsPeriod.YEAR -> List(pointCount) { index ->
                    monthShort(start.plusMonths(index.toLong()))
                }
            }

            return when (snapshot.period) {
                StatsPeriod.WEEK -> base
                StatsPeriod.MONTH -> sparseStoryLabels(base, maxVisible = 5)
                StatsPeriod.QUARTER -> sparseStoryLabels(base, maxVisible = 5)
                StatsPeriod.SEMESTER -> sparseStoryLabels(base, maxVisible = 6)
                StatsPeriod.YEAR -> sparseStoryLabels(base, maxVisible = 6)
            }
        }

        val dateFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.getDefault())
        val logoRes = if (luminance(bg0) < 0.42f) R.drawable.orb_share_logo_light else R.drawable.orb_share_logo_dark
        val logo = trimTransparent(resourceBitmap(logoRes))

        suspend fun artworkForLeader(leader: StatsLeader?, preferArtistPortrait: Boolean = false): Bitmap? {
            if (leader == null) return null
            image(leader.artworkUrl)?.let { return it }
            if (preferArtistPortrait) {
                val resolved = runCatching {
                    ArtistArtworkRepository.cached(leader.title) ?: ArtistArtworkRepository.resolve(leader.title)
                }.getOrNull()
                image(resolved)?.let { return it }
            }
            return null
        }

        suspend fun drawListCard(
            rect: RectF,
            title: String,
            leaders: List<StatsLeader>,
            circularArt: Boolean,
            showSubtitle: Boolean,
            maxItems: Int = 5,
            rankStart: Int = 1,
        ) {
            card(rect)
            text(capitalizeFirst(title), rect.left + 20f, rect.top + 38f, 22f, textPrimary, true, rect.width() - 64f, 1)
            val items = leaders.take(maxItems)
            val startY = rect.top + 72f
            val innerBottom = rect.bottom - 20f
            val rowGap = 10f
            val rowHeight = ((innerBottom - startY) - rowGap * (items.size - 1).coerceAtLeast(0)) / items.size.coerceAtLeast(1)
            items.forEachIndexed { index, leader ->
                val rowTop = startY + index * (rowHeight + rowGap)
                val artSize = if (circularArt) 58f else 64f
                val baseY = rowTop + 36f
                text("${index + rankStart}", rect.left + 18f, baseY, 18f, textSecondary, true)
                val artLeft = rect.left + 44f
                val artRect = RectF(artLeft, rowTop, artLeft + artSize, rowTop + artSize)
                cover(artworkForLeader(leader, preferArtistPortrait = circularArt), artRect, if (circularArt) artSize / 2f else 14f)
                val textX = artRect.right + 14f
                val textWidth = rect.right - textX - 18f
                val subtitleText = when {
                    showSubtitle -> leader.subtitle?.takeIf { it.isNotBlank() }
                    circularArt -> null
                    else -> leader.subtitle?.takeIf { it.isNotBlank() }
                }
                if (subtitleText == null) {
                    // With play counts removed, keep the name optically centered beside the artwork.
                    text(
                        leader.title,
                        textX,
                        rowTop + artSize * 0.60f,
                        18f,
                        textPrimary,
                        true,
                        textWidth,
                        2,
                        1.02f,
                    )
                } else {
                    val nextY = text(leader.title, textX, rowTop + 19f, 18f, textPrimary, true, textWidth, 1, 1.02f)
                    text(subtitleText, textX, nextY + 3f, 15f, textSecondary, false, textWidth, 1, 1.02f)
                }
            }
        }

        suspend fun drawAlbumListCard(
            rect: RectF,
            title: String,
            leaders: List<StatsLeader>,
        ) {
            card(rect)
            text(capitalizeFirst(title), rect.left + 20f, rect.top + 38f, 22f, textPrimary, true, rect.width() - 40f, 1)
            val items = leaders.take(5)
            val startY = rect.top + 72f
            val innerBottom = rect.bottom - 18f
            val rowGap = 8f
            val rowHeight = ((innerBottom - startY) - rowGap * (items.size - 1).coerceAtLeast(0)) / items.size.coerceAtLeast(1)
            items.forEachIndexed { index, leader ->
                val rowTop = startY + index * (rowHeight + rowGap)
                text("${index + 1}", rect.left + 18f, rowTop + 32f, 17f, textSecondary, true)
                val artSize = min(58f, rowHeight - 4f)
                val artRect = RectF(rect.left + 44f, rowTop, rect.left + 44f + artSize, rowTop + artSize)
                cover(artworkForLeader(leader), artRect, 13f)
                val textX = artRect.right + 14f
                val textWidth = rect.right - textX - 16f
                val subtitle = leader.subtitle?.takeIf { it.isNotBlank() }
                val titleY = if (subtitle == null) rowTop + artSize * 0.60f else rowTop + 20f
                val nextY = text(leader.title, textX, titleY, 17f, textPrimary, true, textWidth, 2, 1.0f)
                if (subtitle != null) {
                    text(subtitle, textX, min(rowTop + rowHeight - 9f, nextY + 3f), 12.5f, textSecondary, false, textWidth, 1)
                }
            }
        }

        suspend fun drawSongGridCard(
            rect: RectF,
            title: String,
            leaders: List<StatsLeader>,
        ) {
            card(rect)
            text(capitalizeFirst(title), rect.left + 20f, rect.top + 38f, 22f, textPrimary, true, rect.width() - 40f, 1)
            val items = leaders.take(10)
            val columnGap = 18f
            val contentLeft = rect.left + 18f
            val contentRight = rect.right - 18f
            val columnWidth = (contentRight - contentLeft - columnGap) / 2f
            val startY = rect.top + 72f
            val innerBottom = rect.bottom - 18f
            val rows = 5
            val rowGap = 8f
            val rowHeight = ((innerBottom - startY) - rowGap * (rows - 1)) / rows

            items.forEachIndexed { index, leader ->
                // Rank order reads naturally across each row: 1–2, 3–4,
                // 5–6, 7–8 and 9–10. Do not fill an entire column first.
                val column = index % 2
                val row = index / 2
                val columnLeft = contentLeft + column * (columnWidth + columnGap)
                val rowTop = startY + row * (rowHeight + rowGap)
                text("${index + 1}", columnLeft, rowTop + 30f, 16f, textSecondary, true)
                val artSize = min(52f, rowHeight - 4f)
                val artRect = RectF(columnLeft + 27f, rowTop, columnLeft + 27f + artSize, rowTop + artSize)
                cover(artworkForLeader(leader), artRect, 12f)
                val textX = artRect.right + 11f
                val textWidth = columnLeft + columnWidth - textX
                val subtitle = leader.subtitle?.takeIf { it.isNotBlank() }
                val titleY = if (subtitle == null) rowTop + artSize * 0.60f else rowTop + 18f
                val nextY = text(leader.title, textX, titleY, 15.5f, textPrimary, true, textWidth, 2, 0.98f)
                if (subtitle != null) {
                    text(subtitle, textX, min(rowTop + rowHeight - 7f, nextY + 2f), 11.5f, textSecondary, false, textWidth, 1)
                }
            }
        }

        fun metricPill(label: String, value: String, rect: RectF) {
            paint.style = Paint.Style.FILL
            paint.color = withAlpha(Color.WHITE, 0.08f)
            canvas.drawRoundRect(rect, 22f, 22f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.6f
            paint.color = withAlpha(Color.WHITE, 0.16f)
            canvas.drawRoundRect(rect, 22f, 22f, paint)
            paint.style = Paint.Style.FILL
            text(label, rect.left + 14f, rect.top + 23f, 14f, textTertiary, false, rect.width() - 28f, 1)
            text(value, rect.left + 14f, rect.top + 56f, 22f, textPrimary, true, rect.width() - 28f, 1)
        }

        fun drawLegendDot(x: Float, y: Float, color: Int, label: String) {
            paint.style = Paint.Style.FILL
            paint.color = color
            canvas.drawCircle(x, y, 7f, paint)
            text(label, x + 16f, y + 6f, 15f, textSecondary)
        }

        fun drawSeriesChart(
            rect: RectF,
            current: List<Int>,
            previous: List<Int>,
            currentColor: Int,
            previousColor: Int,
        ) {
            val pointCount = max(current.size, previous.size).coerceAtLeast(2)
            fun normalize(values: List<Int>): List<Int> = when {
                values.size == pointCount -> values
                values.isEmpty() -> List(pointCount) { 0 }
                values.size == 1 -> List(pointCount) { values.first() }
                else -> List(pointCount) { index ->
                    values[((index.toFloat() / (pointCount - 1)) * (values.size - 1)).toInt().coerceIn(0, values.lastIndex)]
                }
            }
            val currentValues = normalize(current)
            val previousValues = normalize(previous)
            val maxValue = (currentValues + previousValues).maxOrNull()?.coerceAtLeast(1) ?: 1

            val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.4f
                color = withAlpha(Color.WHITE, 0.14f)
            }
            repeat(4) { index ->
                val y = rect.top + rect.height() * index / 3f
                canvas.drawLine(rect.left, y, rect.right, y, gridPaint)
            }
            repeat(pointCount) { index ->
                val x = if (pointCount <= 1) {
                    rect.left + rect.width() / 2f
                } else {
                    rect.left + rect.width() * index / (pointCount - 1).toFloat()
                }
                canvas.drawLine(x, rect.top, x, rect.bottom, gridPaint)
            }

            fun createPoints(values: List<Int>): List<Pair<Float, Float>> = values.mapIndexed { index, value ->
                val x = rect.left + rect.width() * index / (pointCount - 1).toFloat()
                val fraction = value.toFloat() / maxValue.toFloat()
                val y = rect.bottom - rect.height() * (0.08f + fraction * 0.84f)
                x to y
            }

            fun smooth(points: List<Pair<Float, Float>>): Path {
                val path = Path()
                if (points.isEmpty()) return path
                path.moveTo(points.first().first, points.first().second)
                if (points.size == 1) return path
                for (i in 0 until points.lastIndex) {
                    val p0 = points[(i - 1).coerceAtLeast(0)]
                    val p1 = points[i]
                    val p2 = points[i + 1]
                    val p3 = points[(i + 2).coerceAtMost(points.lastIndex)]
                    val c1x = p1.first + (p2.first - p0.first) / 6f
                    val c1y = p1.second + (p2.second - p0.second) / 6f
                    val c2x = p2.first - (p3.first - p1.first) / 6f
                    val c2y = p2.second - (p3.second - p1.second) / 6f
                    path.cubicTo(c1x, c1y, c2x, c2y, p2.first, p2.second)
                }
                return path
            }

            val currentPoints = createPoints(currentValues)
            val previousPoints = createPoints(previousValues)
            val previousPath = smooth(previousPoints)
            val currentPath = smooth(currentPoints)
            val fillPath = Path(currentPath).apply {
                lineTo(rect.right, rect.bottom)
                lineTo(rect.left, rect.bottom)
                close()
            }

            paint.shader = LinearGradient(rect.left, rect.top, rect.left, rect.bottom, withAlpha(currentColor, 0.42f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            paint.style = Paint.Style.FILL
            canvas.drawPath(fillPath, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = withAlpha(previousColor, 0.92f)
            paint.strokeWidth = 6f
            canvas.drawPath(previousPath, paint)
            paint.color = currentColor
            paint.strokeWidth = 8f
            canvas.drawPath(currentPath, paint)
            paint.style = Paint.Style.FILL
        }

        val topArtist = snapshot.stats.topArtist ?: snapshot.topArtists.firstOrNull()
        val topArtistArt = artworkForLeader(topArtist, preferArtistPortrait = true)
        val topSongs = snapshot.topSongs.take(10)
        val topAlbums = snapshot.topAlbums.take(5)
        val otherTopArtists = snapshot.topArtists
            .filterNot { candidate -> topArtist?.title?.let { candidate.title.equals(it, ignoreCase = true) } == true }
            .take(4)

        // Header
        logo?.let {
            val targetHeight = 64f
            val targetWidth = targetHeight * (it.width.toFloat() / it.height.toFloat())
            val logoRect = RectF(58f, 64f, 58f + targetWidth, 64f + targetHeight)
            paint.alpha = 255
            canvas.drawBitmap(it, null, logoRect, paint)
        }

        val chipLabel = context.getString(R.string.stats_share_story_period_picker_label)
        text(chipLabel, 790f, 76f, 18f, textSecondary, false, 236f, 1)
        val chipWidth = 108f
        val chipHeight = 52f
        val chipGap = 10f
        val chips = listOf(StatsPeriod.WEEK, StatsPeriod.MONTH, StatsPeriod.QUARTER, StatsPeriod.SEMESTER, StatsPeriod.YEAR)
        val chipsStartX = 410f
        chips.forEachIndexed { index, period ->
            val left = chipsStartX + index * (chipWidth + chipGap)
            drawChip(RectF(left, 88f, left + chipWidth, 88f + chipHeight), periodLabel(period), period == snapshot.period)
        }
        text(
            "${snapshot.periodStart.format(dateFormatter)} — ${snapshot.periodEnd.format(dateFormatter)}",
            796f,
            202f,
            18f,
            textSecondary,
            false,
            240f,
            2,
            1.10f,
        )

        // Keep the story headline on one line in every supported locale/period.
        val storyTitle = periodTitle(snapshot.period)
        paint.typeface = bold
        var storyTitleSize = 72f
        val storyTitleMaxWidth = WIDTH - 112f
        while (storyTitleSize > 50f) {
            paint.textSize = storyTitleSize
            if (paint.measureText(storyTitle) <= storyTitleMaxWidth) break
            storyTitleSize -= 2f
        }
        text(storyTitle, 56f, 286f, storyTitleSize, textPrimary, true)

        val margin = 36f
        val gap = 16f

        // Row 1: featured artist + remaining four artists + compact taste cards.
        val topY = 330f
        val topH = 400f
        val featuredW = 300f
        val artistsW = 330f
        val rightW = WIDTH - margin * 2f - featuredW - artistsW - gap * 2f
        val tasteH = (topH - gap) / 2f

        val featuredRect = RectF(margin, topY, margin + featuredW, topY + topH)
        val artistsRect = RectF(featuredRect.right + gap, topY, featuredRect.right + gap + artistsW, topY + topH)
        val genreRect = RectF(artistsRect.right + gap, topY, artistsRect.right + gap + rightW, topY + tasteH)
        val languageRect = RectF(genreRect.left, genreRect.bottom + gap, genreRect.right, topY + topH)

        // Row 2: wider albums card + substantially wider ten-song card.
        val secondY = topY + topH + gap
        val secondH = 450f
        val albumsW = 400f
        val albumsRect = RectF(margin, secondY, margin + albumsW, secondY + secondH)
        val songsRect = RectF(albumsRect.right + gap, secondY, WIDTH - margin, secondY + secondH)

        // Row 3: one full-width rhythm card for a much larger chart.
        val rhythmY = secondY + secondH + gap
        val rhythmH = 420f
        val rhythmRect = RectF(margin, rhythmY, WIDTH - margin, rhythmY + rhythmH)
        val footerY = rhythmRect.bottom + 48f

        // Featured artist card.
        card(featuredRect, featuredCardColor, 36f)
        val imageRect = RectF(featuredRect.left + 1f, featuredRect.top + 1f, featuredRect.right - 1f, featuredRect.bottom - 1f)
        cover(topArtistArt, imageRect, 36f)
        paint.shader = LinearGradient(
            featuredRect.left,
            featuredRect.top + featuredRect.height() * 0.46f,
            featuredRect.left,
            featuredRect.bottom,
            Color.TRANSPARENT,
            withAlpha(Color.BLACK, 0.90f),
            Shader.TileMode.CLAMP,
        )
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(featuredRect, 36f, 36f, paint)
        paint.shader = null
        text(capitalizeFirst(context.getString(R.string.stats_top_artist)), featuredRect.left + 18f, featuredRect.top + 34f, 17f, textSecondary, true)
        if (topArtist != null) {
            val textShadeRect = RectF(featuredRect.left + 1f, featuredRect.bottom - 176f, featuredRect.right - 1f, featuredRect.bottom - 1f)
            paint.shader = LinearGradient(
                textShadeRect.left,
                textShadeRect.top,
                textShadeRect.left,
                textShadeRect.bottom,
                intArrayOf(Color.TRANSPARENT, withAlpha(Color.BLACK, 0.46f), withAlpha(Color.BLACK, 0.88f)),
                floatArrayOf(0f, 0.40f, 1f),
                Shader.TileMode.CLAMP,
            )
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(textShadeRect, 32f, 32f, paint)
            paint.shader = null
            val brief = formatTime(topArtist.listenedMs)
            text(topArtist.title, featuredRect.left + 18f, featuredRect.bottom - 126f, 23f, textPrimary, true, featuredRect.width() - 36f, 2, 1.02f)
            text(brief, featuredRect.left + 18f, featuredRect.bottom - 78f, 15f, textSecondary, false, featuredRect.width() - 36f, 1)
            text(
                context.getString(R.string.stats_share_story_favorite_artist_period, periodLabel(snapshot.period)),
                featuredRect.left + 18f,
                featuredRect.bottom - 50f,
                14f,
                textTertiary,
                false,
                featuredRect.width() - 36f,
                1,
            )
            text(capitalizeFirst(context.getString(R.string.stats_source_orb)), featuredRect.left + 18f, featuredRect.bottom - 22f, 13f, textTertiary)
        }

        // Remaining four artists sit immediately beside the #1 artist.
        drawListCard(
            rect = artistsRect,
            title = context.getString(R.string.stats_share_story_top_artists),
            leaders = otherTopArtists,
            circularArt = true,
            showSubtitle = false,
            maxItems = 4,
            rankStart = 2,
        )

        fun drawTasteCard(
            rect: RectF,
            singularLabelRes: Int,
            pluralLabelRes: Int,
            rankedValues: List<String>,
        ) {
            val values = rankedValues
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinctBy { it.lowercase(Locale.ROOT) }
                .take(5)
            card(rect, accentCardColor, 30f)
            text(
                capitalizeFirst(context.getString(if (values.size > 1) pluralLabelRes else singularLabelRes)),
                rect.left + 18f,
                rect.top + 30f,
                15f,
                textSecondary,
                true,
                rect.width() - 36f,
                1,
            )
            if (values.isEmpty()) {
                text("—", rect.left + 18f, rect.centerY() + 10f, 32f, textPrimary, true)
                return
            }

            fun centeredSingleLine(
                value: String,
                centerX: Float,
                baselineY: Float,
                preferredSize: Float,
                color: Int,
                boldText: Boolean,
                maxWidth: Float,
                minSize: Float = 11.5f,
            ) {
                paint.shader = null
                paint.style = Paint.Style.FILL
                paint.color = color
                paint.typeface = if (boldText) bold else regular
                var size = preferredSize
                paint.textSize = size
                while (size > minSize && paint.measureText(value) > maxWidth) {
                    size -= 0.5f
                    paint.textSize = size
                }
                val measured = paint.measureText(value)
                canvas.drawText(value, centerX - measured / 2f, baselineY, paint)
            }

            // #1 gets a clear visual hierarchy and is centered across the whole card.
            centeredSingleLine(
                value = "1. ${values.first()}",
                centerX = rect.centerX(),
                baselineY = rect.top + 76f,
                preferredSize = 25f,
                color = textPrimary,
                boldText = true,
                maxWidth = rect.width() - 44f,
                minSize = 16f,
            )

            // #2–#5 form a compact 2x2 grid beneath the winner.
            val grid = values.drop(1)
            val columnGap = 10f
            val gridLeft = rect.left + 14f
            val gridRight = rect.right - 14f
            val columnWidth = (gridRight - gridLeft - columnGap) / 2f
            val columnCenters = floatArrayOf(
                gridLeft + columnWidth / 2f,
                gridLeft + columnWidth + columnGap + columnWidth / 2f,
            )
            val rowBaselines = floatArrayOf(rect.top + 118f, rect.top + 151f)
            grid.forEachIndexed { index, value ->
                val row = index / 2
                val column = index % 2
                val rank = index + 2
                centeredSingleLine(
                    value = "$rank. $value",
                    centerX = columnCenters[column],
                    baselineY = rowBaselines[row],
                    preferredSize = 14.5f,
                    color = textSecondary,
                    boldText = false,
                    maxWidth = columnWidth - 8f,
                )
            }
        }

        drawTasteCard(
            rect = genreRect,
            singularLabelRes = R.string.stats_top_genre,
            pluralLabelRes = R.string.stats_top_genres,
            rankedValues = snapshot.stats.topGenres.ifEmpty { listOfNotNull(snapshot.stats.topGenre) },
        )
        drawTasteCard(
            rect = languageRect,
            singularLabelRes = R.string.stats_top_language,
            pluralLabelRes = R.string.stats_top_languages,
            rankedValues = snapshot.stats.topLanguages.ifEmpty { listOfNotNull(snapshot.stats.topLanguage) },
        )

        drawAlbumListCard(
            rect = albumsRect,
            title = context.getString(R.string.stats_share_story_top_albums),
            leaders = topAlbums,
        )
        drawSongGridCard(
            rect = songsRect,
            title = context.getString(R.string.stats_share_story_top_songs),
            leaders = topSongs,
        )

        // Full-width rhythm card.
        card(rhythmRect, rhythmCardColor, 36f)
        text(capitalizeFirst(context.getString(R.string.stats_summary_title)), rhythmRect.left + 20f, rhythmRect.top + 38f, 20f, textSecondary, true)
        val pillGap = 12f
        val pillLeft = rhythmRect.left + 20f
        val pillRight = rhythmRect.right - 20f
        val pillW = (pillRight - pillLeft - pillGap * 2f) / 3f
        val pillTop = rhythmRect.top + 54f
        val pillBottom = pillTop + 74f
        metricPill(capitalizeFirst(context.getString(R.string.stats_time)), formatTime(snapshot.stats.totalListenedMs), RectF(pillLeft, pillTop, pillLeft + pillW, pillBottom))
        metricPill(capitalizeFirst(context.getString(R.string.stats_tracks)), snapshot.stats.uniqueTracks.toString(), RectF(pillLeft + (pillW + pillGap), pillTop, pillLeft + (pillW + pillGap) + pillW, pillBottom))
        metricPill(capitalizeFirst(context.getString(R.string.stats_artists)), snapshot.stats.uniqueArtists.toString(), RectF(pillLeft + 2f * (pillW + pillGap), pillTop, pillRight, pillBottom))

        val chartRect = RectF(rhythmRect.left + 20f, rhythmRect.top + 154f, rhythmRect.right - 20f, rhythmRect.bottom - 76f)
        drawSeriesChart(
            chartRect,
            snapshot.stats.rhythmSeries,
            snapshot.stats.previousRhythmSeries,
            mix(palette.primary, Color.WHITE, 0.55f),
            mix(palette.secondaryContainer, Color.WHITE, 0.62f),
        )
        val chartPointCount = max(snapshot.stats.rhythmSeries.size, snapshot.stats.previousRhythmSeries.size).coerceAtLeast(2)
        val chartLabels = storyChartLabels(snapshot, chartPointCount)
        chartLabels.forEachIndexed { index, label ->
            if (label.isBlank()) return@forEachIndexed
            val x = if (chartPointCount <= 1) {
                chartRect.left + chartRect.width() / 2f
            } else {
                chartRect.left + chartRect.width() * index / (chartPointCount - 1).toFloat()
            }
            paint.textSize = 11f
            paint.typeface = regular
            val labelWidth = paint.measureText(label)
            text(label, x - labelWidth / 2f, chartRect.bottom + 22f, 11f, textTertiary, false)
        }
        drawLegendDot(
            rhythmRect.left + 22f,
            rhythmRect.bottom - 31f,
            mix(palette.primary, Color.WHITE, 0.24f),
            context.getString(if (snapshot.period == StatsPeriod.WEEK) R.string.stats_share_story_this_week else R.string.stats_share_story_this_period),
        )
        drawLegendDot(
            rhythmRect.left + 210f,
            rhythmRect.bottom - 31f,
            mix(palette.secondaryContainer, Color.WHITE, 0.34f),
            context.getString(if (snapshot.period == StatsPeriod.WEEK) R.string.stats_share_story_last_week else R.string.stats_share_story_previous_period),
        )

        // Footer
        logo?.let {
            val footerHeight = 86f
            val footerWidth = footerHeight * (it.width.toFloat() / it.height.toFloat())
            val left = (WIDTH - footerWidth) / 2f
            val footerLogoRect = RectF(left, footerY, left + footerWidth, footerY + footerHeight)
            paint.alpha = 255
            canvas.drawBitmap(it, null, footerLogoRect, paint)
        }
        val footerText = capitalizeFirst(context.getString(R.string.stats_share_story_footer).substringAfter('·').trim())
        paint.textSize = 24f
        paint.typeface = regular
        val footerTextWidth = paint.measureText(footerText)
        text(footerText, (WIDTH - footerTextWidth) / 2f, footerY + 124f, 24f, textSecondary, false)

        bitmap
    }

    suspend fun saveToGallery(context: Context, bitmap: Bitmap): Uri? = withContext(Dispatchers.IO) {
        val fileName = "Orb-Stats-${System.currentTimeMillis()}.png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Orb")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } == true
            }.getOrDefault(false)
            if (!ok) {
                context.contentResolver.delete(uri, null, null)
                return@withContext null
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            uri
        } else {
            val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Orb").apply { mkdirs() }
            val file = File(folder, fileName)
            val ok = runCatching { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }.getOrDefault(false)
            if (ok) Uri.fromFile(file) else null
        }
    }

    suspend fun share(context: Context, bitmap: Bitmap) = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir, "stats_share").apply { mkdirs() }
        val file = File(folder, "orb-stats.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.orb-updates.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        withContext(Dispatchers.Main) {
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.stats_share_button)))
        }
    }

    private fun drawSeriesChart(
        canvas: Canvas,
        current: List<Int>,
        previous: List<Int>,
        rect: RectF,
        currentColor: Int,
        previousColor: Int,
        gridColor: Int,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.color = gridColor
        repeat(4) { i ->
            val y = rect.top + rect.height() * i / 3f
            canvas.drawLine(rect.left, y, rect.right, y, paint)
        }
        val all = current + previous
        val maxV = all.maxOrNull()?.coerceAtLeast(1) ?: 1
        fun path(values: List<Int>): Path {
            val p = Path()
            if (values.isEmpty()) return p
            val step = if (values.size <= 1) 0f else rect.width() / (values.size - 1)
            values.forEachIndexed { i, v ->
                val x = rect.left + step * i
                val y = rect.bottom - rect.height() * (v.toFloat() / maxV.toFloat())
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            return p
        }
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = 5f
        paint.color = previousColor
        paint.alpha = 120
        canvas.drawPath(path(previous), paint)
        paint.color = currentColor
        paint.alpha = 255
        paint.strokeWidth = 8f
        canvas.drawPath(path(current), paint)
    }
}
