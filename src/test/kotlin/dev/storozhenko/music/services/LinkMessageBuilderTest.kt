package dev.storozhenko.music.services

import dev.storozhenko.music.OdesilEntityData
import dev.storozhenko.music.OdesilPlatformData
import dev.storozhenko.music.OdesilResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkMessageBuilderTest {
    private val builder = LinkMessageBuilder()

    private val response = OdesilResponse(
        entityUniqueId = "X",
        linksByPlatform = mapOf(
            "spotify" to OdesilPlatformData("https://sp"),
            "youtube" to OdesilPlatformData("https://yt"),
        ),
        entitiesByUniqueId = mapOf("X" to OdesilEntityData("Song", "Artist")),
    )

    @Test
    fun `formats platforms in fixed order with artist and title`() {
        assertEquals(
            "Artist - Song\n<a href=\"https://yt\">YouTube</a> | <a href=\"https://sp\">Spotify</a>",
            builder.mapOdesilResponse(response),
        )
    }

    @Test
    fun `extracts first url by link text case-insensitively`() {
        val html = "Artist - Song\n<a href=\"https://yt\">YouTube</a> | <a href=\"https://sp\">Spotify</a>"
        assertEquals("https://yt", builder.extractFirstUrlByText(html, "Youtube"))
        assertNull(builder.extractFirstUrlByText(html, "Deezer"))
    }

    @Test
    fun `formats title with mm-ss duration`() {
        assertEquals("T [02:05]", builder.formatTitleWithDuration(VideoMeta("T", 125)))
        assertEquals("T", builder.formatTitleWithDuration(VideoMeta("T", null)))
    }

    @Test
    fun `strips bracket and paren annotations`() {
        assertEquals("Song", builder.stripAnnotations("Song (Official Video) [HD]"))
        assertEquals("A B", builder.stripAnnotations("A  (x)  B"))
    }

    @Test
    fun `recognizes known odesli music hosts`() {
        assertTrue(builder.isKnownOdesliMusicUrl("https://open.spotify.com/track/1"))
        assertTrue(builder.isKnownOdesliMusicUrl("https://music.apple.com/us/album/1"))
        assertFalse(builder.isKnownOdesliMusicUrl("https://example.com/track/1"))
        assertFalse(builder.isKnownOdesliMusicUrl("not a url"))
    }

    @Test
    fun `separates youtube music from plain youtube`() {
        // music.youtube.com is the only known music host that is also directly downloadable, so it
        // gets auto-detection while plain YouTube stays on the untouched video path.
        assertTrue(builder.isKnownOdesliMusicUrl("https://music.youtube.com/watch?v=1"))
        assertFalse(builder.isKnownOdesliMusicUrl("https://www.youtube.com/watch?v=1"))
        assertFalse(builder.isKnownOdesliMusicUrl("https://youtu.be/1"))
    }

    private val spotify = "https://open.spotify.com/track/1"
    private val ytMusic = "https://music.youtube.com/watch?v=1"
    private val youtube = "https://www.youtube.com/watch?v=1"

    @Test
    fun `undownloadable music host is audio-only`() {
        val c = builder.classifyMusicSource(listOf(spotify), downloadableUrls = emptyList())
        assertTrue(c.musicOnly)
        assertFalse(c.detectable)
    }

    @Test
    fun `youtube music is detectable rather than audio-only`() {
        // music.youtube.com is downloadable, so it is never musicOnly — the probe decides.
        val c = builder.classifyMusicSource(listOf(ytMusic), downloadableUrls = listOf(ytMusic))
        assertFalse(c.musicOnly)
        assertTrue(c.detectable)
    }

    @Test
    fun `plain youtube is neither`() {
        val c = builder.classifyMusicSource(listOf(youtube), downloadableUrls = listOf(youtube))
        assertFalse(c.musicOnly)
        assertFalse(c.detectable)
    }

    @Test
    fun `a downloadable link in the message suppresses the audio-only default`() {
        // Mixed message: the YouTube link is what we actually download, so it also decides delivery.
        val c = builder.classifyMusicSource(listOf(youtube, spotify), downloadableUrls = listOf(youtube))
        assertFalse(c.musicOnly)
        assertFalse(c.detectable)
    }

    @Test
    fun `a music host anywhere in a message with no downloadable link is audio-only`() {
        val c = builder.classifyMusicSource(listOf("https://example.com/post", spotify), downloadableUrls = emptyList())
        assertTrue(c.musicOnly)
    }

    @Test
    fun `detectability follows the first downloadable url, matching download precedence`() {
        val c = builder.classifyMusicSource(listOf(youtube, ytMusic), downloadableUrls = listOf(youtube, ytMusic))
        assertFalse(c.detectable)
        val flipped = builder.classifyMusicSource(listOf(ytMusic, youtube), downloadableUrls = listOf(ytMusic, youtube))
        assertTrue(flipped.detectable)
    }

    @Test
    fun `no urls is neither`() {
        val c = builder.classifyMusicSource(emptyList(), emptyList())
        assertFalse(c.musicOnly)
        assertFalse(c.detectable)
    }

    @Test
    fun `recognizes vk and rutube hosts`() {
        assertTrue(builder.isVkOrRutube("https://vk.com/video-1_2"))
        assertTrue(builder.isVkOrRutube("https://rutube.ru/video/x/"))
        assertTrue(builder.isVkOrRutube("https://vkvideo.ru/video-1_2"))
        assertFalse(builder.isVkOrRutube("https://youtube.com/watch?v=1"))
    }
}
