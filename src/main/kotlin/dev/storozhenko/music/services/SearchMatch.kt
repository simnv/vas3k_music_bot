package dev.storozhenko.music.services

/**
 * Checks a search hit against the release we searched for.
 *
 * Every service answers a search with something. When it does not carry the release, its top hit
 * is another record, often by the same artist: Yandex answered Polyphia's "WITH EYES TO SEE" with
 * "Fuck Around and Find Out". No link is better than a wrong one.
 *
 * Titles match when one contains the other as whole words, after decorations are removed. That
 * accepts "Song (feat. X)", "Song - Remastered 2011" and a YouTube "Song (Official Video)".
 */
internal object SearchMatch {

    fun matches(wanted: TrackIdentity, title: String, artists: List<String>): Boolean {
        if (!overlaps(normalize(wanted.title), normalize(title))) return false
        val artist = normalize(wanted.artist)
        // Nothing to compare: an identity without an artist, or a hit whose artists we did not read.
        if (artist.isEmpty() || artists.isEmpty()) return true
        return artists.any { overlaps(artist, normalize(it)) }
    }

    /** True when one side contains the other as whole words. */
    private fun overlaps(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val x = " $a "
        val y = " $b "
        return x.contains(y) || y.contains(x)
    }

    internal fun normalize(s: String): String {
        val lower = s.lowercase()
        val undecorated = lower
            .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
            .replace(Regex("\\s(feat|ft|featuring)\\.?\\s.*$"), " ")
        // A title made only of brackets would normalize to nothing; keep its words instead.
        return words(undecorated).ifEmpty { words(lower) }
    }

    /** "T.N.T." and "TNT" are the same word; "AC/DC" and "AC-DC" are the same two. */
    private fun words(s: String): String =
        s.replace(Regex("[.'’]"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
}
