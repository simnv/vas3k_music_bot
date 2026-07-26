package dev.storozhenko.music.run

import dev.storozhenko.music.Quality
import dev.storozhenko.music.services.DownloadService
import dev.storozhenko.music.services.MediaProbeService
import dev.storozhenko.music.services.MediaProcessingService
import dev.storozhenko.music.services.TelegramSender
import dev.storozhenko.music.services.VideoDimensions
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// NOTE: kotlinx-coroutines' cancellation stack-trace recovery calls into kotlin-stdlib's
// DebugMetadata parser, which throws "Debug metadata version mismatch" on this project's
// Kotlin 2.3.21 / kotlinx-coroutines 1.10.2 combo. That throw happens inside coroutines'
// internal dispatch machinery on *any* cancellation path, corrupting the job's completion
// signal and hanging runTest forever (see the cancellation test below). Disabling it via
// System.setProperty inside this class is NOT reliable (coroutines caches the flag on first
// touch, which can happen in an earlier test class), so it's set JVM-wide via surefire's
// argLine (-Dkotlinx.coroutines.stacktrace.recovery=false in pom.xml) instead.
class DownloadPipelineTest {
    private val downloader = mockk<DownloadService>(relaxed = true)
    private val probe = mockk<MediaProbeService>(relaxed = true)
    private val processor = mockk<MediaProcessingService>(relaxed = true)
    private val sender = mockk<TelegramSender>(relaxed = true)
    private val pulser = mockk<TelegramSender.ChatActionPulser>(relaxed = true)
    private val pipeline = DownloadPipeline(downloader, probe, processor, sender, chunkSizeMB = 50)

    private fun audioRequest() = DownloadRequest(
        url = "https://youtu.be/x",
        message = "Artist - Title\nhttps://youtu.be/x",
        statusMessageId = 42,
        replyToMessageId = 7,
        chatId = 5L,
        quality = Quality.HIGH,
        forceAudio = true,
        isMusicChat = false,
    )

    @Test
    fun `audio path downloads and sends in place, then cleans up`(@TempDir dir: File) = runTest {
        val audio = File(dir, "song.m4a").apply { writeText("audio-bytes") }
        coEvery { downloader.download(any(), any(), *anyVararg()) } returns audio
        every { downloader.resolveSiblingThumbnail(any()) } returns null

        val ok = pipeline.run(audioRequest(), pulser, null)

        assertTrue(ok)
        coVerify {
            sender.sendAudioInPlace(eq(audio), eq(5L), eq(42), any(), eq("Artist"), eq("Title"), any(), isNull())
        }
        assertFalse(audio.exists()) // eagerlyDelete in finally
    }

    @Test
    fun `failed download edits a failure status and returns false`() = runTest {
        coEvery { downloader.download(any(), any(), *anyVararg()) } returns null
        val ok = pipeline.run(audioRequest(), pulser, null)
        assertFalse(ok)
        coVerify { sender.editMessageText(eq(5L), eq(42), match { it.contains("❌") }, any()) }
    }

    @Test
    fun `matching prefetch is reused without a second download`(@TempDir dir: File) = runTest {
        val audio = File(dir, "song.m4a").apply { writeText("audio-bytes") }
        val req = audioRequest().copy(
            prefetchedUrl = "https://youtu.be/x",
            prefetchedDownload = CompletableDeferred<File?>(audio),
        )
        every { downloader.resolveSiblingThumbnail(any()) } returns null

        assertTrue(pipeline.run(req, pulser, null))
        coVerify(exactly = 0) { downloader.download(any(), any(), *anyVararg()) }
    }

    @Test
    fun `cancellation propagates and is not reported as failure`() = runTest {
        coEvery { downloader.download(any(), any(), *anyVararg()) } coAnswers { awaitCancellation() }
        val job = launch { pipeline.run(audioRequest(), pulser, null) }
        testScheduler.runCurrent()
        job.cancelAndJoin()
        coVerify(exactly = 0) { sender.editMessageText(any(), any(), match { it.contains("❌") }, any()) }
    }

    @Test
    fun `video path probes dimensions and sends chunks`(@TempDir dir: File) = runTest {
        val video = File(dir, "clip.mp4").apply { writeText("video-bytes") } // tiny — below chunkSizeMB, no splitting
        coEvery { downloader.download(any(), any(), *anyVararg()) } returns video
        every { downloader.resolveSiblingThumbnail(any()) } returns null
        coEvery { probe.getVideoDimensions(any()) } returns VideoDimensions(width = 640, height = 360, duration = 12)
        coEvery {
            sender.sendVideoChunks(any(), any(), any(), any(), any(), isNull(), any(), isNull())
        } returns true

        val ok = pipeline.run(audioRequest().copy(forceAudio = false), pulser, null)

        assertTrue(ok)
        coVerify(exactly = 1) {
            sender.sendVideoChunks(
                eq(5L), eq(42), eq(7),
                match { it.size == 1 && it.single().first == video && it.single().second == VideoDimensions(640, 360, 12) },
                any(), isNull(), any(), isNull(),
            )
        }
        assertFalse(video.exists()) // eagerlyDelete in finally
    }

    @Test
    fun `music source runs detection outside a music chat and sends audio when static`(@TempDir dir: File) = runTest {
        val video = File(dir, "clip.mp4").apply { writeText("video-bytes") }
        val extracted = File(dir, "clip.m4a").apply { writeText("audio-bytes") }
        coEvery { downloader.download(any(), any(), *anyVararg()) } returns video
        every { downloader.resolveSiblingThumbnail(any()) } returns null
        coEvery { probe.getVideoDimensions(any()) } returns VideoDimensions(width = 720, height = 720, duration = 200)
        coEvery { probe.decideSendAsVideo(any(), any()) } returns false // art track
        coEvery { processor.convertToTelegramAudio(any()) } returns extracted

        val req = audioRequest().copy(forceAudio = false, isMusicChat = false, isMusicSource = true)
        assertTrue(pipeline.run(req, pulser, null))

        coVerify(exactly = 1) { probe.decideSendAsVideo(eq(video), eq(200)) }
        coVerify(exactly = 1) { sender.sendAudioInPlace(eq(extracted), eq(5L), eq(42), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { sender.sendVideoChunks(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `music source sends video when detection finds real motion`(@TempDir dir: File) = runTest {
        val video = File(dir, "clip.mp4").apply { writeText("video-bytes") }
        coEvery { downloader.download(any(), any(), *anyVararg()) } returns video
        every { downloader.resolveSiblingThumbnail(any()) } returns null
        coEvery { probe.getVideoDimensions(any()) } returns VideoDimensions(width = 640, height = 360, duration = 200)
        coEvery { probe.decideSendAsVideo(any(), any()) } returns true // real music video
        coEvery { sender.sendVideoChunks(any(), any(), any(), any(), any(), isNull(), any(), isNull()) } returns true

        val req = audioRequest().copy(forceAudio = false, isMusicChat = false, isMusicSource = true)
        assertTrue(pipeline.run(req, pulser, null))

        coVerify(exactly = 1) { probe.decideSendAsVideo(eq(video), eq(200)) }
        coVerify(exactly = 1) { sender.sendVideoChunks(any(), any(), any(), any(), any(), isNull(), any(), isNull()) }
    }

    @Test
    fun `explicit video suppresses detection even for a music source in a music chat`(@TempDir dir: File) = runTest {
        val video = File(dir, "clip.mp4").apply { writeText("video-bytes") }
        coEvery { downloader.download(any(), any(), *anyVararg()) } returns video
        every { downloader.resolveSiblingThumbnail(any()) } returns null
        coEvery { probe.getVideoDimensions(any()) } returns VideoDimensions(width = 720, height = 720, duration = 200)
        coEvery { sender.sendVideoChunks(any(), any(), any(), any(), any(), isNull(), any(), isNull()) } returns true

        val req = audioRequest().copy(
            forceAudio = false, isMusicChat = true, isMusicSource = true, forceVideo = true,
        )
        assertTrue(pipeline.run(req, pulser, null))

        coVerify(exactly = 0) { probe.decideSendAsVideo(any(), any()) }
        coVerify(exactly = 1) { sender.sendVideoChunks(any(), any(), any(), any(), any(), isNull(), any(), isNull()) }
    }

    @Test
    fun `plain video source never triggers detection`(@TempDir dir: File) = runTest {
        val video = File(dir, "clip.mp4").apply { writeText("video-bytes") }
        coEvery { downloader.download(any(), any(), *anyVararg()) } returns video
        every { downloader.resolveSiblingThumbnail(any()) } returns null
        coEvery { probe.getVideoDimensions(any()) } returns VideoDimensions(width = 640, height = 360, duration = 200)
        coEvery { sender.sendVideoChunks(any(), any(), any(), any(), any(), isNull(), any(), isNull()) } returns true

        assertTrue(pipeline.run(audioRequest().copy(forceAudio = false), pulser, null))

        coVerify(exactly = 0) { probe.decideSendAsVideo(any(), any()) }
    }

    @Test
    fun `video path cancellation propagates without failure edit`() = runTest {
        coEvery { downloader.download(any(), any(), *anyVararg()) } coAnswers { awaitCancellation() }
        val job = launch { pipeline.run(audioRequest().copy(forceAudio = false), pulser, null) }
        testScheduler.runCurrent()
        job.cancelAndJoin()
        coVerify(exactly = 0) { sender.editMessageText(any(), any(), match { it.contains("❌") }, any()) }
    }
}
