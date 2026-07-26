package dev.storozhenko.music.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers [MediaProbeService.decideSendAsVideo], which decides whether a downloaded music link is
 * an art track (static cover art -> send audio) or a real music video. It had no coverage while it
 * only ran in "music" chats; it now also runs for every music.youtube.com link.
 *
 * Fixtures are generated with ffmpeg rather than committed as binaries. Both are 20s, which takes
 * the `durationSec <= 30` single-window branch, so a run costs well under a second.
 */
class MediaProbeServiceTest {

    private val probe = MediaProbeService(Dispatchers.IO)

    /** Runs a tool and returns exit code + combined output, so fixture failures are not silent. */
    private fun run(vararg command: String): Pair<Int, String> {
        val p = ProcessBuilder(command.toList()).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    private fun ffmpeg(vararg args: String) {
        val (code, out) = run("ffmpeg", "-y", "-v", "error", *args)
        check(code == 0) { "fixture generation failed (exit $code):\n$out" }
    }

    /** Production shells out to both binaries, and libx264 is needed for the fixtures. */
    private fun toolsAvailable(): Boolean = runCatching {
        val (ffmpegCode, encoders) = run("ffmpeg", "-hide_banner", "-encoders")
        val (ffprobeCode, _) = run("ffprobe", "-version")
        ffmpegCode == 0 && ffprobeCode == 0 && encoders.contains("libx264")
    }.getOrDefault(false)

    /** Solid colour: no cuts, and every frame identical — an art track. */
    private fun staticClip(dir: File) = File(dir, "static.mp4").also {
        ffmpeg(
            "-f", "lavfi", "-i", "color=c=steelblue:s=160x120:r=10:d=20",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-preset", "ultrafast", it.absolutePath,
        )
    }

    /**
     * Luma flips between 16 and 235 once a second, i.e. 19 hard cuts. Gradual motion is not enough:
     * `select='gt(scene,0.05)'` scores cuts, and lavfi sources like testsrc2 and life score zero.
     */
    private fun cuttingClip(dir: File) = File(dir, "cuts.mp4").also {
        ffmpeg(
            "-f", "lavfi", "-i", "color=c=black:s=160x120:r=10:d=20",
            "-vf", "geq=lum='if(lt(mod(floor(T),2),1),16,235)':cb=128:cr=128",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-preset", "ultrafast", it.absolutePath,
        )
    }

    @Test
    fun `static cover art is sent as audio`(@TempDir dir: File) = runTest {
        assumeTrue(toolsAvailable(), "ffmpeg/ffprobe with libx264 not on PATH")
        assertFalse(probe.decideSendAsVideo(staticClip(dir), 20))
    }

    @Test
    fun `a clip with real cuts is sent as video`(@TempDir dir: File) = runTest {
        assumeTrue(toolsAvailable(), "ffmpeg/ffprobe with libx264 not on PATH")
        assertTrue(probe.decideSendAsVideo(cuttingClip(dir), 20))
    }

    @Test
    fun `scene counting separates cuts from a still image`(@TempDir dir: File) = runTest {
        assumeTrue(toolsAvailable(), "ffmpeg/ffprobe with libx264 not on PATH")
        assertEquals(0, probe.getSceneChangeCount(staticClip(dir), 0.0, 20.0))
        assertTrue(probe.getSceneChangeCount(cuttingClip(dir), 0.0, 20.0) >= 2)
    }

    /**
     * A fully frozen window emits `freeze_start` with no matching `freeze_end`/`freeze_duration`,
     * so the whole window only counts if the open-freeze branch closes it against the clip end.
     * Without that, a static art track reads as 0s frozen and is misclassified as video.
     */
    @Test
    fun `an unterminated freeze still counts to the end of the window`(@TempDir dir: File) = runTest {
        assumeTrue(toolsAvailable(), "ffmpeg/ffprobe with libx264 not on PATH")
        val frozen = probe.getTotalFreezeDuration(staticClip(dir), 0.0, 20.0)
        assertTrue(frozen >= 19.0, "expected a near-fully frozen window, got ${frozen}s")
    }

    @Test
    fun `reads dimensions and duration`(@TempDir dir: File) = runTest {
        assumeTrue(toolsAvailable(), "ffmpeg/ffprobe with libx264 not on PATH")
        val dims = probe.getVideoDimensions(staticClip(dir))
        assertEquals(VideoDimensions(width = 160, height = 120, duration = 20), dims)
    }
}
