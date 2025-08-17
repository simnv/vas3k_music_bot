package dev.storozhenko.music

import dev.storozhenko.music.getLogger
import dev.storozhenko.music.run.Bot
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import org.telegram.telegrambots.meta.TelegramUrl
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication
import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Response

private val botToken = getEnv("TELEGRAM_API_TOKEN")
private val botUsername = getEnv("TELEGRAM_BOT_USERNAME")
private val telegramBaseSchema = System.getenv()["TELEGRAM_BASE_SCHEMA"]?.takeIf(String::isNotBlank) ?: "https"
private val telegramBaseURL = System.getenv()["TELEGRAM_BASE_URL"]?.takeIf(String::isNotBlank) ?: "api.telegram.org"
private val telegramBasePort = System.getenv()["TELEGRAM_BASE_PORT"]?.takeIf(String::isNotBlank)?.toInt() ?: 443
private val ytdlLocation = System.getenv()["YTDL_LOCATION"]?.takeIf(String::isNotBlank) ?: "/usr/local/bin/yt-dlp"
private val telegramAllowList = getEnv("TELEGRAM_ALLOW_LIST")
private val errorNotificationTelegramId = System.getenv()["ERROR_NOTIFICATION_TELEGRAM_ID"]?.takeIf(String::isNotBlank)
private val ipv6UrlContains = System.getenv()["IPV6_URL_CONTAINS"]?.takeIf(String::isNotBlank)
private val chunkSizeMB = System.getenv()["CHUNK_SIZE_MB"]?.takeIf(String::isNotBlank)?.toIntOrNull() ?: 50

class RetryInterceptor(private val maxRetries: Int) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        var retryCount = 0
        var lastResponse: Response? = null
        var lastException: IOException? = null

        while (retryCount < maxRetries) {
            try {
                lastResponse?.close() // Close previous response if exists
                lastResponse = chain.proceed(request)

                if (lastResponse.isSuccessful) {
                    return lastResponse
                }

                // Only retry on server errors (5xx) or network errors
                if (lastResponse.code < 400) {
                    return lastResponse
                }

            } catch (e: IOException) {
                lastException = e
            }

            retryCount++

            if (retryCount < maxRetries) {
                try {
                    // Exponential backoff: 1s, 2s, 4s, 8s, 16s
                    val waitTime = (1L shl retryCount) * 1000
                    Thread.sleep(waitTime)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Interrupted during retry", e)
                }
            }
        }

        lastResponse?.let {
            return it // Return the last response if we have one
        } ?: throw lastException ?: IOException("Unknown error occurred")
    }
}

fun main() {
    val telegramBotsApi = TelegramBotsLongPollingApplication()
    val telegramURL = TelegramUrl(telegramBaseSchema, telegramBaseURL, telegramBasePort, false)
    val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS) // Connection timeout
        .readTimeout(900, TimeUnit.SECONDS)   // Read timeout (for responses)
        .writeTimeout(900, TimeUnit.SECONDS)  // Write timeout (for requests)
        .callTimeout(1800, TimeUnit.SECONDS)   // Total operation timeout
        .addInterceptor(RetryInterceptor(maxRetries = 5))
        .build()
    val telegramClient = OkHttpTelegramClient(httpClient, botToken, telegramURL)
    val bot = Bot(botUsername, ytdlLocation, telegramClient, telegramAllowList, errorNotificationTelegramId, ipv6UrlContains, chunkSizeMB)
    try {
        telegramBotsApi.registerBot(botToken, bot)
    } catch (e: Exception) {
        println("Can't register bot $bot with $telegramClient: $e")
    }
}

private fun getEnv(envName: String): String {
    return System.getenv()[envName]?.takeIf(String::isNotBlank) ?: throw IllegalStateException("$envName does not exist or empty")
}
