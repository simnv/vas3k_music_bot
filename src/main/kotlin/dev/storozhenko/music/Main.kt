package dev.storozhenko.music

import dev.storozhenko.music.getLogger
import dev.storozhenko.music.run.Bot
import dev.storozhenko.music.services.TokenStorage
import org.telegram.telegrambots.meta.TelegramUrl
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication

private val botToken = getEnv("TELEGRAM_API_TOKEN")
private val botUsername = getEnv("TELEGRAM_BOT_USERNAME")
private val tokenStoragePath = getEnv("TOKEN_STORAGE_PATH")
private val telegramBaseSchema = getEnv("TELEGRAM_BASE_SCHEMA")
private val telegramBaseURL = getEnv("TELEGRAM_BASE_URL")
private val telegramBasePort = getEnv("TELEGRAM_BASE_PORT").toInt()
private val ytdlLocation = getEnv("YTDL_LOCATION")
private val telegramAllowList = getEnv("TELEGRAM_ALLOW_LIST")

fun main() {
    val telegramBotsApi = TelegramBotsLongPollingApplication()

    val tokenStorage = TokenStorage(tokenStoragePath)
    val telegramURL = TelegramUrl(telegramBaseSchema, telegramBaseURL, telegramBasePort, false)
    val telegramClient = OkHttpTelegramClient(botToken, telegramURL)
    val bot = Bot(botUsername, ytdlLocation, telegramClient, telegramAllowList)
    try {
        telegramBotsApi.registerBot(botToken, bot)
    } catch (e: Exception) {
        println("Can't register bot $bot with $telegramClient: $e")
    }
}

private fun getEnv(envName: String): String {
    return System.getenv()[envName]?.takeIf(String::isNotBlank) ?: throw IllegalStateException("$envName does not exist or empty")
}