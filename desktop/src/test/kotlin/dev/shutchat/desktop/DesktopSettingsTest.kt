package dev.shutchat.desktop

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The node id must never change: the relay session and peers depend on it. */
class DesktopSettingsTest {

    private fun tempDir() = Files.createTempDirectory("shutchat-settings")

    @Test
    fun `the node id is generated once and then reused`() {
        val dir = tempDir()
        val first = DesktopSettings(dir).nodeId
        val second = DesktopSettings(dir).nodeId
        assertTrue(first.isNotBlank())
        assertEquals(first, second)
    }

    @Test
    fun `two installations get different node ids`() {
        assertTrue(DesktopSettings(tempDir()).nodeId != DesktopSettings(tempDir()).nodeId)
    }

    @Test
    fun `settings persist across restarts`() {
        val dir = tempDir()
        DesktopSettings(dir).update(displayName = "Luca", publicRelay = false, lanDiscovery = false)
        val reloaded = DesktopSettings(dir)
        assertEquals("Luca", reloaded.displayName)
        assertFalse(reloaded.publicRelay)
        assertFalse(reloaded.lanDiscovery)
    }

    @Test
    fun `the relay is on by default because that is what makes distance work`() {
        assertTrue(DesktopSettings(tempDir()).publicRelay)
    }

    @Test
    fun `a blank name is not accepted`() {
        val dir = tempDir()
        val settings = DesktopSettings(dir)
        val original = settings.displayName
        settings.update(displayName = "   ")
        assertEquals(original, DesktopSettings(dir).displayName)
    }
}
