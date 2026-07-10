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
    fun `recognizes vk and rutube hosts`() {
        assertTrue(builder.isVkOrRutube("https://vk.com/video-1_2"))
        assertTrue(builder.isVkOrRutube("https://rutube.ru/video/x/"))
        assertTrue(builder.isVkOrRutube("https://vkvideo.ru/video-1_2"))
        assertFalse(builder.isVkOrRutube("https://youtube.com/watch?v=1"))
    }
}
