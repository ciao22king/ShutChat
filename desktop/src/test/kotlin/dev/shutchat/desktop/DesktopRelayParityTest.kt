package dev.shutchat.desktop

import dev.shutchat.app.domain.model.Chat
import dev.shutchat.app.domain.model.MessageKind
import dev.shutchat.app.p2p.call.CallPhase
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Two desktop clients, one room, over the public relay.
 *
 * This is the only test that proves the pieces fit together the way a user runs
 * them: create a code on one side, join with it on the other, and the text and
 * the file arrive. It needs internet and the public broker, so it is opt-in with
 * `-Dshutchat.it=1`, exactly like the existing relay round-trip test.
 */
class DesktopRelayParityTest {

    private fun client(tag: String): Pair<DesktopApp, File> {
        val root = Files.createTempDirectory("shutchat-$tag").toFile()
        val settings = DesktopSettings(root.toPath().resolve("config"))
        settings.update(displayName = tag, publicRelay = true, lanDiscovery = false)
        val app = DesktopApp(
            settings = settings,
            store = DesktopStore(root.toPath().resolve("data")),
            files = DesktopFiles(File(root, "files")),
        )
        app.start()
        return app to root
    }

    @Test
    fun `two clients exchange a message and a file over the relay`() = runBlocking {
        assumeTrue("set -Dshutchat.it=1 to run", System.getProperty("shutchat.it") == "1")

        val (alice, aliceRoot) = client("alice")
        val (bob, bobRoot) = client("bob")
        try {
            val chat = alice.createChat(dev.shutchat.app.domain.model.InviteKind.DIRECT, null)
            val code = chat.inviteCode
            assertNotNull("the code must be generated", code)

            val joined = bob.joinChat(code!!)
            assertNotNull("bob must be able to join", joined)

            // Both sides need a moment to connect to the broker and announce.
            delay(8_000)

            alice.send(chat.id, "hello from the desktop")
            val received = withTimeoutOrNull(60_000) {
                var found: String? = null
                while (found == null) {
                    found = bob.messages.value[chat.id].orEmpty()
                        .firstOrNull { it.body == "hello from the desktop" }
                        ?.body
                    delay(300)
                }
                found
            }
            assertEquals("hello from the desktop", received)

            val reply = bob.messages.value[chat.id].orEmpty().first { it.body == "hello from the desktop" }
            bob.send(chat.id, "and hello back", reply)
            val back = withTimeoutOrNull(60_000) {
                var found: String? = null
                while (found == null) {
                    found = alice.messages.value[chat.id].orEmpty()
                        .firstOrNull { it.body == "and hello back" }
                        ?.body
                    delay(300)
                }
                found
            }
            assertEquals("and hello back", back)

            val replyOnAlice = alice.messages.value[chat.id].orEmpty().first { it.body == "and hello back" }
            assertEquals("the reply must carry the quoted message", reply.id, replyOnAlice.replyToId)

            val source = File(aliceRoot, "note.txt").apply {
                writeText("a file that has to survive the trip\n".repeat(4_000))
            }
            alice.sendAttachment(chat.id, source, MessageKind.FILE)

            val delivered = withTimeoutOrNull(90_000) {
                var path: String? = null
                while (path == null) {
                    path = bob.messages.value[chat.id].orEmpty()
                        .firstOrNull { it.kind == MessageKind.FILE }
                        ?.attachment
                        ?.localPath
                        ?.takeIf { File(it).length() == source.length() }
                    delay(500)
                }
                path
            }
            assertNotNull("the file must arrive whole", delivered)
            assertTrue(File(delivered!!).readText().startsWith("a file that has to survive"))
        } finally {
            alice.stop()
            bob.stop()
            aliceRoot.deleteRecursively()
            bobRoot.deleteRecursively()
        }
    }

    @Test
    fun `two clients place a call over the relay`() = runBlocking {
        assumeTrue("set -Dshutchat.it=1 to run", System.getProperty("shutchat.it") == "1")

        val (alice, aliceRoot) = client("caller")
        val (bob, bobRoot) = client("callee")
        try {
            val chat = alice.createChat(dev.shutchat.app.domain.model.InviteKind.DIRECT, null)
            bob.joinChat(chat.inviteCode!!)
            delay(8_000)

            alice.call(chat.id, video = false)
            val ringing = withTimeoutOrNull(60_000) {
                while (bob.calls.state.value.phase != CallPhase.RINGING) delay(250)
                true
            }
            assertEquals("the other side must ring", true, ringing)
            bob.calls.accept()

            val active = withTimeoutOrNull(60_000) {
                while (alice.calls.state.value.phase != CallPhase.ACTIVE ||
                    bob.calls.state.value.phase != CallPhase.ACTIVE
                ) {
                    delay(250)
                }
                true
            }
            assertEquals("both sides must reach the media path", true, active)
        } finally {
            alice.calls.hangUp()
            alice.stop()
            bob.stop()
            aliceRoot.deleteRecursively()
            bobRoot.deleteRecursively()
        }
    }

    @Test
    fun `a chat survives a restart`() = runBlocking {
        val root = Files.createTempDirectory("shutchat-restart").toFile()
        try {
            val settings = DesktopSettings(root.toPath().resolve("config"))
            settings.update(displayName = "persist", publicRelay = false, lanDiscovery = false)
            val first = DesktopApp(
                settings = settings,
                store = DesktopStore(root.toPath().resolve("data")),
                files = DesktopFiles(File(root, "files")),
            )
            first.start()
            val chat: Chat = first.createChat(dev.shutchat.app.domain.model.InviteKind.GROUP, null, "Room")
            first.send(chat.id, "written once")
            first.setDraft(chat.id, "half typed")
            delay(1_500)
            first.stop()

            val second = DesktopApp(
                settings = DesktopSettings(root.toPath().resolve("config")),
                store = DesktopStore(root.toPath().resolve("data")),
                files = DesktopFiles(File(root, "files")),
            )
            second.start()
            try {
                assertEquals(1, second.chats.value.size)
                assertEquals("Room", second.chats.value.first().title)
                assertEquals("written once", second.messages.value[chat.id]?.firstOrNull()?.body)
                assertEquals("half typed", second.drafts.value[chat.id])
            } finally {
                second.stop()
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
