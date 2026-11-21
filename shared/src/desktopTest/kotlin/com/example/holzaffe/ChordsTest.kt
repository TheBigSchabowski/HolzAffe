package com.example.holzaffe

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChordsTest {
    @Test
    fun questionsAreWellFormedAcrossAllModes() {
        val rnd = Random(7)
        for (mode in GameMode.entries) {
            repeat(200) {
                val q = QuestionFactory.next(mode, rnd)
                assertEquals(QuestionFactory.CHOICE_COUNT, q.choices.size)
                assertEquals(QuestionFactory.CHOICE_COUNT, q.choices.toSet().size, "choices must differ in $mode")
                assertTrue(q.correct in 0 until QuestionFactory.CHOICE_COUNT)
                assertTrue(q.prompt.isNotBlank())

                val exercise = QuestionFactory.exerciseById(q.progressionId)
                assertNotNull(exercise, "unknown exercise ${q.progressionId}")
                assertEquals(exercise.mode, q.mode)
                if (mode != GameMode.MIX) assertEquals(mode, q.mode)

                val expectedChords = when (q.mode) {
                    GameMode.DEGREES -> 2
                    GameMode.CHORD_TYPES -> 1
                    GameMode.CADENCES -> 3
                    GameMode.INVERSIONS -> 1
                    GameMode.MIX -> error("concrete question cannot have MIX mode")
                }
                assertEquals(expectedChords, q.chords.size, "unexpected chord count for ${q.mode}")
                assertEquals(q.chords.size, q.chordInfos.size, "chordInfos must match chords in ${q.mode}")
                assertTrue(q.chordSummary.isNotBlank())

                for ((chord, info) in q.chords.zip(q.chordInfos)) {
                    assertEquals(chord.sorted(), chord, "ascending voicing: $chord")
                    assertEquals(chord.distinct().size, chord.size, "distinct pitches: $chord")
                    assertTrue(chord.all { it in 36..84 }, "playable range in ${q.mode}: $chord")
                    assertTrue(info.name.isNotBlank())
                    assertTrue(info.degree.isNotBlank())
                    assertEquals(chord.size, info.notes.split("–").size, "notes label should match chord size")
                }

                when (q.mode) {
                    GameMode.DEGREES -> {
                        q.chords.forEach { assertEquals(3, it.size); assertTrue(it.last() - it.first() < 12) }
                        assertTrue(abs(q.chords[0][0] - q.chords[1][0]) <= 6)
                    }
                    GameMode.CADENCES -> {
                        q.chords.forEach { assertEquals(3, it.size); assertTrue(it.last() - it.first() < 12) }
                        assertTrue(abs(q.chords[0][0] - q.chords[1][0]) <= 6)
                        assertTrue(abs(q.chords[1][0] - q.chords[2][0]) <= 9)
                    }
                    GameMode.INVERSIONS -> {
                        assertEquals(3, q.chords[0].size)
                        assertTrue(q.chords[0].last() - q.chords[0].first() < 12)
                        assertTrue(q.chords[0][0] in 48..59)
                    }
                    GameMode.CHORD_TYPES -> {
                        val bass = q.chords[0][0]
                        assertTrue(bass in 48..59)
                        val pcs = q.chords[0].map { (it - bass) % 12 }.toSet()
                        assertEquals((exercise as ChordTypeExercise).type.intervals.toSet(), pcs)
                    }
                    GameMode.MIX -> error("unreachable")
                }
            }
        }
    }

    @Test
    fun allTargetsAndQualitiesAndInversionsAreDistinct() {
        assertEquals(QuestionFactory.CHOICE_COUNT, MAJOR_TARGETS.size)
        assertEquals(QuestionFactory.CHOICE_COUNT, MINOR_TARGETS.size)
        assertEquals(QuestionFactory.CHOICE_COUNT, CHORD_TYPES.size)
        assertEquals(QuestionFactory.CHOICE_COUNT, MAJOR_CADENCES.size)
        assertEquals(QuestionFactory.CHOICE_COUNT, MINOR_CADENCES.size)
        assertEquals(QuestionFactory.CHOICE_COUNT, INVERSION_EXERCISES.size)

        fun pitchClasses(root: Int, intervals: List<Int>): Set<Int> =
            intervals.map { (root + it) % 12 }.toSet()

        val majorSet = (listOf(Degree("I", 0, listOf(0, 4, 7))) + MAJOR_TARGETS)
            .map { pitchClasses(it.root, it.intervals) }
            .toSet()
        assertEquals(10, majorSet.size)

        val minorSet = (listOf(Degree("i", 0, listOf(0, 3, 7))) + MINOR_TARGETS)
            .map { pitchClasses(it.root, it.intervals) }
            .toSet()
        assertEquals(10, minorSet.size)

        assertEquals(9, CHORD_TYPES.map { it.intervals.toSet() }.toSet().size)

        val invVectors = INVERSION_EXERCISES.map { ex ->
            val rotated = ex.intervals.drop(ex.inversion) + ex.intervals.take(ex.inversion).map { it + 12 }
            rotated.map { it - rotated.first() }
        }.toSet()
        assertEquals(9, invVectors.size)

        assertEquals(9, MAJOR_CADENCES.map { it.second.label to it.third.label }.toSet().size)
        assertEquals(9, MINOR_CADENCES.map { it.second.label to it.third.label }.toSet().size)
        assertTrue(MAJOR_CADENCES.all { it.second != it.third })
        assertTrue(MINOR_CADENCES.all { it.second != it.third })
    }

    @Test
    fun chordInfosMatchConcreteGuitarChordNames() {
        val gI = degreeChordInfo(Degree("I", 0, listOf(0, 4, 7)), key = 55, minor = false, midiNotes = listOf(55, 59, 62))
        assertEquals(ChordInfo("G-Dur", "I", "G–H–D"), gI)

        val gFlatVII = degreeChordInfo(MAJOR_TARGETS.first { it.label == "♭VII" }, key = 55, minor = false, midiNotes = listOf(53, 57, 60))
        assertEquals(ChordInfo("F-Dur", "♭VII", "F–A–C"), gFlatVII)

        val gFlatVI = degreeChordInfo(MAJOR_TARGETS.first { it.label == "♭VI" }, key = 55, minor = false, midiNotes = listOf(51, 55, 58))
        assertEquals(ChordInfo("E♭-Dur", "♭VI", "E♭–G–B♭"), gFlatVI)

        val gViiDim = degreeChordInfo(MAJOR_TARGETS.first { it.label == "vii°" }, key = 55, minor = false, midiNotes = listOf(54, 57, 60))
        assertEquals(ChordInfo("F♯-dim", "vii°", "F♯–A–C"), gViiDim)

        val eVi = degreeChordInfo(MAJOR_TARGETS.first { it.label == "vi" }, key = 52, minor = false, midiNotes = listOf(49, 52, 56))
        assertEquals(ChordInfo("C♯-Moll", "vi", "C♯–E–G♯"), eVi)

        val dMinor = degreeChordInfo(Degree("i", 0, listOf(0, 3, 7)), key = 50, minor = true, midiNotes = listOf(50, 53, 57))
        assertEquals(ChordInfo("D-Moll", "i", "D–F–A"), dMinor)

        val dMinorVI = degreeChordInfo(MINOR_TARGETS.first { it.label == "VI" }, key = 50, minor = true, midiNotes = listOf(58, 62, 65))
        assertEquals(ChordInfo("B♭-Dur", "VI", "B♭–D–F"), dMinorVI)

        val dMinorVii = degreeChordInfo(MINOR_TARGETS.first { it.label == "vii°" }, key = 50, minor = true, midiNotes = listOf(49, 52, 55))
        assertEquals(ChordInfo("C♯-dim", "vii°", "C♯–E–G"), dMinorVii)

        assertEquals(
            ChordInfo("Cmaj7", "maj7", "C–E–G–H"),
            chordTypeInfo(CHORD_TYPES.first { it.id == "maj7" }, key = 48, midiNotes = listOf(48, 52, 55, 59)),
        )
        assertEquals(
            ChordInfo("G7", "7", "G–H–D–F"),
            chordTypeInfo(CHORD_TYPES.first { it.id == "7" }, key = 55, midiNotes = listOf(55, 59, 62, 65)),
        )
        assertEquals(
            ChordInfo("Am7", "m7", "A–C–E–G"),
            chordTypeInfo(CHORD_TYPES.first { it.id == "m7" }, key = 57, midiNotes = listOf(57, 60, 64, 67)),
        )
        assertEquals(
            ChordInfo("Dsus4", "sus4", "D–G–A"),
            chordTypeInfo(CHORD_TYPES.first { it.id == "sus4" }, key = 50, midiNotes = listOf(50, 55, 57)),
        )
        assertEquals(
            ChordInfo("C-aug", "aug", "C–E–G♯"),
            chordTypeInfo(CHORD_TYPES.first { it.id == "aug" }, key = 48, midiNotes = listOf(48, 52, 56)),
        )

        assertEquals(
            ChordInfo("C-Dur", "Dur · Grund", "C–E–G"),
            inversionChordInfo(INVERSION_EXERCISES.first { it.quality == "Dur" && it.inversion == 0 }, bass = 48, midiNotes = listOf(48, 52, 55)),
        )
        assertEquals(
            ChordInfo("C-Dur/E", "Dur · 1. Umk", "E–G–C"),
            inversionChordInfo(INVERSION_EXERCISES.first { it.quality == "Dur" && it.inversion == 1 }, bass = 52, midiNotes = listOf(52, 55, 60)),
        )
        assertEquals(
            ChordInfo("C-Dur/G", "Dur · 2. Umk", "G–C–E"),
            inversionChordInfo(INVERSION_EXERCISES.first { it.quality == "Dur" && it.inversion == 2 }, bass = 55, midiNotes = listOf(55, 60, 64)),
        )
    }

    @Test
    fun majorAndMinorShareNoLabelWithinAQuestion() {
        val rnd = Random(1)
        for (mode in listOf(GameMode.DEGREES, GameMode.CADENCES)) {
            repeat(200) {
                val q = QuestionFactory.next(mode, rnd)
                val tonics = q.choices.map { it.substringBefore(" ") }.toSet()
                assertEquals(1, tonics.size, "all choices in $mode must share the same tonic")
            }
        }
    }

    @Test
    fun synthRendersBlockAndArpeggiatedAudioWithoutClipping() {
        val twoChord = QuestionFactory.next(GameMode.DEGREES, Random(3)).chords
        val pcm2 = Synth.render(twoChord)
        assertEquals((1.65 * Synth.RATE).toInt(), pcm2.size)
        assertTrue(pcm2.maxOf { abs(it.toInt()) } in 20000..Short.MAX_VALUE)

        val threeChord = QuestionFactory.next(GameMode.CADENCES, Random(4)).chords
        val pcm3 = Synth.render(threeChord)
        assertEquals((2.2 * Synth.RATE).toInt(), pcm3.size)
        assertTrue(pcm3.maxOf { abs(it.toInt()) } in 20000..Short.MAX_VALUE)

        val fourNoteChord = QuestionFactory.forExercise(CHORD_TYPE_EXERCISES.first { it.type.id == "maj7" }, Random(5)).chords
        for (chords in listOf(fourNoteChord, twoChord, threeChord)) {
            val arp = Synth.renderArpeggiated(chords)
            assertTrue(arp.isNotEmpty())
            val peak = arp.maxOf { abs(it.toInt()) }
            assertTrue(peak in 20000..Short.MAX_VALUE, "arp peak $peak")
        }
    }

    @Test
    fun missedExerciseRepeatsAfterGapAndGraduatesAfterTwoCorrect() {
        val queue = ReviewQueue()
        val rnd = Random(42)

        val q1 = queue.nextQuestion(GameMode.DEGREES, rnd)
        queue.record(q1.progressionId, correct = false, mode = GameMode.DEGREES)
        assertEquals(1, queue.pendingCount(GameMode.DEGREES))

        repeat(ReviewQueue.RETRY_GAP) {
            val q = queue.nextQuestion(GameMode.DEGREES, rnd)
            assertNotEquals(q1.progressionId, q.progressionId, "should wait before first retry")
            queue.record(q.progressionId, correct = true, mode = GameMode.DEGREES)
        }

        val retry = queue.nextQuestion(GameMode.DEGREES, rnd)
        assertEquals(q1.progressionId, retry.progressionId, "missed exercise must return after RETRY_GAP")
        queue.record(retry.progressionId, correct = true, mode = GameMode.DEGREES)
        assertEquals(1, queue.pendingCount(GameMode.DEGREES))

        repeat(ReviewQueue.CONFIRM_GAP) {
            val q = queue.nextQuestion(GameMode.DEGREES, rnd)
            assertNotEquals(q1.progressionId, q.progressionId, "should wait before confirmation review")
            queue.record(q.progressionId, correct = true, mode = GameMode.DEGREES)
        }

        val confirm = queue.nextQuestion(GameMode.DEGREES, rnd)
        assertEquals(q1.progressionId, confirm.progressionId, "should return once more after CONFIRM_GAP")
        queue.record(confirm.progressionId, correct = true, mode = GameMode.DEGREES)
        assertEquals(0, queue.pendingCount(GameMode.DEGREES))
    }

    @Test
    fun repeatedMissResetsBackToShortGap() {
        val queue = ReviewQueue()
        val rnd = Random(9)

        val q1 = queue.nextQuestion(GameMode.CHORD_TYPES, rnd)
        queue.record(q1.progressionId, correct = false, mode = GameMode.CHORD_TYPES)

        repeat(ReviewQueue.RETRY_GAP) {
            val q = queue.nextQuestion(GameMode.CHORD_TYPES, rnd)
            assertNotEquals(q1.progressionId, q.progressionId)
            queue.record(q.progressionId, correct = true, mode = GameMode.CHORD_TYPES)
        }

        val retry = queue.nextQuestion(GameMode.CHORD_TYPES, rnd)
        assertEquals(q1.progressionId, retry.progressionId)
        queue.record(retry.progressionId, correct = false, mode = GameMode.CHORD_TYPES)

        assertEquals(
            listOf(ReviewQueue.Entry(q1.progressionId, stage = 0, due = ReviewQueue.RETRY_GAP)),
            queue.snapshot(),
        )

        repeat(ReviewQueue.RETRY_GAP) {
            val q = queue.nextQuestion(GameMode.CHORD_TYPES, rnd)
            assertNotEquals(q1.progressionId, q.progressionId)
            queue.record(q.progressionId, correct = true, mode = GameMode.CHORD_TYPES)
        }

        val secondRetry = queue.nextQuestion(GameMode.CHORD_TYPES, rnd)
        assertEquals(q1.progressionId, secondRetry.progressionId)
    }

    @Test
    fun reviewQueueIsolatesModesAndCombinesInMix() {
        val queue = ReviewQueue()
        val rnd = Random(17)

        val missedDegree = queue.nextQuestion(GameMode.DEGREES, rnd)
        queue.record(missedDegree.progressionId, correct = false, mode = GameMode.DEGREES)

        // Playing another mode must neither surface nor decrement the DEGREES review.
        repeat(6) {
            val typeQ = queue.nextQuestion(GameMode.CHORD_TYPES, rnd)
            assertEquals(GameMode.CHORD_TYPES, typeQ.mode)
            queue.record(typeQ.progressionId, correct = true, mode = GameMode.CHORD_TYPES)
        }
        assertEquals(
            listOf(ReviewQueue.Entry(missedDegree.progressionId, stage = 0, due = ReviewQueue.RETRY_GAP)),
            queue.snapshot(),
        )

        // Returning to DEGREES (or MIX) preserves the full gap and then surfaces the missed item.
        repeat(ReviewQueue.RETRY_GAP) {
            val q = queue.nextQuestion(GameMode.DEGREES, rnd)
            assertNotEquals(missedDegree.progressionId, q.progressionId)
            queue.record(q.progressionId, correct = true, mode = GameMode.DEGREES)
        }
        val returnedInMix = queue.nextQuestion(GameMode.MIX, rnd)
        assertEquals(missedDegree.progressionId, returnedInMix.progressionId)
    }

    @Test
    fun reviewQueueRoundTripsThroughSerialization() {
        val queue = ReviewQueue()
        queue.record("S:I:V", correct = false, mode = GameMode.DEGREES)
        queue.record("T:sus4", correct = false, mode = GameMode.MIX)
        queue.record("K:I:IV-V", correct = false, mode = GameMode.MIX)
        queue.record("U:dim:2", correct = true, mode = GameMode.MIX)
        queue.record("S:I:V", correct = true, mode = GameMode.MIX) // advances S:I:V to stage 1

        val encoded = queue.encode()
        val restored = ReviewQueue.decode(encoded)
        assertEquals(queue.snapshot(), restored.snapshot())
        assertEquals(encoded, restored.encode())

        val sanitized = ReviewQueue.decode(
            " ,bogus|0|1,S:I:V|bad|2,S:I:V|0|bad,S:I:V|0|1,S:I:V|1|4,T:maj7|5|-3,K:i:iv-V|0|2,U:Moll:1|1|0,missingfields"
        )
        assertEquals(
            listOf(
                ReviewQueue.Entry("S:I:V", stage = 0, due = 1),
                ReviewQueue.Entry("T:maj7", stage = 1, due = 0),
                ReviewQueue.Entry("K:i:iv-V", stage = 0, due = 2),
                ReviewQueue.Entry("U:Moll:1", stage = 1, due = 0),
            ),
            sanitized.snapshot(),
        )
    }

    @Test
    fun reviewQueueNeverRepeatsBackToBackEvenWhenAllQueued() {
        val queue = ReviewQueue()
        val rnd = Random(123)
        var prevId: String? = null
        for (ex in ALL_EXERCISES) {
            queue.record(ex.id, correct = false, mode = GameMode.MIX)
            prevId = ex.id
        }
        assertEquals(ALL_EXERCISES.size, queue.pendingCount)

        repeat(80) { step ->
            val q = queue.nextQuestion(GameMode.MIX, rnd)
            assertNotEquals(prevId, q.progressionId, "step $step repeated $prevId back-to-back")
            queue.record(q.progressionId, correct = step % 4 != 0, mode = GameMode.MIX)
            prevId = q.progressionId
        }
    }

    @Test
    fun gateSessionDeckCapsPoolAtSevenAndVariesOrderWithoutBackToBackRepeats() {
        for (mode in GameMode.entries) {
            val deck = GateSessionDeck(mode = mode, rnd = Random(42 + mode.ordinal))
            assertTrue(deck.pool.size in 1..GateSessionDeck.DEFAULT_POOL_SIZE, "mode=$mode pool=${deck.pool.size}")
            val poolIds = deck.pool.map { it.id }.toSet()
            assertEquals(deck.pool.size, poolIds.size, "mode=$mode pool must have distinct exercises")

            val drawnIds = mutableListOf<String>()
            var prevId: String? = null
            repeat(35) { step ->
                val q = deck.nextQuestion()
                assertTrue(q.progressionId in poolIds, "mode=$mode step=$step drew outside 7-exercise pool")
                assertNotEquals(prevId, q.progressionId, "mode=$mode step=$step repeated $prevId back-to-back")
                drawnIds += q.progressionId
                prevId = q.progressionId
            }

            // Consecutive full cycles of 7 must not stay in the exact same order.
            val cycle1 = drawnIds.subList(0, deck.pool.size)
            val cycle2 = drawnIds.subList(deck.pool.size, deck.pool.size * 2)
            assertEquals(poolIds, cycle1.toSet())
            assertEquals(poolIds, cycle2.toSet())
            assertNotEquals(cycle1, cycle2, "mode=$mode cycles must reshuffle into a different order")
        }

        // Separate app launches (new decks) should pick different 7-exercise subsets.
        val deckA = GateSessionDeck(GameMode.DEGREES, rnd = Random(1)).pool.map { it.id }.toSet()
        val deckB = GateSessionDeck(GameMode.DEGREES, rnd = Random(2)).pool.map { it.id }.toSet()
        assertNotEquals(deckA, deckB, "separate app-start sessions should pick different 7-exercise pools")
    }
}
