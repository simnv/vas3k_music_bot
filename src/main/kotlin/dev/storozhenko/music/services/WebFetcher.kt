package dev.storozhenko.music.services

import dev.storozhenko.music.getLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Minimal GET helper for the metadata lookups in [MusicResolver]. Separate from the services that
 * use it so tests can stub network access instead of reaching the internet.
 */
class WebFetcher(
    private val virtualDispatcher: CoroutineDispatcher,
    private val timeout: Duration = Duration.ofSeconds(6),
) {
    private val logger = getLogger()
    private val client = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** Response body, or null on any non-200 / failure. Never throws, except to honour cancellation. */
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String? =
        runInterruptible(virtualDispatcher) {
            runCatching {
                val builder = HttpRequest.newBuilder().uri(URI.create(url)).timeout(timeout)
                // Yandex serves a JS shell to unknown agents; a browser UA gets the real markup.
                builder.header("User-Agent", BROWSER_UA)
                headers.forEach { (k, v) -> builder.header(k, v) }
                val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() != 200) {
                    logger.info("GET $url -> HTTP ${response.statusCode()}")
                    null
                } else {
                    response.body()
                }
            }.getOrElse {
                // Cancellation must propagate: the user's cancel button relies on it, and
                // swallowing it here would leave the job running after the message is deleted.
                if (it is InterruptedException) throw it
                logger.info("GET $url failed: ${it.message}")
                null
            }
        }

    companion object {
        const val BROWSER_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
    }
}
