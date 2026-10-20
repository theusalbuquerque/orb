package com.music.orb.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import kotlinx.coroutines.delay

/** Lay out the complete paragraph from the first frame; only its ink is revealed. */
@Composable
internal fun StableTypewriterText(text: String, animate: Boolean, color: Color) {
    var revealed by remember(text, animate) { mutableStateOf(if (animate) 0 else text.length) }
    LaunchedEffect(text, animate) {
        if (!animate) {
            revealed = text.length
            return@LaunchedEffect
        }
        val chunk = ((text.length + 59) / 60).coerceAtLeast(2)
        while (revealed < text.length) {
            delay(16L)
            var end = (revealed + chunk).coerceAtMost(text.length)
            if (end < text.length && end > 0 && Character.isHighSurrogate(text[end - 1])) end++
            revealed = end
        }
    }
    Text(
        text = buildAnnotatedString {
            append(text.substring(0, revealed))
            withStyle(SpanStyle(color = Color.Transparent)) {
                append(text.substring(revealed))
            }
        },
        style = MaterialTheme.typography.bodyMedium,
        color = color,
    )
}
