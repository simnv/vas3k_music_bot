package dev.storozhenko.music.run

import dev.storozhenko.music.getLogger
import dev.storozhenko.music.services.TelegramSender
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup

/** On startup, edits status messages left behind by a crash/restart and clears their markers. */
class OrphanSweeper(
    private val store: JobMarkerStore,
    private val sender: TelegramSender,
) {
    private val logger = getLogger()
    private val emptyKeyboard = InlineKeyboardMarkup.builder().build()

    suspend fun sweep() {
        val orphans = store.listAll()
        if (orphans.isEmpty()) return
        logger.info("Sweeping ${orphans.size} orphaned job(s) from previous run")
        orphans.forEach { (token, marker) ->
            runCatching {
                sender.editMessageText(
                    marker.chatId, marker.statusMessageId,
                    "❌ Bot was restarted, please send the link again.", emptyKeyboard,
                )
            }.onFailure { logger.warn("Failed to notify orphaned job $token: ${it.message}") }
            store.delete(token)
        }
    }
}
