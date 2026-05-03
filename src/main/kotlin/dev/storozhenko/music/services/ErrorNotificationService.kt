package dev.storozhenko.music.services

import dev.storozhenko.music.getLogger
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.time.Instant
import java.time.format.DateTimeFormatter

class ErrorNotificationService(
    private val telegramClient: TelegramClient,
    private val errorNotificationTelegramId: String
) {
    private val logger = getLogger()
    private val lastNotificationTime = mutableMapOf<String, Long>()
    private val NOTIFICATION_COOLDOWN_MS = 60_000 // 1 minute cooldown between similar errors

    fun sendErrorNotification(throwable: Throwable, context: String = "") {
        try {
            val errorKey = "${throwable.javaClass.simpleName}:${throwable.message?.take(50) ?: "no_message"}"
            val currentTime = System.currentTimeMillis()
            
            // Check cooldown to avoid spam
            val lastTime = lastNotificationTime[errorKey] ?: 0
            if (currentTime - lastTime < NOTIFICATION_COOLDOWN_MS) {
                logger.info("Skipping error notification due to cooldown: $errorKey")
                return
            }
            
            lastNotificationTime[errorKey] = currentTime
            
            val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
            val errorMessage = buildString {
                append("🚨 <b>Error Notification</b>\n\n")
                append("<b>Time:</b> <code>$timestamp</code>\n")
                append("<b>Error Type:</b> <code>${throwable.javaClass.simpleName}</code>\n")
                
                throwable.message?.let { message ->
                    append("<b>Message:</b> <code>${message.take(200)}</code>\n")
                }
                
                if (context.isNotEmpty()) {
                    append("<b>Context:</b> <code>$context</code>\n")
                }
                
                // Add stack trace (truncated)
                val stackTrace = throwable.stackTraceToString()
                if (stackTrace.length > 500) {
                    append("<b>Stack Trace:</b>\n<code>${stackTrace.take(500)}...</code>")
                } else {
                    append("<b>Stack Trace:</b>\n<code>$stackTrace</code>")
                }
            }
            
            val sendMessage = SendMessage.builder()
                .chatId(errorNotificationTelegramId)
                .text(errorMessage)
                .parseMode("HTML")
                .build()
            
            telegramClient.execute(sendMessage)
            logger.info("Error notification sent successfully")
        } catch (e: Exception) {
            // Don't use sendErrorNotification here to avoid infinite recursion
            logger.error("Failed to send error notification: ${e.message}", e)
        }
    }
    
    fun sendStartupErrorNotification(throwable: Throwable) {
        sendErrorNotification(throwable, "Application Startup")
    }
    
    fun sendCommandErrorNotification(throwable: Throwable, command: String) {
        sendErrorNotification(throwable, "Command Processing: $command")
    }
    
    fun sendUpdateProcessingErrorNotification(throwable: Throwable) {
        sendErrorNotification(throwable, "Update Processing")
    }
    
    fun sendServiceErrorNotification(throwable: Throwable, serviceName: String) {
        sendErrorNotification(throwable, "Service Error: $serviceName")
    }
    public fun sendMessageWithSourceInfo(message: String, authorName: String?, authorUsername: String?, chatId: Long, messageId: Int, chatTitle: String, requestMode: String? = null) {
        try {
            val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
            val chatLinkId = chatId.toString().removePrefix("-100")
            val notificationMessage = buildString {
                append("ℹ️ <b>Request from</b> ")

                if (authorName != null && authorUsername != null) {
                    append("$authorName (@$authorUsername)")
                } else if (authorName != null) {
                    append(authorName)
                } else if (authorUsername != null) {
                    append("@$authorUsername")
                }

                append(" in <a href=\"https://t.me/c/$chatLinkId/$messageId\">$chatTitle</a>")
                if (requestMode != null) {
                    append("\n<b>Mode:</b> <code>$requestMode</code>")
                }
                append("\n\n$message")
            }
            
            val sendMessage = SendMessage.builder()
                .chatId(errorNotificationTelegramId)
                .text(notificationMessage)
                .parseMode("HTML")
                .disableWebPagePreview(true)
                .build()
            
            telegramClient.execute(sendMessage)
            logger.info("Message with source info sent successfully")
        } catch (e: Exception) {
            logger.error("Failed to send message with source info: ${e.message}", e)
        }
    }
}

