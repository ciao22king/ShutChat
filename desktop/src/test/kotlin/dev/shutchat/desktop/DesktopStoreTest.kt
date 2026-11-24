package dev.shutchat.desktop

import dev.shutchat.app.domain.model.Chat
import dev.shutchat.app.domain.model.ChatKind
import dev.shutchat.app.domain.model.ChatStatus
import dev.shutchat.app.domain.model.Message
import dev.shutchat.app.domain.model.MessageKind
import dev.shutchat.app.domain.model.MessageStatus
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The transcript file is the only copy of a desktop conversation. */
class DesktopStoreTest {

    private fun tempDir() = Files.createTempDirectory("shutchat-store")

    private fun chat(id: String) = Chat(
        id = id,
        kind = ChatKind.DIRECT,
        title = "Chat",
        status = ChatStatus.ACTIVE,
        inviteCode = "CODE",
        inviteExpiryDay = 100,
        createdAt = 1,
        lastActivityAt = 1,
    )

    private fun message(id: String, chatId: String) = Message(
        id = id,
        chatId = chatId,
        senderNodeId = "node",
        senderName = "Luca",
        kind = MessageKind.TEXT,
        body = "ciao",
        createdAt = 5,
        status = MessageStatus.DELIVERED,
        outgoing = true,
    )

    @Test
    fun `an empty directory loads an empty state`() {
        val state = DesktopStore(tempDir()).load()
        assertTrue(state.chats.isEmpty())
        assertTrue(state.messages.isEmpty())
    }

    @Test
    fun `chats and messages survive a save and load`() {
        val dir = tempDir()
        val store = DesktopStore(dir)
        store.save(
            DesktopState(
                chats = listOf(chat("room1")),
                messages = mapOf("room1" to listOf(message("m1", "room1"))),
            ),
        )
        val loaded = DesktopStore(dir).load()
        assertEquals(1, loaded.chats.size)
        assertEquals("room1", loaded.chats.first().id)
        assertEquals(1, loaded.messages["room1"]?.size)
        assertEquals("ciao", loaded.messages["room1"]?.first()?.body)
    }

    @Test
    fun `a corrupt file degrades to an empty state instead of crashing`() {
        val dir = tempDir()
        Files.writeString(dir.resolve("state.json"), "{ this is not json")
        val state = DesktopStore(dir).load()
        assertTrue(state.chats.isEmpty())
    }

    @Test
    fun `saving leaves no temporary file behind`() {
        val dir = tempDir()
        DesktopStore(dir).save(DesktopState(chats = listOf(chat("r"))))
        val leftovers = Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".tmp") }.count()
        }
        assertEquals(0, leftovers)
    }
}
