package dev.storozhenko.music.services

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchMatchTest {

    private val polyphia = TrackIdentity("Polyphia", "WITH EYES TO SEE")

    @Test
    fun `another record by the same artist is rejected`() {
        // Yandex does not carry the track and answered with this one instead.
        assertFalse(SearchMatch.matches(polyphia, "Fuck Around and Find Out", listOf("Polyphia", "\$NOT")))
    }

    @Test
    fun `the same title by another artist is rejected`() {
        assertFalse(SearchMatch.matches(polyphia, "With Eyes to See", listOf("Someone Else")))
    }

    @Test
    fun `case, punctuation and decorations do not prevent a match`() {
        assertTrue(SearchMatch.matches(polyphia, "With Eyes to See", listOf("Polyphia")))
        assertTrue(SearchMatch.matches(polyphia, "WITH EYES TO SEE (feat. Someone)", listOf("Polyphia", "Someone")))
        assertTrue(SearchMatch.matches(polyphia, "With Eyes To See - Remastered 2024", listOf("Polyphia")))
        assertTrue(SearchMatch.matches(TrackIdentity("Polyphia", "With Eyes To See (Official Video)"), "WITH EYES TO SEE", listOf("Polyphia")))
        assertTrue(SearchMatch.matches(TrackIdentity("AC/DC", "T.N.T."), "TNT", listOf("AC-DC")))
    }

    @Test
    fun `a collaboration credited as one string matches its first artist`() {
        assertTrue(SearchMatch.matches(polyphia, "With Eyes to See", listOf("Polyphia & Someone")))
    }

    @Test
    fun `an identity with no artist is matched on the title alone`() {
        assertTrue(SearchMatch.matches(TrackIdentity("", "With Eyes to See"), "WITH EYES TO SEE", listOf("Polyphia")))
    }

    @Test
    fun `an unsplit youtube title still matches`() {
        // A video title without " - " has no artist part, so the artist sits inside the title.
        assertTrue(SearchMatch.matches(TrackIdentity("", "Polyphia With Eyes To See"), "With Eyes to See", listOf("Polyphia")))
    }

    @Test
    fun `a word fragment is not a match`() {
        assertFalse(SearchMatch.matches(TrackIdentity("Polyphia", "Seeing"), "See", listOf("Polyphia")))
    }

    @Test
    fun `diacritics and letter variants do not prevent a match`() {
        assertTrue(SearchMatch.matches(TrackIdentity("Beyoncé", "Café"), "Cafe", listOf("Beyonce")))
        assertTrue(SearchMatch.matches(TrackIdentity("Sigur Ros", "Hoppipolla"), "Hoppípolla", listOf("Sigur Rós")))
        assertTrue(SearchMatch.matches(TrackIdentity("Mø", "Final Song"), "Final Song", listOf("MO")))
        assertTrue(SearchMatch.matches(TrackIdentity("Die Ärzte", "Straße"), "Strasse", listOf("Die Arzte")))
        assertTrue(SearchMatch.matches(TrackIdentity("Łona", "Æther"), "Aether", listOf("Lona")))
        // Full-width forms, as some Japanese releases are titled.
        assertTrue(SearchMatch.matches(TrackIdentity("", "ＡＢＣ"), "ABC", emptyList()))
        assertTrue(SearchMatch.matches(TrackIdentity("Земфира", "Ёлка"), "Елка", listOf("Земфира")))
        assertTrue(SearchMatch.matches(TrackIdentity("Simon & Garfunkel", "Mrs. Robinson"), "Mrs Robinson", listOf("Simon and Garfunkel")))
    }

    @Test
    fun `a field written in another script is trusted`() {
        // "Земфира" and "Zemfira" cannot be compared as text, so the service's ranking decides.
        assertTrue(SearchMatch.matches(TrackIdentity("Земфира", "Хочешь"), "Hochesh", listOf("Zemfira")))
        assertTrue(SearchMatch.matches(TrackIdentity("Zemfira", "Hochesh"), "Хочешь?", listOf("Земфира")))
        assertTrue(SearchMatch.matches(TrackIdentity("Yoasobi", "Yoru ni Kakeru"), "夜に駆ける", listOf("YOASOBI")))
    }

    @Test
    fun `a field in the same script is still checked when the other is trusted`() {
        // The title cannot be compared, but the artist can, and it is wrong.
        assertFalse(SearchMatch.matches(TrackIdentity("Zemfira", "Хочешь"), "Hochesh", listOf("Polyphia")))
    }

    @Test
    fun `a shared script means a strict comparison`() {
        // Both sides carry Latin letters, so they are compared as usual.
        assertFalse(SearchMatch.matches(TrackIdentity("Zemfira", "Hochesh (Хочешь)"), "Iskala", listOf("Zemfira")))
    }

    @Test
    fun `cyrillic titles are compared`() {
        assertTrue(SearchMatch.matches(TrackIdentity("BEARWOLF", "Владивосток"), "Владивосток", listOf("BEARWOLF")))
        assertFalse(SearchMatch.matches(TrackIdentity("BEARWOLF", "Владивосток"), "Москва", listOf("BEARWOLF")))
    }
}
