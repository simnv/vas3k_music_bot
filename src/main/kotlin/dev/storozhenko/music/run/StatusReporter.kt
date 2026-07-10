package dev.storozhenko.music.run

import dev.storozhenko.music.services.TelegramSender
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup

/** Edits one status message in place: progress keeps the cancel keyboard, failure strips it. */
class StatusReporter(
    private val sender: TelegramSender,
    private val chatId: Long,
    private val messageId: Int,
    private val baseMessage: String,
    private val cancelKb: InlineKeyboardMarkup?,
) {
    private val emptyKeyboard = InlineKeyboardMarkup.builder().build()

    suspend fun status(suffix: String) =
        sender.editMessageText(chatId, messageId, "$baseMessage\n$suffix", cancelKb)

    suspend fun fail(reason: String) =
        sender.editMessageText(chatId, messageId, "$baseMessage\n❌ $reason", emptyKeyboard)
}
