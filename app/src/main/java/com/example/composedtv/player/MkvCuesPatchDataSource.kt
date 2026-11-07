package com.example.composedtv.player

import android.net.Uri
import android.util.Log
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * MKV 断点续播补丁。
 *
 * 部分 IPTV 点播 MKV 只在「文件末尾的 SeekHead」里记录 Cues 索引位置，文件头部的 SeekHead
 * 只有 SeekHead/Tracks 条目。Media3 的 MatroskaExtractor 仅解析头部 SeekHead，取不到
 * cuesContentPosition 就会建立 SeekMap.Unseekable，于是 isCurrentMediaItemSeekable() 恒为
 * false，setMediaItem(item, startPositionMs) 与 seekTo() 都被 clamp 回 0。
 *
 * 这里在数据源层做等长就地修补：把头部 SeekHead 中那条无用的 SeekID=0x114D9B74(SeekHead)
 * 改成 SeekID=0x1C53BB6B(Cues)，并把它的 SeekPosition(5 字节) 换成从文件尾探测到的 Cues
 * 位置。文件长度与所有字节偏移均不变，之后由 MatroskaExtractor 自行跳到 Cues、解析出精确
 * 的时间点→Cluster 偏移映射，续播即可生效。
 *
 * 仅对直连 .mkv 点播源启用；探测、匹配或请求失败时完全透传，不影响正常播放。
 */
class MkvCuesPatchDataSource(
    private val upstream: DataSource,
    private val url: String,
    private val headers: Map<String, String>,
    private val defaultUserAgent: String,
    client: OkHttpClient
) : DataSource {

    companion object {
        private const val TAG = "MkvCuesPatch"
        private const val HEAD_SIZE = 512
        private const val TAIL_SIZE = 4096

        /** 文件尾 SeekHead 中：SeekID(size=4)=Cues + SeekPosition(size=5) 的固定前缀 */
        private val CUES_SEEK_TAIL =
            bytes(0x53, 0xAB, 0x84, 0x1C, 0x53, 0xBB, 0x6B, 0x53, 0xAC, 0x85)
        /** 头部 SeekHead 中待替换的条目：SeekID(size=4)=SeekHead + SeekPosition(size=5) */
        private val HEAD_SEEK_PATTERN =
            bytes(0x53, 0xAB, 0x84, 0x11, 0x4D, 0x9B, 0x74, 0x53, 0xAC, 0x85)
        private val CUES_ID = bytes(0x1C, 0x53, 0xBB, 0x6B)
        private val EBML_MAGIC = bytes(0x1A, 0x45, 0xDF, 0xA3)

        private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

        /** url -> 修补后的文件头；null 表示该源无需/无法修补，避免每次起播重复探测 */
        private val cache = ConcurrentHashMap<String, ByteArray?>()
    }

    private val probeClient = client.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private var patchedHead: ByteArray? = null
    private var headPos = 0

    override fun open(dataSpec: DataSpec): Long {
        patchedHead = null
        headPos = 0
        // 探测在上游连接建立前完成，避免探测期间上游响应流闲置被服务器断开。
        val patch = if (dataSpec.position == 0L) {
            if (cache.containsKey(url)) cache[url] else buildPatch().also { cache[url] = it }
        } else null

        val remaining = upstream.open(dataSpec)
        if (patch != null) {
            val discard = ByteArray(HEAD_SIZE)
            var consumed = 0
            while (consumed < HEAD_SIZE) {
                val n = upstream.read(discard, 0, HEAD_SIZE - consumed)
                if (n <= 0) break
                consumed += n
            }
            if (consumed > 0) {
                patchedHead = if (consumed >= patch.size) patch else patch.copyOf(consumed)
                Log.d(TAG, "头部 SeekHead 已补 Cues 条目: $url")
            }
        }
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val head = patchedHead
        if (head != null && headPos < head.size) {
            val n = minOf(length, head.size - headPos)
            System.arraycopy(head, headPos, buffer, offset, n)
            headPos += n
            return n
        }
        return upstream.read(buffer, offset, length)
    }

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        patchedHead = null
        headPos = 0
        upstream.close()
    }

    /** 读取头部 512B + 尾部 4KB，算出 Cues 相对 Segment 起始的字节位置并生成等长补丁头。 */
    private fun buildPatch(): ByteArray? {
        return try {
            val (total, head) = fetch(0L, (HEAD_SIZE - 1).toLong()) ?: return null
            if (total <= TAIL_SIZE || head.size < 4) return null
            for (i in EBML_MAGIC.indices) {
                if (head[i] != EBML_MAGIC[i]) return null
            }
            val tail = fetch(total - TAIL_SIZE, total - 1)?.second ?: return null
            val cuesPos = readCuesPosition(tail) ?: return null
            val idx = indexOf(head, HEAD_SEEK_PATTERN)
            if (idx < 0 || idx > 256 || idx + HEAD_SEEK_PATTERN.size + 5 > head.size) return null

            val patched = head.copyOf()
            System.arraycopy(CUES_ID, 0, patched, idx + 3, 4)
            for (k in 0 until 5) {
                patched[idx + HEAD_SEEK_PATTERN.size + k] =
                    ((cuesPos shr (8 * (4 - k))) and 0xFF).toByte()
            }
            Log.d(TAG, "探测到 Cues 位置=$cuesPos，头部偏移=$idx: $url")
            patched
        } catch (t: Throwable) {
            Log.w(TAG, "Cues 探测失败，降级为原始流: ${t.message}")
            null
        }
    }

    /** 返回「文件总长度 + 本次 Range 的响应体」；只按声明长度读取，防止源站忽略 Range 时拖回整个文件。 */
    private fun fetch(from: Long, to: Long): Pair<Long, ByteArray>? {
        val builder = Request.Builder().url(url).header("Range", "bytes=$from-$to")
        if (!headers.containsKey("User-Agent")) builder.header("User-Agent", defaultUserAgent)
        headers.forEach { (k, v) -> builder.header(k, v) }
        probeClient.newCall(builder.build()).execute().use { resp ->
            if (resp.code != 206) return null
            val contentRange = resp.header("Content-Range") ?: return null
            val total = contentRange.substringAfterLast('/').toLongOrNull() ?: return null
            val body = resp.body ?: return null
            val limit = (to - from + 1).toInt()
            val buf = ByteArray(limit)
            var read = 0
            body.byteStream().use { input ->
                while (read < limit) {
                    val n = input.read(buf, read, limit - read)
                    if (n <= 0) break
                    read += n
                }
            }
            if (read == 0) return null
            return total to if (read == limit) buf else buf.copyOf(read)
        }
    }

    private fun readCuesPosition(tail: ByteArray): Long? {
        val i = indexOf(tail, CUES_SEEK_TAIL)
        if (i < 0 || i + CUES_SEEK_TAIL.size + 5 > tail.size) return null
        var v = 0L
        for (k in 0 until 5) {
            v = (v shl 8) or (tail[i + CUES_SEEK_TAIL.size + k].toLong() and 0xFF)
        }
        return if (v > 0) v else null
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
