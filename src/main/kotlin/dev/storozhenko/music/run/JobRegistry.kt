package dev.storozhenko.music.run

import kotlinx.coroutines.Job
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow
import java.util.concurrent.ConcurrentHashMap

data class CancellableJob(
    val job: Job,
    val url: String?,
    val chatId: Long,
    val chatTitle: String,
    val originalMessageId: Int,
)

class JobRegistry {
    private val activeJobs = ConcurrentHashMap<String, CancellableJob>()

    fun register(token: String, entry: CancellableJob) {
        activeJobs[token] = entry
    }

    /** Removes and returns the entry, or null if already finished. The caller decides whether to cancel. */
    fun take(token: String): CancellableJob? = activeJobs.remove(token)

    fun remove(token: String) {
        activeJobs.remove(token)
    }

    fun cancelKeyboard(token: String): InlineKeyboardMarkup {
        val button = InlineKeyboardButton.builder()
            .text("❌ Отмена")
            .callbackData("cancel:$token")
            .build()
        val row = InlineKeyboardRow().apply { add(button) }
        return InlineKeyboardMarkup.builder().keyboardRow(row).build()
    }
}
