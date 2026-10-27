package dev.shutchat.app.update

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The update check is the one part of the app that talks to the open internet, so
 * the decision logic is pinned here rather than left to a manual tap.
 */
class UpdateCheckerTest {

    private val releaseJson = """
        {
          "tag_name": "v1.2.0",
          "name": "ShutChat 1.2.0",
          "body": "Correzioni varie.",
          "html_url": "https://github.com/ciao22king/ShutChat/releases/tag/v1.2.0",
          "assets": [
            {"name": "ShutChat-1.2.0.apk",
             "browser_download_url": "https://example.invalid/ShutChat-1.2.0.apk",
             "size": 24370016}
          ]
        }
    """.trimIndent()

    // ------------------------------------------------------------ version order

    @Test
    fun `a higher patch wins`() {
        assertTrue(VersionCompare.isNewer("1.0.1", "1.0.0"))
        assertFalse(VersionCompare.isNewer("1.0.0", "1.0.1"))
    }

    @Test
    fun `a higher minor beats a higher patch`() {
        assertTrue(VersionCompare.isNewer("1.2.0", "1.1.9"))
    }

    @Test
    fun `numeric comparison does not fall for string ordering`() {
        // "1.10.0" < "1.9.0" as strings, but must be newer as versions.
        assertTrue(VersionCompare.isNewer("1.10.0", "1.9.0"))
    }

    @Test
    fun `the same version is not newer`() {
        assertFalse(VersionCompare.isNewer("1.0.0", "1.0.0"))
        assertEquals(0, VersionCompare.compare("1.0.0", "1.0.0"))
    }

    @Test
    fun `a missing component counts as zero`() {
        assertEquals(0, VersionCompare.compare("1.2", "1.2.0"))
        assertTrue(VersionCompare.isNewer("1.2.1", "1.2"))
    }

    @Test
    fun `a leading v is ignored`() {
        assertEquals(0, VersionCompare.compare("v1.0.0", "1.0.0"))
    }

    @Test
    fun `a prerelease suffix compares on its numeric part`() {
        assertEquals(0, VersionCompare.compare("1.2.0-beta1", "1.2.0"))
        assertFalse(VersionCompare.isNewer("1.2.0-beta1", "1.2.0"))
    }

    @Test
    fun `normalize rejects text with no numbers`() {
        assertNull(VersionCompare.normalize("latest"))
        assertNull(VersionCompare.normalize(""))
    }

    // ---------------------------------------------------------------- parsing

    @Test
    fun `a release payload is read into its parts`() {
        val release = ReleaseParser.parse(releaseJson)
        requireNotNull(release)
        assertEquals("v1.2.0", release.tag)
        assertEquals("1.2.0", release.version)
        assertEquals("ShutChat 1.2.0", release.title)
        assertEquals("https://example.invalid/ShutChat-1.2.0.apk", release.apkUrl)
        assertEquals(24370016L, release.apkSizeBytes)
    }

    @Test
    fun `a release without an apk still parses`() {
        val release = ReleaseParser.parse(
            """{"tag_name":"v1.1.0","html_url":"https://example.invalid","assets":[]}""",
        )
        requireNotNull(release)
        assertNull(release.apkUrl)
        assertEquals("v1.1.0", release.title)
    }

    @Test
    fun `unknown fields do not break parsing`() {
        val release = ReleaseParser.parse(
            """{"tag_name":"v1.0.0","surprise":{"nested":true},"assets":[]}""",
        )
        requireNotNull(release)
        assertEquals("1.0.0", release.version)
    }

    @Test
    fun `a tag with no numbers is rejected`() {
        assertNull(ReleaseParser.parse("""{"tag_name":"nightly","assets":[]}"""))
    }

    @Test
    fun `malformed json is rejected rather than thrown`() {
        assertNull(ReleaseParser.parse("<html>404</html>"))
    }

    // ---------------------------------------------------------------- decisions

    @Test
    fun `a newer release is reported as available`() = runTest {
        val checker = UpdateChecker({ releaseJson })
        val result = checker.check("1.0.0")
        assertTrue(result is UpdateResult.Available)
        assertEquals("1.2.0", (result as UpdateResult.Available).release.version)
    }

    @Test
    fun `the same release is reported as up to date`() = runTest {
        val checker = UpdateChecker({ releaseJson })
        val result = checker.check("1.2.0")
        assertTrue(result is UpdateResult.UpToDate)
    }

    @Test
    fun `a local build ahead of the feed is up to date, not an error`() = runTest {
        val checker = UpdateChecker({ releaseJson })
        val result = checker.check("1.3.0")
        assertTrue(result is UpdateResult.UpToDate)
    }

    @Test
    fun `an unreachable feed fails without throwing`() = runTest {
        val checker = UpdateChecker({ null })
        assertTrue(checker.check("1.0.0") is UpdateResult.Failed)
    }

    @Test
    fun `an unreadable feed fails without throwing`() = runTest {
        val checker = UpdateChecker({ "not json at all" })
        assertTrue(checker.check("1.0.0") is UpdateResult.Failed)
    }
}
