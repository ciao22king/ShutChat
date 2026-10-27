package dev.shutchat.app.update

import dev.shutchat.app.core.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Plain HTTP GET, no HTTP client dependency.
 *
 * The app already carries Compose, Room and Nearby; pulling in Retrofit or Ktor
 * for a single request would be disproportionate. `HttpURLConnection` is enough
 * and is one less thing in the APK.
 */
object HttpReleaseSource : ReleaseSource {

    private const val TAG = "ReleaseSource"
    private const val TIMEOUT_MILLIS = 15_000

    override suspend fun fetch(url: String): String? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                // GitHub answers 403 without a User-Agent, so this is required,
                // not cosmetic.
                setRequestProperty("User-Agent", "ShutChat-Android")
                setRequestProperty("Accept", "application/vnd.github+json")
                instanceFollowRedirects = true
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                Log.d(TAG, "risposta $code per $url")
                return@withContext null
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.d(TAG, "richiesta fallita: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }
}
