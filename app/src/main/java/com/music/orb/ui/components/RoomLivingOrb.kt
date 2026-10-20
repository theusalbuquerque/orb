package com.music.orb.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.random.Random
import kotlin.math.cos
import kotlin.math.sin

/** A stationary, softly morphing glass orb; only its surface and outline move. */
@Composable
internal fun RoomLivingOrb(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "room-glass-orb")
    val motion = transition.animateFloat(0f, 6.283185f,
        animationSpec = infiniteRepeatable(tween(16000, easing = LinearEasing)), label = "orb-surface")
    val hue = transition.animateFloat(0f, 5f,
        animationSpec = infiniteRepeatable(tween(30000, easing = LinearEasing)), label = "orb-color")
    Box(modifier.drawWithCache {
        val origin = Offset(size.width/2f, size.height/2f)
        val radius = (size.minDimension * .43f).coerceAtLeast(1f)
        val outline = Path()
        val halo = Brush.radialGradient(listOf(Color(0xFFBA8AF0).copy(alpha=.19f), Color.Transparent), origin, radius*1.24f)
        val palette = listOf(Color(0xFFBA9AE8), Color(0xFFF1A4C9), Color(0xFF86BAED), Color(0xFF94D7B0), Color(0xFFECDD8C))
        val surfaces = palette.map { color ->
            Brush.radialGradient(listOf(color, color.copy(red=color.red*.74f, green=color.green*.74f, blue=color.blue*.74f)), origin-Offset(radius*.22f,radius*.28f),radius*1.7f)
        }
        val random = Random(41)
        val grain = List(1200) {
            Triple(Offset(origin.x+(random.nextFloat()*2f-1f)*radius*1.08f,
                origin.y+(random.nextFloat()*2f-1f)*radius*1.08f),
                radius*(.0018f+random.nextFloat()*.0028f),random.nextBoolean())
        }
        val pearl = Brush.radialGradient(listOf(Color.White.copy(alpha=.31f),Color.White.copy(alpha=.06f),Color.Transparent),origin+Offset(-radius*.38f,-radius*.5f),radius*.95f)
        val sheen = Brush.linearGradient(listOf(Color.White.copy(alpha=.28f),Color(0xFFB9E9FA).copy(alpha=.12f),Color.White.copy(alpha=.04f)),origin-Offset(radius,radius),origin+Offset(radius,radius))
        onDrawBehind {
            val p = motion.value
            outline.reset()
            for (i in 0..96) {
                val angle = i*6.283185f/96
                val contour = radius*(1f+.036f*sin(angle*3f+p)+.018f*cos(angle*5f-p*2f))
                val x=origin.x+cos(angle)*contour
                val y=origin.y+sin(angle)*contour
                if(i==0)outline.moveTo(x,y) else outline.lineTo(x,y)
            }
            outline.close()
            drawCircle(halo,radius*1.24f,origin)
            val colorPhase = hue.value
            val colorIndex = colorPhase.toInt().coerceIn(0, palette.lastIndex)
            val fraction = colorPhase-colorIndex
            val blend = fraction*fraction*(3f-2f*fraction)
            drawPath(outline,surfaces[colorIndex])
            drawPath(outline,surfaces[(colorIndex+1)%palette.size],alpha=blend)
            clipPath(outline) {
                rotate(p*12f,origin) {
                    grain.forEach { (point, dotRadius, light) ->
                        drawCircle(if(light) Color.White.copy(alpha=.14f) else Color.Black.copy(alpha=.075f),dotRadius,point)
                    }
                }
                drawCircle(pearl,radius*2f,origin)
            }
            drawPath(outline,sheen,style=Stroke(width=radius*.018f))
        }
    })
}
