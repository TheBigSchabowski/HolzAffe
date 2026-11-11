package com.example.holzaffe

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChordsTest {
    @Test
    fun questionsAreWellFormed() {
        val rnd = Random(7)
        repeat(500) {
            val q = QuestionFactory.next(rnd)
            assertEquals(2, q.chords.size)
            assertEquals(3, q.choices.toSet().size, "choices must differ")
            assertTrue(q.correct in 0..2)
            for (chord in q.chords) {
                assertEquals(3, chord.size)
                assertEquals(chord.sorted(), chord, "close position, ascending")
                assertTrue(chord.last() - chord.first() < 12)
                assertTrue(chord.all { it in 36..84 }, "playable range: $chord")
            }
            // Both basses stay within a tritone, so inversions do not leap.
            assertTrue(kotlin.math.abs(q.chords[0][0] - q.chords[1][0]) <= 6)
        }
    }

    @Test
    fun majorAndMinorShareNoLabelWithinAQuestion() {
        val rnd = Random(1)
        repeat(200) {
            val q = QuestionFactory.next(rnd)
            val tonics = q.choices.map { it.substringBefore(" ") }.toSet()
            assertEquals(1, tonics.size, "all choices start on the same tonic")
        }
    }

    @Test
    fun synthRendersAudibleAudioWithoutClipping() {
        val pcm = Synth.render(QuestionFactory.next(Random(3)).chords)
        assertEquals((1.65 * Synth.RATE).toInt(), pcm.size)
        val peak = pcm.maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue(peak in 20000..Short.MAX_VALUE, "peak $peak")
    }
}
