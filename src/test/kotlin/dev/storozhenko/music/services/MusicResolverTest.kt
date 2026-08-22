package dev.storozhenko.music.services

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fixtures are trimmed copies of real responses captured on 2026-08-22, so the parsers are pinned
 * against the shapes actually served rather than assumed ones.
 */
class MusicResolverTest {

    private val web = mockk<WebFetcher>()
    private val resolver = MusicResolver(web, ytSearch = { null })

    private val itunesJson = """
        {"resultCount":1,"results":[{"wrapperType":"track","artistName":"BEARWOLF",
        "trackName":"Владивосток",
        "trackViewUrl":"https://music.apple.com/us/album/x/6793443883?i=6793443884&uo=4"}]}
    """.trimIndent()

    private val yandexHtml = """
        <html><head>
        <meta property="og:title" content="Владивосток"/>
        <meta property="og:description" content="BEARWOLF • Трек • 2026"/>
        </head><body><a href="/album/43183857/track/153933899">x</a></body></html>
    """.trimIndent()

    @Test
    fun `parses itunes identity and url`() {
        assertEquals(TrackIdentity("BEARWOLF", "Владивосток"), resolver.parseItunes(itunesJson))
        assertEquals(
            "https://music.apple.com/us/album/x/6793443883?i=6793443884&uo=4",
            resolver.parseItunesUrl(itunesJson),
        )
    }

    @Test
    fun `empty itunes results yield null`() {
        assertNull(resolver.parseItunes("""{"resultCount":0,"results":[]}"""))
        assertNull(resolver.parseItunesUrl("""{"resultCount":0,"results":[]}"""))
        assertNull(resolver.parseItunes("not json"))
    }

    @Test
    fun `parses artist from the yandex og description`() {
        // og:description is "Artist • Трек • Year"; the artist is the first bullet field.
        assertEquals(TrackIdentity("BEARWOLF", "Владивосток"), resolver.parseOg(yandexHtml))
    }

    @Test
    fun `og parsing tolerates reversed attribute order and missing description`() {
        val reversed = """<meta content="Song" property="og:title">"""
        assertEquals(TrackIdentity("", "Song"), resolver.parseOg(reversed))
        assertNull(resolver.parseOg("<html>no meta</html>"))
    }

    @Test
    fun `finds the first yandex track path`() {
        assertEquals("https://music.yandex.ru/album/43183857/track/153933899", resolver.firstYandexTrack(yandexHtml))
        assertNull(resolver.firstYandexTrack("<html>nothing</html>"))
    }

    @Test
    fun `extracts apple and spotify ids`() {
        assertEquals("6793443884", resolver.appleTrackId("https://music.apple.com/us/album/x/679?i=6793443884&uo=4"))
        assertNull(resolver.appleTrackId("https://music.apple.com/us/album/x/679"))
        assertEquals(
            "4cOdK2wGLETKBW3PvgPWqT",
            resolver.spotifyTrackId("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT?si=abc"),
        )
        assertNull(resolver.spotifyTrackId("https://open.spotify.com/album/123"))
    }

    @Test
    fun `identity query joins artist and title, skipping blanks`() {
        assertEquals("BEARWOLF Владивосток", TrackIdentity("BEARWOLF", "Владивосток").query)
        assertEquals("Song", TrackIdentity("", "Song").query)
    }

    @Test
    fun `resolves a yandex link and echoes the source instead of re-searching it`() = runTest {
        val source = "https://music.yandex.ru/album/43183857/track/153933899"
        coEvery { web.get(source, any()) } returns yandexHtml
        coEvery { web.get(match { it.startsWith("https://itunes.apple.com/search") }, any()) } returns itunesJson
        val r = MusicResolver(web, ytSearch = { "https://youtu.be/abc" }).resolve(source)!!

        assertEquals(TrackIdentity("BEARWOLF", "Владивосток"), r.identity)
        // The posted Yandex URL is reused verbatim — no search page fetched for its own platform.
        assertEquals(source, r.links["Yandex.Music"])
        assertEquals("https://youtu.be/abc", r.links["YouTube"])
        assertEquals("https://youtu.be/abc", r.youtubeUrl)
        assertTrue(r.links.containsKey("Apple Music"))
    }

    @Test
    fun `returns null when the track cannot be identified`() = runTest {
        coEvery { web.get(any(), any()) } returns null
        assertNull(resolver.resolve("https://music.yandex.ru/album/1/track/2"))
    }

    @Test
    fun `spotify is omitted when no client is configured`() = runTest {
        val source = "https://music.yandex.ru/album/1/track/2"
        coEvery { web.get(source, any()) } returns yandexHtml
        coEvery { web.get(match { it.startsWith("https://itunes.apple.com") }, any()) } returns null
        val r = MusicResolver(web, ytSearch = { "https://youtu.be/abc" }, spotify = null).resolve(source)!!
        assertTrue(!r.links.containsKey("Spotify"))
    }

    @Test
    fun `a failing youtube search does not sink the whole resolution`() = runTest {
        val source = "https://music.yandex.ru/album/1/track/2"
        coEvery { web.get(source, any()) } returns yandexHtml
        coEvery { web.get(match { it.startsWith("https://itunes.apple.com") }, any()) } returns itunesJson
        val r = MusicResolver(web, ytSearch = { throw RuntimeException("yt-dlp exploded") }).resolve(source)!!
        assertNull(r.youtubeUrl)
        assertTrue(r.links.containsKey("Apple Music"))
    }
}
