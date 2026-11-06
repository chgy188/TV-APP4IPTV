package com.example.composedtv.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * 「水杯注水」缓冲指示：水位由真实缓冲进度驱动，纯 Canvas 绘制，无图片资源。
 * 与 ambientGradientBackdrop 同色系（青 → 蓝），适合 TV 大屏首帧前的等待画面。
 */
@Composable
fun WaterGlassLoader(
    progress: Float,
    modifier: Modifier = Modifier
) {
    // 水位平滑跟随采样值，避免 120ms 采样带来的抖动
    val level by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 420, easing = LinearEasing),
        label = "waterLevel"
    )

    // 波浪相位无限循环：即使进度暂时不动，画面仍在「活着」
    val transition = rememberInfiniteTransition(label = "waterWave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )

    Canvas(modifier = modifier.size(width = 132.dp, height = 168.dp)) {
        val w = size.width
        val h = size.height

        // 杯体几何：上宽下略窄、圆角杯底
        val topY = h * 0.10f
        val bottomY = h * 0.92f
        val topLeft = w * 0.10f
        val topRight = w * 0.90f
        val bottomLeft = w * 0.20f
        val bottomRight = w * 0.80f
        val corner = w * 0.09f
        val rimHeight = h * 0.05f

        // 任意高度处的杯壁 x，让水体严丝合缝贴在杯内（无需 clipPath）
        fun leftAt(y: Float): Float {
            val t = ((y - topY) / (bottomY - topY)).coerceIn(0f, 1f)
            return topLeft + (bottomLeft - topLeft) * t
        }
        fun rightAt(y: Float): Float {
            val t = ((y - topY) / (bottomY - topY)).coerceIn(0f, 1f)
            return topRight + (bottomRight - topRight) * t
        }

        val glass = Path().apply {
            moveTo(topLeft, topY)
            lineTo(bottomLeft, bottomY - corner)
            quadraticBezierTo(bottomLeft, bottomY, bottomLeft + corner, bottomY)
            lineTo(bottomRight - corner, bottomY)
            quadraticBezierTo(bottomRight, bottomY, bottomRight, bottomY - corner)
            lineTo(topRight, topY)
        }

        // 玻璃本体的极淡填充：空杯阶段也有质感
        drawPath(
            path = glass,
            brush = Brush.verticalGradient(
                colors = listOf(Color(0x14FFFFFF), Color(0x08FFFFFF), Color(0x1A60A5FA)),
                startY = topY,
                endY = bottomY
            )
        )

        val waterY = bottomY - (bottomY - topY) * level
        val amplitude = w * 0.022f * (0.35f + 0.65f * (1f - level))

        fun wavePath(phaseShift: Float, ampScale: Float): Path {
            val p = Path()
            val left = leftAt(waterY)
            val right = rightAt(waterY)
            val steps = 32
            p.moveTo(left, waterY + sin(phaseShift) * amplitude * ampScale)
            for (i in 1..steps) {
                val t = i.toFloat() / steps
                val x = left + (right - left) * t
                val y = waterY + sin(
                    t * 2f * PI.toFloat() * 1.6f + phase + phaseShift
                ) * amplitude * ampScale
                p.lineTo(x, y)
            }
            p.lineTo(bottomRight, bottomY - corner)
            p.quadraticBezierTo(bottomRight, bottomY, bottomRight - corner, bottomY)
            p.lineTo(bottomLeft + corner, bottomY)
            p.quadraticBezierTo(bottomLeft, bottomY, bottomLeft, bottomY - corner)
            p.close()
            return p
        }

        if (level > 0.001f) {
            // 后层波浪：稍亮、相位错开，制造水的通透感
            drawPath(
                path = wavePath(PI.toFloat() * 0.7f, 0.65f),
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0x667DD3FC), Color(0x5538BDF8)),
                    startY = waterY,
                    endY = bottomY
                )
            )
            // 前层主体：青 → 蓝 → 深蓝
            drawPath(
                path = wavePath(0f, 1f),
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF7DD3FC), Color(0xFF2F7DF6), Color(0xFF1E40AF)),
                    startY = waterY,
                    endY = bottomY
                )
            )

            // 水面高光线
            val surface = Path().apply {
                val left = leftAt(waterY)
                val right = rightAt(waterY)
                val steps = 32
                moveTo(left, waterY + sin(phase) * amplitude)
                for (i in 1..steps) {
                    val t = i.toFloat() / steps
                    val x = left + (right - left) * t
                    lineTo(x, waterY + sin(t * 2f * PI.toFloat() * 1.6f + phase) * amplitude)
                }
            }
            drawPath(
                path = surface,
                color = Color.White.copy(alpha = 0.55f),
                style = Stroke(width = 1.5f.dp.toPx())
            )

            // 上升气泡：仅在水下，越接近水面越淡
            repeat(5) { i ->
                val t = ((phase / (2f * PI.toFloat())) * 0.9f + i * 0.23f) % 1f
                val by = bottomY - t * (bottomY - waterY).coerceAtLeast(1f)
                if (by > waterY + 3f) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.42f * (1f - t)),
                        radius = (1.6f + (i % 3) * 0.9f).dp.toPx(),
                        center = Offset(w * (0.38f + 0.055f * (i % 4)), by)
                    )
                }
            }
        }

        // 注水水滴：未装满时从杯口落下，强化「正在缓存」
        if (level < 0.98f) {
            val dropT = (phase / (2f * PI.toFloat())) % 1f
            val dropY = topY - h * 0.06f + dropT * (waterY - topY + h * 0.06f)
            if (dropY < waterY) {
                drawCircle(
                    color = Color(0xAA93C5FD),
                    radius = 2.2f.dp.toPx(),
                    center = Offset(w * 0.5f, dropY)
                )
            }
        }

        // 杯壁描边 + 杯口椭圆
        drawPath(
            path = glass,
            color = Color.White.copy(alpha = 0.55f),
            style = Stroke(width = 2.2f.dp.toPx())
        )
        drawOval(
            color = Color.White.copy(alpha = 0.7f),
            topLeft = Offset(topLeft, topY - rimHeight / 2f),
            size = Size(topRight - topLeft, rimHeight),
            style = Stroke(width = 2f.dp.toPx())
        )

        // 左侧竖向玻璃高光
        drawLine(
            brush = Brush.verticalGradient(
                colors = listOf(Color(0x66FFFFFF), Color(0x00FFFFFF)),
                startY = topY + h * 0.06f,
                endY = bottomY - h * 0.12f
            ),
            start = Offset(topLeft + w * 0.055f, topY + h * 0.06f),
            end = Offset(bottomLeft + w * 0.055f, bottomY - h * 0.12f),
            strokeWidth = 3f.dp.toPx()
        )

        // 满杯：杯沿泛光，暗示「可以播放了」
        if (level > 0.98f) {
            drawOval(
                color = Color(0x66BAE6FD),
                topLeft = Offset(topLeft, topY - rimHeight / 2f),
                size = Size(topRight - topLeft, rimHeight),
                style = Stroke(width = 3f.dp.toPx())
            )
        }
    }
}
