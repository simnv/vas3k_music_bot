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
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageMedia
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.telegram.telegrambots.meta.api.objects.LinkPreviewOptions
import org.telegram.telegrambots.meta.api.objects.media.InputMediaAudio
import org.telegram.telegrambots.meta.api.objects.media.InputMediaVideo
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

    suspend fun editMessageText(chatId: Long, messageId: Int, newText: String) {
        val editMessage = EditMessageText.builder()
            .chatId(chatId.toString())
            .messageId(messageId)
            .text(newText)
            .parseMode("HTML")
            .linkPreviewOptions(LinkPreviewOptions.builder().isDisabled(true).build())
            .build()
        try {
            telegramClient.executeAsync(editMessage).await()
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
