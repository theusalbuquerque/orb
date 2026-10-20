package com.music.orb.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.music.orb.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Expressive circular waves with a quiet track and a rounded, breathing active arc. */
@Composable
internal fun WavyRefreshIndicator(
    refreshing: Boolean, progress: Float, modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val transition = rememberInfiniteTransition(label = "refresh-waves")
    val phase by transition.animateFloat(0f, (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "refresh-wave-phase")
    val label = stringResource(R.string.refresh_loading)
    val path = remember { Path() }
    Canvas(modifier.semantics {
        contentDescription = label
        progressBarRangeInfo = if (refreshing) ProgressBarRangeInfo.Indeterminate
            else ProgressBarRangeInfo(progress, 0f..1f)
    }) {
        val motion = if (refreshing) phase else progress * PI.toFloat()
        val sweep = if (refreshing) PI * (0.95 + 0.55 * sin(phase.toDouble())) else 2 * PI * progress
        val radius = size.minDimension * 0.35f
        val amplitude = 2.dp.toPx()
        drawCircle(color.copy(alpha = 0.16f), radius = radius,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        path.reset()
        val segments = 192
        for (i in 0..segments) {
            val angle = -PI / 2 + motion + sweep * i / segments
            val waveRadius = radius + amplitude * sin(angle * 8 - motion * 2)
            val x = center.x + (waveRadius * cos(angle)).toFloat()
            val y = center.y + (waveRadius * sin(angle)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (!refreshing && progress >= 1f) path.close()
        drawPath(path, color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
    }
}
