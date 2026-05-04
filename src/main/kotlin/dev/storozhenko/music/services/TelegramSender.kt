package dev.storozhenko.music.services

import dev.storozhenko.music.getLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction
import org.telegram.telegrambots.meta.api.methods.send.SendVideo
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageMedia
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.telegram.telegrambots.meta.api.objects.LinkPreviewOptions
import org.telegram.telegrambots.meta.api.objects.media.InputMediaAudio
import org.telegram.telegrambots.meta.api.objects.media.InputMediaVideo
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.io.Closeable
import java.io.File

class TelegramSender(
    private val telegramClient: TelegramClient,
    private val botScope: CoroutineScope
) {
    private val logger = getLogger()

    fun startPulser(chatId: Long, initialAction: String): ChatActionPulser =
        ChatActionPulser(chatId, initialAction)

    inner class ChatActionPulser(private val chatId: Long, initialAction: String) : Closeable {
        @Volatile private var action: String = initialAction
        private val job: Job = botScope.launch {
            while (isActive) {
                try {
                    telegramClient.executeAsync(SendChatAction(chatId.toString(), action)).await()
                } catch (e: Exception) {
                    logger.info("Failed to send chat action: ${e.message}")
                }
                delay(5_000)
            }
        }
        fun set(newAction: String) { action = newAction }
        override fun close() { job.cancel() }
    }

    suspend fun deleteMessage(chatId: Long, messageId: Int) {
        try {
            telegramClient.executeAsync(
                DeleteMessage.builder().chatId(chatId.toString()).messageId(messageId).build()
            ).await()
        } catch (e: TelegramApiException) {
            logger.info("Failed to delete message: ${e.message}")
        }
    }

    suspend fun removeKeyboard(chatId: Long, messageId: Int) {
        val edit = EditMessageReplyMarkup.builder()
            .chatId(chatId.toString())
            .messageId(messageId)
            .replyMarkup(InlineKeyboardMarkup.builder().build())
            .build()
        try {
            telegramClient.executeAsync(edit).await()
        } catch (e: TelegramApiException) {
            logger.info("Failed to remove keyboard: ${e.message}")
        }
    }

    suspend fun editMessageText(chatId: Long, messageId: Int, newText: String, replyMarkup: InlineKeyboardMarkup? = null) {
        val builder = EditMessageText.builder()
            .chatId(chatId.toString())
            .messageId(messageId)
            .text(newText)
            .parseMode("HTML")
            .linkPreviewOptions(LinkPreviewOptions.builder().isDisabled(true).build())
        replyMarkup?.let { builder.replyMarkup(it) }
        try {
            telegramClient.executeAsync(builder.build()).await()
        } catch (e: TelegramApiException) {
            logger.info("Failed to edit message: ${e.message}")
        }
    }

    suspend fun sendAudioInPlace(
        audioFile: File,
        chatId: Long,
        intermediateMessageId: Int,
        caption: String,
        artist: String,
        title: String,
        duration: Int? = null,
        thumbnailFile: File? = null
    ) {
        val audioMedia = InputMediaAudio(audioFile, "audio").also {
            it.caption = caption
            it.parseMode = "HTML"
            it.performer = artist
            it.title = title
            duration?.let { d -> it.duration = d }
            thumbnailFile?.let { tf -> it.thumbnail = InputFile(tf) }
        }
        val edit = EditMessageMedia.builder()
            .chatId(chatId.toString())
            .messageId(intermediateMessageId)
            .media(audioMedia)
            .build()
        telegramClient.executeAsync(edit).await()
    }

    suspend fun sendVideoInPlace(
        chatId: Long,
        intermediateMessageId: Int,
        videoFile: File,
        width: Int,
        height: Int,
        duration: Int,
        caption: String,
        thumbnailFile: File? = null
    ) {
        val videoMedia = InputMediaVideo(videoFile, "video").also {
            it.caption = caption
            it.parseMode = "HTML"
            it.width = width
            it.height = height
            it.duration = duration
            it.supportsStreaming = true
            it.showCaptionAboveMedia = true
            thumbnailFile?.let { tf ->
                it.thumbnail = InputFile(tf)
                it.cover = InputFile(tf)
            }
        }
        val edit = EditMessageMedia.builder()
            .chatId(chatId.toString())
            .messageId(intermediateMessageId)
            .media(videoMedia)
            .build()
        telegramClient.executeAsync(edit).await()
    }

    suspend fun sendVideoChunks(
        chatId: Long,
        intermediateMessageId: Int,
        replyToMessageId: Int,
        chunks: List<Pair<File, VideoDimensions>>,
        baseMessage: String,
        thumbnailFile: File?,
        pulser: ChatActionPulser,
        replyMarkup: InlineKeyboardMarkup? = null
    ): Boolean {
        val total = chunks.size
        chunks.forEachIndexed { index, (file, dims) ->
            val number = index + 1
            val statusMessage = if (total > 1) "$baseMessage\nSending Video (Part $number of $total)..."
                                else "$baseMessage\nSending Video..."
            editMessageText(chatId, intermediateMessageId, statusMessage, replyMarkup)
            logger.info("Sending Video chunk $number of $total...")
            pulser.set("upload_video")
            val caption = if (total > 1) "$baseMessage\n\nPart $number of $total" else baseMessage
            try {
                if (number == 1) {
                    sendVideoInPlace(chatId, intermediateMessageId, file, dims.width, dims.height, dims.duration, caption, thumbnailFile)
                } else {
                    sendVideoChunk(chatId, replyToMessageId, file, dims.width, dims.height, dims.duration, caption, thumbnailFile)
                }
                logger.info("Video chunk $number sent successfully")
                if (number < total) delay(1000)
            } catch (e: TelegramApiException) {
                logger.error("Failed to send video chunk $number: ${e.message}", e)
                editMessageText(chatId, intermediateMessageId, "$baseMessage\n❌ Failed to send video chunk $number: ${e.message}")
                return false
            }
        }
        return true
    }

    suspend fun sendVideoChunk(
        chatId: Long,
        replyToMessageId: Int,
        videoFile: File,
        width: Int,
        height: Int,
        duration: Int,
        caption: String,
        thumbnailFile: File? = null
    ) {
        val videoBuilder = SendVideo.builder()
            .video(InputFile(videoFile))
            .width(width)
            .height(height)
            .duration(duration)
            .caption(caption)
            .parseMode("HTML")
            .showCaptionAboveMedia(true)
            .supportsStreaming(true)
            .disableNotification(true)
            .chatId(chatId)
            .replyToMessageId(replyToMessageId)
        thumbnailFile?.let {
            videoBuilder.thumbnail(InputFile(it))
            videoBuilder.cover(InputFile(it))
        }
        telegramClient.executeAsync(videoBuilder.build()).await()
    }
}
