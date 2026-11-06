package com.example.composedtv.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 纯代码生成的「蒙版色彩渐变」背景，不依赖任何图片资源。
 *
 * 由三层叠加而成（均在 drawBehind 中按尺寸实时绘制，随屏幕/窗口自适应）：
 * 1. 深蓝黑对角底色，保证文字与控件有足够对比度；
 * 2. 左上冷蓝 + 右下青紫两处柔和径向光晕，营造层次感与 TV 大屏氛围；
 * 3. 顶部到底部的暗色蒙版，避免中心内容区过亮。
 *
 * 复用场景：非播放界面（登录 / 用户选择）整体背景，以及播放器首帧渲染前的占位背景
 * （取代纯黑屏，见 PlayerScreen）。
 */
fun Modifier.ambientGradientBackdrop(): Modifier = this.drawBehind {
    val w = size.width
    val h = size.height

    // 1. 对角底色：深蓝黑，四角略暗，整体保持低亮度
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(
                Color(0xFF111A2E),
                Color(0xFF0D1524),
                Color(0xFF080C15)
            ),
            start = Offset.Zero,
            end = Offset(w, h)
        )
    )

    // 2. 左上冷蓝光晕
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color(0x3D3B82F6), Color.Transparent),
            center = Offset(w * 0.18f, h * 0.12f),
            radius = w * 0.75f
        )
    )

    // 3. 右下青紫光晕
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color(0x332A7FFF), Color.Transparent),
            center = Offset(w * 0.86f, h * 0.88f),
            radius = w * 0.7f
        )
    )

    // 4. 中部极淡的暖色，避免整体过于冷硬
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color(0x146366F1), Color.Transparent),
            center = Offset(w * 0.52f, h * 0.48f),
            radius = w * 0.45f
        )
    )

    // 5. 自上而下的暗色蒙版：中心内容区更沉稳，文字清晰
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color(0x33000000), Color.Transparent, Color(0x66000000))
        )
    )
}
