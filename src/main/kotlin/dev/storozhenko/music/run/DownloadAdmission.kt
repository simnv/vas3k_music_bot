package dev.storozhenko.music.run

import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap

/** Caps concurrent downloads: one semaphore per chat plus one global. */
class DownloadAdmission(globalLimit: Int, private val perChatLimit: Int) {
    private val global = Semaphore(globalLimit)

    // Grows unbounded but is safe: the bot only serves the fixed chat allowlist (TELEGRAM_ALLOW_LIST).
    private val perChat = ConcurrentHashMap<Long, Semaphore>()

    private fun chatSemaphore(chatId: Long): Semaphore =
        perChat.computeIfAbsent(chatId) { Semaphore(perChatLimit) }

    fun hasFreeSlot(chatId: Long): Boolean =
        global.availablePermits > 0 && chatSemaphore(chatId).availablePermits > 0

    /**
     * Runs [block] holding one per-chat and one global slot; suspends (cancellably) until both are free.
     * [onWait] is invoked at most once, before suspending, if either slot is not immediately available.
     */
    suspend fun <T> withSlot(chatId: Long, onWait: suspend () -> Unit = {}, block: suspend () -> T): T {
        val chat = chatSemaphore(chatId)
        var notified = false
        if (!chat.tryAcquire()) {
            onWait()
            notified = true
            chat.acquire()
        }
        try {
            if (!global.tryAcquire()) {
                if (!notified) onWait()
                global.acquire()
            }
            try {
                return block()
            } finally {
                global.release()
            }
        } finally {
            chat.release()
        }
    }
}
