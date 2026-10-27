package dev.shutchat.app.p2p

import dev.shutchat.app.core.Log
import dev.shutchat.app.core.hexToBytes
import dev.shutchat.app.core.toHex
import dev.shutchat.app.crypto.RoomCrypto
import dev.shutchat.app.media.MediaStore
import dev.shutchat.app.p2p.protocol.Envelope
import dev.shutchat.app.p2p.protocol.Protocol
import dev.shutchat.app.p2p.protocol.TransferCancel
import dev.shutchat.app.p2p.protocol.TransferComplete
import dev.shutchat.app.p2p.protocol.TransferStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Chunked, resumable attachment transfer.
 *
 * Files are split into 32 kB chunks and each chunk is sealed with the room's
 * transfer key, which is derived from the invite secret. Two consequences worth
 * noting:
 *
 *  * **Resumable.** The receiver tracks which chunk indices have landed, so a
 *    link that drops at 70% resumes at 70% rather than restarting.
 *  * **Relay friendly.** Chunks are individually encrypted but the room id
 *    travels in the frame header, so a relaying device can still route them.
 *
 * The same file sent to a group is encrypted once and broadcast once: the room
 * key is shared by every member, so there is no per-pair fan-out to pay for.
 */
class AttachmentTransfer(
    private val scope: CoroutineScope,
    private val mesh: MeshEngine,
    private val mediaStore: MediaStore,
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

    /**
     * Sends [file] to every peer in the room.
     *
     * @return false when no peer is reachable, so the caller can leave the
     *         attachment marked as pending instead of pretending it was sent.
     */
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
        if (announced == 0) {
            Log.d(TAG, "nessun peer raggiungibile per $attachmentId")
            return false
        }

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
                val frame = dev.shutchat.app.p2p.protocol.Framing.frameChunk(
                    roomId = roomIdBytes,
                    transferId = transferId,
                    index = index,
                    total = totalChunks,
                    body = sealed,
                )
                if (mesh.sendFrame(roomIdHex, frame) == 0) {
                    Log.d(TAG, "trasferimento $attachmentId interrotto al chunk $index")
                    mesh.broadcast(
                        TransferCancel(roomIdHex, transferId.toHex(), "peer non raggiungibile"),
                    )
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

    /** Handles the announcement that precedes a transfer. */
    fun onEnvelope(envelope: Envelope) {
        when (envelope) {
            is TransferStart -> {
                if (inbound.containsKey(envelope.transferId)) return
                val destination = mediaStore.destinationFor(envelope.attachmentId, envelope.fileName)
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        inbound[envelope.transferId] = Inbound(
                            attachmentId = envelope.attachmentId,
                            messageId = envelope.messageId,
                            totalChunks = envelope.totalChunks,
                            totalBytes = envelope.totalBytes,
                            file = destination,
                        )
                    }.onFailure { Log.w(TAG, "impossibile aprire ${destination.name}", it) }
                }
            }

            is TransferComplete -> finish(envelope.transferId)

            is TransferCancel -> {
                inbound.remove(envelope.transferId)?.close()
                Log.d(TAG, "trasferimento annullato: ${envelope.reason}")
            }

            else -> Unit
        }
    }

    /** Handles a chunk routed up by the mesh. */
    fun onChunk(chunk: MeshEngine.InboundChunk) {
        val state = inbound[chunk.transferId] ?: return
        if (chunk.index !in state.received.indices) return
        if (state.received[chunk.index]) return

        state.received[chunk.index] = true
        state.receivedBytes += chunk.body.size

        scope.launch(Dispatchers.IO) {
            runCatching {
                synchronized(state) {
                    state.handle.seek(chunk.index.toLong() * Protocol.CHUNK_SIZE)
                    state.handle.write(chunk.body)
                }
            }.onFailure { Log.w(TAG, "scrittura chunk ${chunk.index} fallita", it) }

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
            if (complete) {
                // The complete envelope may have been seen before the last chunk
                // was written, so finishing here as well keeps the file usable.
                finish(chunk.transferId)
            }
        }
    }

    /** @return the local path when the transfer is done, else null. */
    fun localPathFor(transferId: String): String? = inbound[transferId]?.file?.absolutePath

    private fun finish(transferId: String) {
        val state = inbound.remove(transferId) ?: return
        state.close()
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

    fun cancel(transferId: String) {
        inbound.remove(transferId)?.close()
    }

    fun cancelAll() {
        inbound.keys.toList().forEach(::cancel)
    }

    private companion object {
        const val TAG = "AttachmentTransfer"
    }
}
