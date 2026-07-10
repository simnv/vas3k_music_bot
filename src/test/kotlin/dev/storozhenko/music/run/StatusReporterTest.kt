package dev.storozhenko.music.run

import dev.storozhenko.music.services.TelegramSender
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import kotlin.test.Test

class StatusReporterTest {
    private val sender = mockk<TelegramSender>(relaxed = true)
    private val kb = InlineKeyboardMarkup.builder().build()

    @Test
    fun `status appends suffix and keeps the cancel keyboard`() = runTest {
        StatusReporter(sender, 5L, 42, "base", kb).status("Downloading...")
        coVerify { sender.editMessageText(eq(5L), eq(42), eq("base\nDownloading..."), eq(kb)) }
    }

    @Test
    fun `fail appends cross mark and strips the keyboard`() = runTest {
        StatusReporter(sender, 5L, 42, "base", kb).fail("boom")
        coVerify {
            sender.editMessageText(eq(5L), eq(42), eq("base\n❌ boom"), match { it.keyboard.isEmpty() })
        }
    }
}
