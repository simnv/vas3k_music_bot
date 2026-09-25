package dev.storozhenko.music.services

import java.text.Normalizer

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
        if (!sameField(normalize(wanted.title), normalize(title))) return false
        val artist = normalize(wanted.artist)
        // Nothing to compare: an identity without an artist, or a hit whose artists we did not read.
        if (artist.isEmpty() || artists.isEmpty()) return true
        return artists.any { sameField(artist, normalize(it)) }
    }

    /**
     * "Земфира" and "Zemfira" cannot be compared as text. When two values share no script at all,
     * the service's own ranking is trusted instead: a wrong link is possible there, but a strict
     * check would drop every correct one.
     */
    private fun sameField(a: String, b: String): Boolean = overlaps(a, b) || differentScripts(a, b)

    private fun differentScripts(a: String, b: String): Boolean {
        val x = scripts(a)
        val y = scripts(b)
        return x.isNotEmpty() && y.isNotEmpty() && x.none { it in y }
    }

    private fun scripts(s: String): Set<Character.UnicodeScript> =
        s.codePoints().toArray()
            .filter { Character.isLetter(it) }
            .map { Character.UnicodeScript.of(it) }
            .filter { it != Character.UnicodeScript.COMMON && it != Character.UnicodeScript.INHERITED }
            .toSet()

    /** True when one side contains the other as whole words. */
    private fun overlaps(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val x = " $a "
        val y = " $b "
        return x.contains(y) || y.contains(x)
    }

    internal fun normalize(s: String): String {
        val lower = fold(s.lowercase())
        val undecorated = lower
            .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
            .replace(Regex("\\s(feat|ft|featuring)\\.?\\s.*$"), " ")
        // A title made only of brackets would normalize to nothing; keep its words instead.
        return words(undecorated).ifEmpty { words(lower) }
    }

    /**
     * Services disagree on diacritics: one writes "Sigur Rós", another "Sigur Ros". NFKD splits
     * "ó" into "o" and a combining mark, and also turns full-width "Ａ" into "A". Letters that do
     * not decompose are mapped by hand.
     */
    private fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .let { decomposed -> buildString { decomposed.forEach { append(LETTERS[it] ?: it) } } }
            .replace("&", " and ")

    private val LETTERS = mapOf(
        'ø' to "o", 'ß' to "ss", 'æ' to "ae", 'œ' to "oe", 'ł' to "l", 'đ' to "d", 'ð' to "d",
        'þ' to "th", 'ı' to "i", 'ħ' to "h",
    )

    /** "T.N.T." and "TNT" are the same word; "AC/DC" and "AC-DC" are the same two. */
    private fun words(s: String): String =
        s.replace(Regex("[.'’]"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
}
