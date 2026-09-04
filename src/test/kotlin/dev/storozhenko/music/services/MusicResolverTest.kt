package dev.storozhenko.music.services

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    private val yandexTrackJson = """
        {"result":{"id":"153933899","title":"Владивосток",
        "artists":[{"name":"BEARWOLF"}],"albums":[{"id":43183857}]}}
    """.trimIndent()

    private val yandexSearchJson = """
        {"result":{"tracks":{"results":[
        {"id":153933899,"title":"Владивосток","albums":[{"id":43183857}]}]}}}
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
    fun `parses identity from the yandex track api`() {
        assertEquals(TrackIdentity("BEARWOLF", "Владивосток"), resolver.parseYandexTrack(yandexTrackJson))
        assertNull(resolver.parseYandexTrack("""{"result":{}}"""))
        assertNull(resolver.parseYandexTrack("nonsense"))
    }

    @Test
    fun `parses the array-shaped track response the api actually returns`() {
        // /tracks/{id} wraps the track in an array; the object form only shows up elsewhere.
        val arrayShaped = """
            {"result":[{"id":"153933899","title":"Владивосток","artists":[{"name":"BEARWOLF"}]}]}
        """.trimIndent()
        assertEquals(TrackIdentity("BEARWOLF", "Владивосток"), resolver.parseYandexTrack(arrayShaped))
        assertNull(resolver.parseYandexTrack("""{"result":[]}"""))
    }

    @Test
    fun `apple search prefers the configured storefront`() = runTest {
        val source = "https://music.yandex.ru/album/43183857/track/153933899"
        val storefrontJson = itunesJson.replace("/us/", "/gb/")
        coEvery { web.get(match { it.contains("api.music.yandex.net/tracks") }, any(), any()) } returns yandexTrackJson
        coEvery { web.get(match { it.contains("country=gb") }, any(), any()) } returns storefrontJson

        val r = MusicResolver(web, ytSearch = { null }).resolve(source)!!
        assertTrue(r.links["Apple Music"]!!.contains("/gb/"), "expected a gb storefront link")
    }

    @Test
    fun `apple search falls back when the storefront lacks the track`() = runTest {
        val source = "https://music.yandex.ru/album/43183857/track/153933899"
        coEvery { web.get(match { it.contains("api.music.yandex.net/tracks") }, any(), any()) } returns yandexTrackJson
        // Storefront miss, then the default catalogue answers.
        coEvery { web.get(match { it.contains("country=gb") }, any(), any()) } returns
            """{"resultCount":0,"results":[]}"""
        coEvery {
            web.get(match { it.startsWith("https://itunes.apple.com/search") && !it.contains("country=") }, any(), any())
        } returns itunesJson

        val r = MusicResolver(web, ytSearch = { null }).resolve(source)!!
        assertTrue(r.links["Apple Music"]!!.contains("/us/"))
    }

    @Test
    fun `resolveFor looks up a source we can already name`() = runTest {
        // A YouTube link in a music chat: yt-dlp gave us the title, so there is nothing to identify.
        val source = "https://youtu.be/G0qpZMFNthk"
        coEvery { web.get(match { it.contains("country=gb") }, any(), any()) } returns itunesJson
        coEvery { web.get(match { it.contains("api.music.yandex.net/search") }, any(), any()) } returns yandexSearchJson

        val r = MusicResolver(web, ytSearch = { "https://youtu.be/other" })
            .resolveFor(TrackIdentity("BEARWOLF", "Владивосток"), source)!!

        // The posted YouTube URL wins over the search hit for its own platform.
        assertEquals(source, r.links["YouTube"])
        assertEquals("https://music.yandex.ru/album/43183857/track/153933899", r.links["Yandex.Music"])
        assertTrue(r.links.containsKey("Apple Music"))
    }

    @Test
    fun `resolveFor gives up on a blank identity`() = runTest {
        assertNull(resolver.resolveFor(TrackIdentity("", ""), "https://youtu.be/x"))
    }

    @Test
    fun `distinguishes album urls from track urls`() {
        assertTrue(resolver.isAlbumUrl("https://music.yandex.ru/album/43183857"))
        assertFalse(resolver.isAlbumUrl("https://music.yandex.ru/album/43183857/track/153933899"))
        assertTrue(resolver.isAlbumUrl("https://music.apple.com/ru/album/name/6793443883"))
        assertFalse(resolver.isAlbumUrl("https://music.apple.com/ru/album/name/6793443883?i=6793443884"))
        assertTrue(resolver.isAlbumUrl("https://open.spotify.com/album/1DFixLWuPkv3KT3TnV35m3"))
        assertFalse(resolver.isAlbumUrl("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT"))
        // A playlist is not an album and must not be treated as one.
        assertFalse(resolver.isAlbumUrl("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"))
        assertFalse(resolver.isAlbumUrl("https://youtu.be/abc"))
    }

    @Test
    fun `parses album identity from yandex and itunes`() {
        assertEquals(
            TrackIdentity("BEARWOLF", "Владивосток"),
            resolver.parseYandexAlbum("""{"result":{"id":43183857,"title":"Владивосток","artists":[{"name":"BEARWOLF"}]}}"""),
        )
        assertEquals(
            TrackIdentity("BEARWOLF", "Владивосток - Single"),
            resolver.parseItunesAlbum("""{"results":[{"collectionName":"Владивосток - Single","artistName":"BEARWOLF"}]}"""),
        )
        assertNull(resolver.parseYandexAlbum("""{"result":{}}"""))
    }

    @Test
    fun `builds album urls from search results`() {
        assertEquals(
            "https://music.yandex.ru/album/43183857",
            resolver.parseYandexAlbumSearch("""{"result":{"albums":{"results":[{"id":43183857}]}}}"""),
        )
        assertNull(resolver.parseYandexAlbumSearch("""{"result":{"albums":{"results":[]}}}"""))
        assertEquals(
            "https://music.apple.com/ru/album/x/1",
            resolver.parseItunesAlbumUrl("""{"results":[{"collectionViewUrl":"https://music.apple.com/ru/album/x/1"}]}"""),
        )
    }

    @Test
    fun `resolves an album to links and never to a download`() = runTest {
        val source = "https://music.yandex.ru/album/43183857"
        coEvery { web.get(match { it.contains("api.music.yandex.net/albums") }, any(), any()) } returns
            """{"result":{"title":"Владивосток","artists":[{"name":"BEARWOLF"}]}}"""
        coEvery { web.get(match { it.contains("entity=album") && it.contains("country=gb") }, any(), any()) } returns
            """{"results":[{"collectionViewUrl":"https://music.apple.com/ru/album/x/1"}]}"""

        val r = MusicResolver(web, ytSearch = { "https://youtu.be/should-not-be-used" }).resolveAlbum(source)!!

        assertEquals(TrackIdentity("BEARWOLF", "Владивосток"), r.identity)
        assertEquals(source, r.links["Yandex.Music"])
        assertEquals("https://music.apple.com/ru/album/x/1", r.links["Apple Music"])
        // youtubeUrl is what would trigger a download, and an album must never start one.
        assertNull(r.youtubeUrl)
        assertFalse(r.links.containsKey("YouTube"))
    }

    @Test
    fun `resolveAlbum ignores track urls`() = runTest {
        assertNull(resolver.resolveAlbum("https://music.yandex.ru/album/1/track/2"))
    }

    @Test
    fun `an unidentifiable album still answers with the posted link`() = runTest {
        // Better one real link than an error: the link the user sent is still a good link.
        coEvery { web.get(any(), any(), any()) } returns null
        val source = "https://music.apple.com/dk/album/x/6766853324"
        val r = MusicResolver(web, ytSearch = { null }).resolveAlbum(source)!!
        assertEquals(mapOf("Apple Music" to source), r.links)
        assertEquals("", r.identity.title)
        assertNull(r.youtubeUrl)
    }

    @Test
    fun `a single service link is a good enough answer`() = runTest {
        val source = "https://open.spotify.com/album/1DFixLWuPkv3KT3TnV35m3"
        coEvery { web.get(any(), any(), any()) } returns null
        val r = MusicResolver(web, ytSearch = { null }).resolveAlbum(source)!!
        assertEquals(1, r.links.size)
        assertEquals(source, r.links["Spotify"])
    }

    @Test
    fun `recognises playlists, which are not albums`() {
        assertTrue(resolver.isPlaylistUrl("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"))
        assertTrue(resolver.isPlaylistUrl("https://music.yandex.ru/users/someone/playlists/1000"))
        assertTrue(resolver.isPlaylistUrl("https://music.apple.com/ru/playlist/x/pl.123"))
        assertFalse(resolver.isPlaylistUrl("https://music.yandex.ru/album/43183857"))
        assertFalse(resolver.isPlaylistUrl("https://open.spotify.com/album/1DFixLWuPkv3KT3TnV35m3"))
        assertFalse(resolver.isPlaylistUrl("https://youtu.be/abc"))
    }

    @Test
    fun `reads the storefront out of an apple url`() {
        assertEquals("dk", resolver.appleUrlStorefront("https://music.apple.com/dk/album/half-told-tales/6766853324"))
        assertEquals("ru", resolver.appleUrlStorefront("https://music.apple.com/ru/album/x/1?i=2"))
        assertNull(resolver.appleUrlStorefront("https://music.apple.com/album/x/1"))
        assertNull(resolver.appleUrlStorefront("https://open.spotify.com/album/1DFixLWuPkv3KT3TnV35m3"))
    }

    @Test
    fun `album lookup uses the storefront from the posted link`() = runTest {
        // /dk/ album absent from the default catalogue: without country=dk the lookup returns
        // resultCount 0 and the bot claimed it could not find an album named in the URL.
        val source = "https://music.apple.com/dk/album/half-told-tales/6766853324"
        coEvery { web.get(match { it.contains("lookup") && !it.contains("country=") }, any(), any()) } returns
            """{"resultCount":0,"results":[]}"""
        coEvery { web.get(match { it.contains("lookup") && it.contains("country=dk") }, any(), any()) } returns
            """{"resultCount":1,"results":[{"collectionName":"Half-Told Tales","artistName":"Arab Strap"}]}"""
        // The link is also checked for availability in our own storefront before being rewritten.
        coEvery { web.get(match { it.contains("lookup") && it.contains("country=gb") }, any(), any()) } returns
            """{"resultCount":1,"results":[{"collectionName":"Half-Told Tales"}]}"""

        val r = MusicResolver(web, ytSearch = { null }).resolveAlbum(source)!!
        assertEquals(TrackIdentity("Arab Strap", "Half-Told Tales"), r.identity)
    }

    @Test
    fun `album id must be a whole path segment`() {
        assertEquals("6766853324", resolver.appleAlbumId("https://music.apple.com/dk/album/half-told-tales/6766853324"))
        assertEquals("456", resolver.appleAlbumId("https://music.apple.com/gb/album/1999/456"))
        assertEquals("123", resolver.appleAlbumId("https://music.apple.com/gb/album/name/123?l=en"))
        assertEquals("99999", resolver.appleAlbumId("https://music.apple.com/gb/album/blink-182/99999?foo=1"))
        // An unanchored pattern backtracked into the slug here and returned "9".
        assertEquals("1999", resolver.appleAlbumId("https://music.apple.com/gb/album/1999"))
        assertNull(resolver.appleAlbumId("https://music.apple.com/gb/album/abc"))
    }

    @Test
    fun `rewrites the storefront segment`() {
        assertEquals(
            "https://music.apple.com/gb/album/x/1",
            resolver.withAppleStorefront("https://music.apple.com/dk/album/x/1", "gb"),
        )
        // Nothing to rewrite when there is no storefront segment.
        assertEquals(
            "https://music.apple.com/album/x/1",
            resolver.withAppleStorefront("https://music.apple.com/album/x/1", "gb"),
        )
    }

    @Test
    fun `a posted apple link is converted to our storefront`() = runTest {
        val source = "https://music.apple.com/dk/album/half-told-tales/6766853324"
        coEvery { web.get(match { it.contains("lookup") && it.contains("country=dk") }, any(), any()) } returns
            """{"resultCount":1,"results":[{"collectionName":"Half-Told Tales","artistName":"Arab Strap"}]}"""
        // Available in our storefront, so the link may be rewritten.
        coEvery { web.get(match { it.contains("lookup") && it.contains("country=gb") }, any(), any()) } returns
            """{"resultCount":1,"results":[{"collectionName":"Half-Told Tales"}]}"""

        val r = MusicResolver(web, ytSearch = { null }).resolveAlbum(source)!!
        assertEquals("https://music.apple.com/gb/album/half-told-tales/6766853324", r.links["Apple Music"])
    }

    @Test
    fun `the posted apple link is kept when our storefront lacks the release`() = runTest {
        val source = "https://music.apple.com/dk/album/half-told-tales/6766853324"
        coEvery { web.get(match { it.contains("lookup") && it.contains("country=dk") }, any(), any()) } returns
            """{"resultCount":1,"results":[{"collectionName":"Half-Told Tales","artistName":"Arab Strap"}]}"""
        coEvery { web.get(match { it.contains("lookup") && it.contains("country=gb") }, any(), any()) } returns
            """{"resultCount":0,"results":[]}"""

        val r = MusicResolver(web, ytSearch = { null }).resolveAlbum(source)!!
        // Pointing at a store that does not carry it would be worse than the original link.
        assertEquals(source, r.links["Apple Music"])
    }

    @Test
    fun `extracts the yandex track id`() {
        assertEquals("153933899", resolver.yandexTrackId("https://music.yandex.ru/album/43183857/track/153933899"))
        assertNull(resolver.yandexTrackId("https://music.yandex.ru/album/43183857"))
    }

    @Test
    fun `og parsing tolerates reversed attribute order and missing description`() {
        val reversed = """<meta content="Song" property="og:title">"""
        assertEquals(TrackIdentity("", "Song"), resolver.parseOg(reversed))
        assertNull(resolver.parseOg("<html>no meta</html>"))
    }

    @Test
    fun `builds the yandex web url from the search api`() {
        assertEquals("https://music.yandex.ru/album/43183857/track/153933899", resolver.parseYandexSearch(yandexSearchJson))
        assertNull(resolver.parseYandexSearch("""{"result":{"tracks":{"results":[]}}}"""))
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
        coEvery { web.get(match { it.contains("api.music.yandex.net/tracks") }, any(), any()) } returns yandexTrackJson
        coEvery { web.get(match { it.startsWith("https://itunes.apple.com/search") }, any(), any()) } returns itunesJson
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
        coEvery { web.get(any(), any(), any()) } returns null
        assertNull(resolver.resolve("https://music.yandex.ru/album/1/track/2"))
    }

    @Test
    fun `spotify is omitted when no client is configured`() = runTest {
        val source = "https://music.yandex.ru/album/1/track/2"
        coEvery { web.get(match { it.contains("api.music.yandex.net/tracks") }, any(), any()) } returns yandexTrackJson
        coEvery { web.get(match { it.startsWith("https://itunes.apple.com") }, any(), any()) } returns null
        val r = MusicResolver(web, ytSearch = { "https://youtu.be/abc" }, spotify = null).resolve(source)!!
        assertTrue(!r.links.containsKey("Spotify"))
    }

    @Test
    fun `og parsing survives apostrophes and decodes entities`() {
        // A regex capture of [^"']* truncates this at the apostrophe, yielding "Don".
        val html = """<meta property="og:title" content="Don't Stop">"""
        assertEquals("Don't Stop", resolver.parseOg(html)?.title)

        val entities = """<meta property="og:title" content="Simon &amp; Garfunkel &#39;66">"""
        assertEquals("Simon & Garfunkel '66", resolver.parseOg(entities)?.title)

        val singleQuoted = """<meta property='og:title' content='Song'>"""
        assertEquals("Song", resolver.parseOg(singleQuoted)?.title)

        val spaced = """<meta property = "og:title" content = "Spaced">"""
        assertEquals("Spaced", resolver.parseOg(spaced)?.title)
    }

    @Test
    fun `the bullet artist convention applies only to yandex`() {
        val html = """
            <meta property="og:title" content="T"/>
            <meta property="og:description" content="Artist • Трек • 2026"/>
        """.trimIndent()
        assertEquals("Artist", resolver.parseOg(html, bulletArtist = true)?.artist)
        // Other hosts do not use that format, so guessing an artist from it would be wrong.
        assertEquals("", resolver.parseOg(html, bulletArtist = false)?.artist)
    }

    @Test
    fun `id extraction requires proper boundaries`() {
        assertNull(resolver.appleTrackId("https://music.apple.com/x?i=123junk"))
        assertEquals("123", resolver.appleTrackId("https://music.apple.com/x?i=123"))
        assertEquals("123", resolver.appleTrackId("https://music.apple.com/x?i=123&uo=4"))
        assertNull(resolver.spotifyTrackId("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqTEXTRA"))
    }

    @Test
    fun `host matching requires a label boundary`() {
        assertTrue(resolver.hostMatches("music.apple.com", "apple.com"))
        assertTrue(resolver.hostMatches("apple.com", "apple.com"))
        assertFalse(resolver.hostMatches("notapple.com", "apple.com"))
    }

    @Test
    fun `a spotify source without credentials still resolves via oembed`() = runTest {
        val source = "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT"
        coEvery { web.get(match { it.startsWith("https://open.spotify.com/oembed") }, any(), any()) } returns
            """{"title":"Never Gonna Give You Up"}"""
        coEvery { web.get(match { it.startsWith("https://itunes.apple.com/search") }, any(), any()) } returns itunesJson
        coEvery { web.get(match { it.contains("api.music.yandex.net/search") }, any(), any()) } returns yandexSearchJson

        val r = MusicResolver(web, ytSearch = { "https://youtu.be/x" }, spotify = null).resolve(source)!!
        assertEquals("Never Gonna Give You Up", r.identity.title)
        // The posted Spotify link is still surfaced even though we could not query Spotify.
        assertEquals(source, r.links["Spotify"])
    }

    @Test
    fun `one failing lookup does not cancel the others`() = runTest {
        val source = "https://music.yandex.ru/album/1/track/2"
        coEvery { web.get(match { it.contains("api.music.yandex.net/tracks") }, any(), any()) } returns yandexTrackJson
        coEvery { web.get(match { it.startsWith("https://itunes.apple.com") }, any(), any()) } throws RuntimeException("apple down")
        coEvery { web.get(match { it.contains("api.music.yandex.net/search") }, any(), any()) } returns yandexSearchJson

        val r = MusicResolver(web, ytSearch = { "https://youtu.be/x" }).resolve(source)!!
        assertEquals("https://youtu.be/x", r.youtubeUrl)
        assertTrue(!r.links.containsKey("Apple Music"))
    }

    @Test
    fun `a failing youtube search does not sink the whole resolution`() = runTest {
        val source = "https://music.yandex.ru/album/1/track/2"
        coEvery { web.get(match { it.contains("api.music.yandex.net/tracks") }, any(), any()) } returns yandexTrackJson
        coEvery { web.get(match { it.startsWith("https://itunes.apple.com") }, any(), any()) } returns itunesJson
        val r = MusicResolver(web, ytSearch = { throw RuntimeException("yt-dlp exploded") }).resolve(source)!!
        assertNull(r.youtubeUrl)
        assertTrue(r.links.containsKey("Apple Music"))
    }
}
