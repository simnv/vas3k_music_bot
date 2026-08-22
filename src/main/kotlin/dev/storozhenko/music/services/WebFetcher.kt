package dev.storozhenko.music.services

import dev.storozhenko.music.getLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Minimal GET helper for the metadata lookups in [MusicResolver]. Separate from the services that
 * use it so tests can stub network access instead of reaching the internet.
 *
 * Built on OkHttp rather than java.net.http because the latter cannot use a SOCKS proxy, and
 * Yandex answers this host with HTTP 451 unless the request goes through one.
 */
class WebFetcher(
    private val virtualDispatcher: CoroutineDispatcher,
    socksProxy: String? = null,
) {
    private val logger = getLogger()

    private val direct = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        // Whole-call deadline. Without it a response that trickles in under the read timeout could
        // hold a download admission slot indefinitely.
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Null when no proxy is configured; callers then fall back to a direct request. */
    private val proxied: OkHttpClient? = parseProxy(socksProxy)?.let { address ->
        logger.info("WebFetcher SOCKS proxy enabled: ${address.hostString}:${address.port}")
        direct.newBuilder().proxy(Proxy(Proxy.Type.SOCKS, address)).build()
    }

    val proxyAvailable: Boolean get() = proxied != null

    /**
     * Accepts the same `socks5h://host:port` form as YTDL_PROXY. Unresolved on purpose: DNS must be
     * done by the proxy, which is what the `h` in socks5h means.
     */
    private fun parseProxy(value: String?): InetSocketAddress? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            val uri = URI(value)
            val host = uri.host ?: return null
            val port = uri.port.takeIf { it > 0 } ?: 1080
            InetSocketAddress.createUnresolved(host, port)
        }.getOrElse {
            logger.warn("Could not parse proxy '$value': ${it.message}")
            null
        }
    }

    /** Response body, or null on any non-200 / failure. Never throws, except to honour cancellation. */
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        useProxy: Boolean = false,
    ): String? = runInterruptible(virtualDispatcher) {
        if (useProxy && proxied == null) {
            // Going direct here is not a soft degradation: Yandex answers this host with 451, so the
            // lookup is certain to fail and the user just sees "track not found".
            logger.warn("$url needs the SOCKS proxy but none is configured (set YTDL_PROXY); it will likely fail")
        }
        val client = if (useProxy) proxied ?: direct else direct
        try {
            val builder = Request.Builder().url(url)
            // Yandex serves a JS shell to unknown agents; a browser UA gets the real markup.
            builder.header("User-Agent", BROWSER_UA)
            headers.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    logger.info("GET $url -> HTTP ${response.code}")
                    null
                } else {
                    response.body?.string()
                }
            }
        } catch (e: InterruptedException) {
            // Cancellation must propagate: the user's cancel button relies on it.
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.info("GET $url failed: ${e.message}")
            null
        }
    }

    companion object {
        const val BROWSER_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
    }
}
