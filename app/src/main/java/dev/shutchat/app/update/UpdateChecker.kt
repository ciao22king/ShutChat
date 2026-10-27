package dev.shutchat.app.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A published build, as described by the release feed. */
data class ReleaseInfo(
    val tag: String,
    val version: String,
    val title: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String?,
    val apkSizeBytes: Long,
)

/** Outcome of asking whether a newer build exists. */
sealed interface UpdateResult {

    /** Nothing newer is published. */
    data class UpToDate(val current: String) : UpdateResult

    /** A newer build is available. */
    data class Available(val release: ReleaseInfo, val current: String) : UpdateResult

    /** The check could not complete. The reason is user facing. */
    data class Failed(val reason: String) : UpdateResult
}

/**
 * Parses the release feed.
 *
 * Split from the transport on purpose: the parsing rules are the part that can be
 * wrong in a way nobody notices, so they are unit tested against captured
 * payloads without touching the network.
 */
object ReleaseParser {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Asset(
        val name: String = "",
        @SerialName("browser_download_url") val downloadUrl: String = "",
        val size: Long = 0,
    )

    @Serializable
    private data class Payload(
        @SerialName("tag_name") val tagName: String = "",
        val name: String = "",
        val body: String = "",
        @SerialName("html_url") val htmlUrl: String = "",
        val assets: List<Asset> = emptyList(),
    )

    /**
     * @return the release, or null if the payload is not a usable release. A
     *         missing APK is not fatal: the release page is still worth showing,
     *         so the user can download by hand.
     */
    fun parse(body: String): ReleaseInfo? {
        val payload = runCatching { json.decodeFromString<Payload>(body) }.getOrNull() ?: return null
        val version = VersionCompare.normalize(payload.tagName) ?: return null
        val apk = payload.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
        return ReleaseInfo(
            tag = payload.tagName,
            version = version,
            title = payload.name.ifBlank { payload.tagName },
            notes = payload.body,
            pageUrl = payload.htmlUrl,
            apkUrl = apk?.downloadUrl?.takeIf { it.isNotBlank() },
            apkSizeBytes = apk?.size ?: 0L,
        )
    }
}

/** Fetches a URL as text. Injected so the checker can be exercised without a network. */
fun interface ReleaseSource {
    /** @return the response body, or null when the request failed. */
    suspend fun fetch(url: String): String?
}

/**
 * Answers "is there a newer version than the one I am running".
 *
 * There is no update server of our own: the releases already published on the
 * project page are the feed. That keeps the promise the rest of the app makes,
 * since no new party learns that a given device exists.
 */
class UpdateChecker(
    private val source: ReleaseSource,
    private val feedUrl: String = DEFAULT_FEED,
) {

    suspend fun check(currentVersion: String): UpdateResult {
        val body = source.fetch(feedUrl)
            ?: return UpdateResult.Failed("Non riesco a raggiungere il sito delle release")

        val release = ReleaseParser.parse(body)
            ?: return UpdateResult.Failed("La risposta delle release non è leggibile")

        return if (VersionCompare.isNewer(release.version, currentVersion)) {
            UpdateResult.Available(release, currentVersion)
        } else {
            UpdateResult.UpToDate(currentVersion)
        }
    }

    companion object {
        const val DEFAULT_FEED = "https://api.github.com/repos/ciao22king/ShutChat/releases/latest"
    }
}
