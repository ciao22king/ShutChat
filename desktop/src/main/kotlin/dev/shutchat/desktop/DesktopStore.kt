package dev.shutchat.desktop

import dev.shutchat.app.domain.model.Chat
import dev.shutchat.app.domain.model.ChatMember
import dev.shutchat.app.domain.model.Message
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Everything the desktop client keeps between runs. */
@Serializable
data class DesktopState(
    val chats: List<Chat> = emptyList(),
    val messages: Map<String, List<Message>> = emptyMap(),
    val members: Map<String, List<ChatMember>> = emptyMap(),
    val drafts: Map<String, String> = emptyMap(),
)

/**
 * Transcript store.
 *
 * A single JSON file, written atomically (temp file then move) so a crash
 * mid-write cannot leave a half-parsed transcript behind. Only plaintext is
 * stored: the keys live in the invite code, and losing this file loses nothing
 * that cannot be fetched again from a peer.
 */
class DesktopStore(private val dir: Path = DesktopSettings.dataDir()) {

    private val file: Path = dir.resolve("state.json")
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): DesktopState {
        if (!Files.exists(file)) return DesktopState()
        return runCatching { json.decodeFromString<DesktopState>(Files.readString(file)) }
            .getOrElse { DesktopState() }
    }

    fun save(state: DesktopState) {
        runCatching {
            Files.createDirectories(dir)
            val temp = dir.resolve("state.json.tmp")
            Files.writeString(temp, json.encodeToString(DesktopState.serializer(), state))
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
