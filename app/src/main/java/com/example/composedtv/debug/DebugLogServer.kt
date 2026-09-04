package com.example.composedtv.debug

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 极简 HTTP 诊断服务（无第三方依赖）。
 *
 * 用途：设备开不了 ADB 时，用 PC 浏览器访问投影仪 IP 直接看诊断数据与日志。
 *
 * 路由：
 *  - /            → HTML 页面（2 秒自动刷新）
 *  - /json        → 诊断快照 JSON
 *  - /log         → 全部内存日志（纯文本）
 *  - /clear       → 清空日志
 *  - /reset       → 复位计数器
 *  - /stop        → 停止服务
 */
object DebugLogServer {

    private const val TAG = "DiagServer"
    private const val DEFAULT_PORT = 8080

    @Volatile
    private var running = false

    @Volatile
    private var port = 0

    @Volatile
    private var hostIp: String? = null

    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null

    fun isRunning(): Boolean = running

    fun url(): String? = if (running) "http://${hostIp ?: "?"}:$port" else null

    /**
     * 启动服务，返回可访问 URL；失败返回 null。
     *
     * bind 放在后台线程：ServerSocket 创建属于网络操作，在主线程执行可能触发
     * NetworkOnMainThreadException。这里用 latch 短暂等待 bind 结果再返回地址。
     */
    fun start(requestPort: Int = DEFAULT_PORT): String? {
        if (running) return url()
        val latch = CountDownLatch(1)
        thread = Thread({
            val ss: ServerSocket = try {
                ServerSocket(requestPort)
            } catch (e: Exception) {
                Log.e(TAG, "启动诊断服务失败: ${e.message}", e)
                running = false
                latch.countDown()
                return@Thread
            }
            serverSocket = ss
            port = ss.localPort
            hostIp = DebugDiagnostics.localIpAddress()
            running = true
            DebugDiagnostics.startFileLogging()
            DebugDiagnostics.log(TAG, "server started at ${url()}")
            latch.countDown()
            acceptLoop(ss)
        }, "diag-server").apply { isDaemon = true; start() }

        runCatching { latch.await(800, TimeUnit.MILLISECONDS) }
        return if (running) url() else null
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        thread = null
        DebugDiagnostics.stopFileLogging()
        DebugDiagnostics.log(TAG, "server stopped")
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val client: Socket = try {
                ss.accept()
            } catch (e: Exception) {
                if (running) Log.w(TAG, "accept 异常: ${e.message}")
                break
            }
            try {
                handle(client)
            } catch (e: Exception) {
                Log.w(TAG, "处理请求异常: ${e.message}")
            } finally {
                runCatching { client.close() }
            }
        }
    }

    private fun handle(client: Socket) {
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
        val requestLine = reader.readLine() ?: return
        val path = requestLine.split(" ").getOrNull(1) ?: "/"
        // 读走剩余 header，避免连接提前关闭
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }

        val route = path.substringBefore("?")
        when (route) {
            "/json" -> respond(client, "application/json; charset=utf-8", DebugDiagnostics.toJson())
            "/log" -> respond(client, "text/plain; charset=utf-8", DebugDiagnostics.exportLog())
            "/clear" -> {
                DebugDiagnostics.clearLog()
                respond(client, "text/plain; charset=utf-8", "log cleared\n")
            }
            "/reset" -> {
                DebugDiagnostics.resetCounters()
                respond(client, "text/plain; charset=utf-8", "counters reset\n")
            }
            "/stop" -> {
                respond(client, "text/plain; charset=utf-8", "stopping\n")
                client.close()
                stop()
                return
            }
            else -> respond(client, "text/html; charset=utf-8", htmlPage())
        }
    }

    private fun respond(client: Socket, contentType: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val out: OutputStream = client.getOutputStream()
        val head = (
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray(StandardCharsets.US_ASCII)
        out.write(head)
        out.write(bytes)
        out.flush()
    }

    private fun htmlPage(): String {
        val s = DebugDiagnostics.snapshot()
        val esc = { v: String -> v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") }
        val row = { k: String, v: String, warn: Boolean ->
            "<tr><td class='k'>${esc(k)}</td><td class='v${if (warn) " warn" else ""}'>${esc(v)}</td></tr>"
        }
        val softDecode = s.decoderName.contains("google", ignoreCase = true) ||
            s.decoderName.contains("ffmpeg", ignoreCase = true) ||
            s.decoderName.contains("av1", ignoreCase = true)
        val fpsWarn = s.renderedFps > 0 && s.renderedFps < 20

        val body = buildString {
            append(row("设备 / SDK / ABI", "${s.device} / API ${s.sdkInt} / ${s.abi}", false))
            append(row("老设备降级 / 渲染方式", "legacy=${s.legacy} / ${s.rendererMode}", false))
            append(row("频道", s.channelName.ifEmpty { "-" }, false))
            append(row("URL", s.channelUrl, false))
            append(row("走代理", s.usingProxy.toString(), s.usingProxy))
            append(row("解码器", s.decoderName, softDecode))
            append(row("解码器初始化(会话/当前/增量)", "${s.decoderInitSession} / ${s.decoderInitCurrent} / ${s.decoderInitDelta}", s.decoderInitDelta > 0))
            append(row("上次初始化耗时", "${s.lastDecoderInitCostMs} ms", false))
            append(row("分辨率 / 帧率 / 码率", "${s.resolution} / ${s.frameRate} / ${s.bitrate}", false))
            append(row("编码 / MIME", "${s.codecs} / ${s.mime}", false))
            append(row("状态 / loading", "${s.state} / ${s.isLoading}", s.isLoading))
            append(row("实际出帧率", String.format(Locale.US, "%.1f fps", s.renderedFps), fpsWarn))
            append(row("渲染帧 / 丢帧 / 丢到关键帧", "${s.renderedTotal} / ${s.droppedTotal} / ${s.droppedToKeyframe}", s.droppedToKeyframe > 0))
            append(row("Surface 注入(调用/实际执行)", "${s.surfaceInjectCalls} / ${s.surfaceInjectApplied}", s.surfaceInjectApplied > 3))
            append(row("Player 创建 / 释放", "${s.playerCreateCount} / ${s.playerReleaseCount}", s.playerCreateCount - s.playerReleaseCount > 1))
            append(row("起播 / 重载次数", "${s.raceStartCount} / ${s.reloadCount}", s.reloadCount > 2))
            append(row("最近重载原因", s.lastReloadReason, false))
            append(row("错误数 / 最近错误", "${s.errorCount} / ${s.lastError}", s.errorCount > 0))
            append(row("日志行数 / 路径", "${s.logLines} / ${s.logPath}", false))
        }

        return """<!DOCTYPE html><html><head><meta charset="utf-8">
<meta http-equiv="refresh" content="2">
<title>IPTV 诊断</title>
<style>
body{background:#111;color:#ddd;font-family:Consolas,Menlo,monospace;font-size:13px;padding:16px}
h2{font-size:15px;color:#7ecbff;margin:0 0 10px}
table{border-collapse:collapse;width:100%;max-width:1100px}
td{padding:3px 8px;border-bottom:1px solid #262626;vertical-align:top}
td.k{color:#8a8;width:300px;white-space:nowrap}
td.v{color:#fff;word-break:break-all}
td.warn{color:#ff6b6b;font-weight:bold}
a{color:#7ecbff;margin-right:14px}
</style></head><body>
<h2>IPTV 播放诊断 · 每 2 秒自动刷新</h2>
<p><a href="/log">查看日志</a><a href="/json">JSON</a><a href="/reset">复位计数器</a><a href="/clear">清空日志</a><a href="/stop">停止服务</a></p>
<table>$body</table>
</body></html>"""
    }
}
