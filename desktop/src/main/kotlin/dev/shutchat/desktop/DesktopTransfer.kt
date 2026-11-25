package dev.shutchat.desktop

import dev.shutchat.app.core.Log
import dev.shutchat.app.core.hexToBytes
import dev.shutchat.app.core.toHex
import dev.shutchat.app.crypto.RoomCrypto
import dev.shutchat.app.p2p.MeshEngine
import dev.shutchat.app.p2p.protocol.Envelope
import dev.shutchat.app.p2p.protocol.Framing
import dev.shutchat.app.p2p.protocol.Protocol
import dev.shutchat.app.p2p.protocol.TransferCancel
import dev.shutchat.app.p2p.protocol.TransferComplete
import dev.shutchat.app.p2p.protocol.TransferStart
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * Chunked attachment transfer, the same protocol the phone speaks.
 *
 * The Android client keeps its own copy of this because its sink is the
 * MediaStore; here the sink is a plain directory. The wire format is identical,
 * so a file sent from Linux lands on Android and the other way round.
 */
class DesktopTransfer(
    private val scope: CoroutineScope,
    private val mesh: MeshEngine,
    private val files: DesktopFiles,
) {

    val progress = MutableSharedFlow<Progress>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    data class Progress(
        val attachmentId: String,
        val messageId: String,
        val receivedBytes: Long,
        val totalBytes: Long,
        val complete: Boolean,
    ) {
        val fraction: Float
            get() = if (totalBytes <= 0) 0f else (receivedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    }

    private val inbound = ConcurrentHashMap<String, Inbound>()
    private val completed = ConcurrentHashMap<String, File>()

    private class Inbound(
        val attachmentId: String,
        val messageId: String,
        val totalChunks: Int,
        val totalBytes: Long,
        val file: File,
    ) {
        val received = BooleanArray(totalChunks)
        @Volatile var receivedBytes: Long = 0
        val handle = RandomAccessFile(file, "rw")

        fun close() = runCatching { handle.close() }.let { }
    }

    /** @return false when no peer is reachable, so the caller can queue instead. */
    suspend fun send(
        roomIdHex: String,
        transferId: ByteArray,
        attachmentId: String,
        messageId: String,
        file: File,
        fileName: String,
        mimeType: String,
        onProgress: (Float) -> Unit = {},
    ): Boolean {
        val totalChunks = ((file.length() + Protocol.CHUNK_SIZE - 1) / Protocol.CHUNK_SIZE).toInt()
        if (totalChunks <= 0) return false

        val announced = mesh.broadcast(
            TransferStart(
                roomId = roomIdHex,
                transferId = transferId.toHex(),
                attachmentId = attachmentId,
                messageId = messageId,
                fileName = fileName,
                mimeType = mimeType,
                totalChunks = totalChunks,
                totalBytes = file.length(),
            ),
        )
        if (announced == 0) return false

        val key = mesh.transferKey(roomIdHex) ?: return false
        val roomIdBytes = roomIdHex.hexToBytes()

        file.inputStream().use { input ->
            val buffer = ByteArray(Protocol.CHUNK_SIZE)
            var index = 0
            var sent = 0L
            while (index < totalChunks) {
                val read = input.read(buffer)
                if (read <= 0) break
                val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                val sealed = RoomCrypto.encrypt(key, chunk, transferId)
                val frame = Framing.frameChunk(
                    roomId = roomIdBytes,
                    transferId = transferId,
                    index = index,
                    total = totalChunks,
                    body = sealed,
                )
                if (mesh.sendFrame(roomIdHex, frame) == 0) {
                    Log.d(TAG, "transfer $attachmentId stopped at chunk $index")
                    mesh.broadcast(TransferCancel(roomIdHex, transferId.toHex(), "peer unreachable"))
                    return false
                }
                sent += read
                index++
                onProgress((sent.toFloat() / file.length()).coerceIn(0f, 1f))
            }
        }

        mesh.broadcast(
            TransferComplete(
                roomId = roomIdHex,
                transferId = transferId.toHex(),
                attachmentId = attachmentId,
                messageId = messageId,
            ),
        )
        return true
    }

    fun onEnvelope(envelope: Envelope) {
        when (envelope) {
            is TransferStart -> {
                if (inbound.containsKey(envelope.transferId)) return
                val destination = files.incoming(envelope.attachmentId, envelope.fileName)
                // Registered synchronously: the first chunks can arrive before a
                // coroutine would have run, and dropping them loses the file.
                runCatching {
                    inbound[envelope.transferId] = Inbound(
                        attachmentId = envelope.attachmentId,
                        messageId = envelope.messageId,
                        totalChunks = envelope.totalChunks,
                        totalBytes = envelope.totalBytes,
                        file = destination,
                    )
                }.onFailure { Log.w(TAG, "cannot open ${destination.name}", it) }
            }

            is TransferComplete -> {
                val state = inbound[envelope.transferId]
                // The completion notice is a hint, not proof: if chunks are still
                // missing the file is not finished and must not be reported as
                // whole, or the UI shows a truncated file at 100%.
                if (state != null && state.received.all { it }) finish(envelope.transferId)
            }

            is TransferCancel -> inbound.remove(envelope.transferId)?.close()

            else -> Unit
        }
    }

    fun onChunk(chunk: MeshEngine.InboundChunk) {
        val state = inbound[chunk.transferId] ?: return
        if (chunk.index !in state.received.indices) return
        if (state.received[chunk.index]) return

        // Written inline: the completion envelope can follow the last chunk
        // immediately, and an async write would then race the close and leave a
        // zero-byte file behind.
        val written = runCatching {
            synchronized(state) {
                state.handle.seek(chunk.index.toLong() * Protocol.CHUNK_SIZE)
                state.handle.write(chunk.body)
            }
        }.onFailure { Log.w(TAG, "chunk write ${chunk.index} failed", it) }.isSuccess
        if (!written) return

        state.received[chunk.index] = true
        state.receivedBytes += chunk.body.size
        val complete = state.received.all { it }
        progress.tryEmit(
            Progress(
                attachmentId = state.attachmentId,
                messageId = state.messageId,
                receivedBytes = state.receivedBytes,
                totalBytes = state.totalBytes,
                complete = complete,
            ),
        )
        if (complete) finish(chunk.transferId)
    }

    fun pathFor(attachmentId: String): String? =
        completed[attachmentId]?.absolutePath
            ?: inbound.values.firstOrNull { it.attachmentId == attachmentId }?.file?.absolutePath

    private fun finish(transferId: String) {
        val state = inbound.remove(transferId) ?: return
        state.close()
        completed[state.attachmentId] = state.file
        progress.tryEmit(
            Progress(
                attachmentId = state.attachmentId,
                messageId = state.messageId,
                receivedBytes = state.totalBytes,
                totalBytes = state.totalBytes,
                complete = true,
            ),
        )
    }

    private companion object {
        const val TAG = "DesktopTransfer"
    }
}
