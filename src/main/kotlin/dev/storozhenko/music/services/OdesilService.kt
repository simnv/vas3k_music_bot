package dev.storozhenko.music.services

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import dev.storozhenko.music.OdesilEntity
import dev.storozhenko.music.OdesilResponse
import dev.storozhenko.music.getLogger
import org.telegram.telegrambots.meta.api.objects.MessageEntity
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.Charset

/**
 * Songlink/Odesli lookup.
 *
 * The keyless tier was withdrawn — unauthenticated requests now return
 * `401 PUBLIC_API_ACCESS_DEPRECATED` — so this is disabled unless [apiKey] is configured. Odesli's
 * own docs still describe auth as optional; they are out of date. Request a key from
 * developers@song.link and set ODESLI_API_KEY to re-enable it.
 */
class OdesilService(
    private val musicSearch: MusicSearchService? = null,
    private val apiKey: String? = null,
) {
    private val logger = getLogger()
    private val client = HttpClient.newBuilder().build()
    private val objectMapper = ObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    val enabled: Boolean get() = !apiKey.isNullOrBlank()

    suspend fun detect(messageEntity: MessageEntity): OdesilEntity? =
        detect(messageEntity.text)?.let { OdesilEntity(it, messageEntity) }

    suspend fun detect(url: String): OdesilResponse? {
        if (!enabled) return null
        val encodedUrl = URLEncoder.encode(url.substringBefore("?list="), Charset.defaultCharset())
        val keyParam = "&key=" + URLEncoder.encode(apiKey, Charset.defaultCharset())
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.song.link/v1-alpha.1/links?url=$encodedUrl$keyParam"))
            .build()
        val response = retryRequest(request) ?: return null
        val body = response.body()
        if (response.statusCode() != 200) {
            logger.info("Odesil fail: $body")
            return null
        }
        val parsed = objectMapper.readValue(body, OdesilResponse::class.java)
        return musicSearch?.enrichMissingPlatforms(parsed) ?: parsed
    }

    private fun retryRequest(request: HttpRequest): HttpResponse<String>? {
        for (i in 0..5) {
            try {
                return client.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: Exception) {
                logger.error("Exception occurred while trying to send request, retry number is $i", e)
            }
        }
        return null
    }
}

