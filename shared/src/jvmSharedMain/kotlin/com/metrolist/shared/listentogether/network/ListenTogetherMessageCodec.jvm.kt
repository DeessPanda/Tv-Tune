package com.metrolist.shared.listentogether.network

import okio.Buffer
import okio.GzipSink
import okio.GzipSource
import okio.buffer
import okio.use

private class NoOpListenTogetherCodec : ListenTogetherMessageCodec {
    override var compressionEnabled: Boolean = false
    override fun encode(msgType: String, payload: Any?, requestId: Long): ByteArray = ByteArray(0)
    override fun decodeEnvelope(data: ByteArray): ListenTogetherEnvelope = ListenTogetherEnvelope("", ByteArray(0))
    override fun decodePayload(msgType: String, payloadBytes: ByteArray): Any? = null
}

internal actual fun createListenTogetherMessageCodec(): ListenTogetherMessageCodec = NoOpListenTogetherCodec()

internal actual fun compressListenTogetherPayload(data: ByteArray): ByteArray {
    val output = Buffer()
    GzipSink(output).buffer().use { it.write(data) }
    return output.readByteArray()
}

internal actual fun decompressListenTogetherPayload(
    data: ByteArray,
    limit: Int,
): ByteArray {
    try {
        return GzipSource(Buffer().write(data)).use { gzip ->
            val output = Buffer()
            while (true) {
                val count = gzip.read(output, minOf(8192L, limit - output.size + 1L))
                if (count == -1L) break
                require(output.size <= limit) { "Compressed Listen Together payload exceeds the size limit" }
            }
            output.readByteArray()
        }
    } catch (e: Exception) {
        throw IllegalArgumentException("Failed to decompress payload", e)
    }
}
