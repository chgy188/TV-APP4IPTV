package com.example.composedtv.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * HUD 宿主：自带采样循环，把刷新产生的重组**限制在自己的作用域内**。
 *
 * 关键：绝不能在 PlayerScreen 作用域里持有采样 state——那会让整个 PlayerScreen
 * 每 500ms 重组一次，从而执行 AndroidView 的 update，进而每 500ms 新建一个
 * Surface 对象并调用 engine.setSharedSurface()。在 API<23（无 setOutputSurface）
 * 上这等同于每 500ms 重启一次视频解码器，反而把"图片轮播"做得更严重，
 * 同时污染 Surface 注入计数导致误判。
 */
@Composable
fun DebugHudHost(modifier: Modifier = Modifier) {
    var snapshot by remember { mutableStateOf(DebugDiagnostics.snapshot()) }
    LaunchedEffect(Unit) {
        while (true) {
            snapshot = DebugDiagnostics.snapshot()
            kotlinx.coroutines.delay(500)
        }
    }
    DebugHud(snapshot, modifier = modifier)
}

/**
 * 屏幕诊断 HUD：直接投在投影画面上，用于无 ADB 环境。
 *
 * 判读要点（对应老设备"有声音但画面像图片轮播"）：
 * - 解码器名含 OMX.google / ffmpeg → 已降级软解（红色告警）
 * - 解码器初始化"增量"持续 > 0 → 解码器在反复重启（Surface 被反复重设）
 * - 实际出帧率远低于源帧率 + "丢到关键帧"上涨 → 已退化成只出关键帧，即图片轮播
 * - Surface 注入"实际执行"远大于预期 → setSharedSurface 幂等保护失效
 */
@Composable
fun DebugHud(s: DiagSnapshot, modifier: Modifier = Modifier) {
    val softDecode = s.decoderName.contains("google", ignoreCase = true) ||
        s.decoderName.contains("ffmpeg", ignoreCase = true)
    val fpsLow = s.renderedFps > 0 && s.renderedFps < 20

    Column(
        modifier = modifier
            .background(Color(0xDD000000))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        HudTitle("DIAG · API ${s.sdkInt} · ${s.abi} · legacy=${s.legacy} · ${s.rendererMode}")
        HudLine("频道", s.channelName.ifEmpty { "-" }, s.usingProxy)
        HudLine("解码器", s.decoderName, softDecode)
        HudLine(
            "解码器初始化",
            "会话${s.decoderInitSession} 当前${s.decoderInitCurrent} 增量${s.decoderInitDelta} 耗时${s.lastDecoderInitCostMs}ms",
            s.decoderInitDelta > 0
        )
        HudLine("视频", "${s.resolution} @${s.frameRate}fps ${s.bitrate}", false)
        HudLine("编码", "${s.codecs} ${s.mime}", false)
        HudLine("状态", "${s.state} loading=${s.isLoading} buf=${s.bufferedMs / 1000}s", s.isLoading)
        HudLine(
            "出帧",
            String.format(
                Locale.US,
                "%.1f fps 渲染%d 丢%d 丢到关键帧%d 跳%d",
                s.renderedFps, s.renderedTotal, s.droppedTotal, s.droppedToKeyframe, s.skippedTotal
            ),
            fpsLow || s.droppedToKeyframe > 0
        )
        HudLine(
            "Surface注入",
            "调用${s.surfaceInjectCalls} 实际${s.surfaceInjectApplied}",
            s.surfaceInjectApplied > 3
        )
        HudLine("Player", "创建${s.playerCreateCount} 释放${s.playerReleaseCount}", s.playerCreateCount - s.playerReleaseCount > 1)
        HudLine("竞速/重载", "${s.raceStartCount} / ${s.reloadCount} (${s.lastReloadReason})", s.reloadCount > 2)
        HudLine("错误", "${s.errorCount} · ${s.lastError}", s.errorCount > 0)
        if (s.serverRunning) {
            HudLine("HTTP", s.serverUrl, false)
        }
        HudLine("提示", "MENU→诊断分组 / 数字键0·9", false)
    }
}

@Composable
private fun HudTitle(text: String) {
    Text(
        text = text,
        color = Color(0xFF7ECBFF),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(bottom = 3.dp)
    )
}

@Composable
private fun HudLine(label: String, value: String, warn: Boolean) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = Color(0xFF88AA88),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .padding(end = 8.dp)
                .weight(0.30f)
        )
        Text(
            text = value,
            color = if (warn) Color(0xFFFF6B6B) else Color.White,
            fontSize = 10.sp,
            fontWeight = if (warn) FontWeight.Bold else FontWeight.Normal,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(0.70f)
        )
    }
}
