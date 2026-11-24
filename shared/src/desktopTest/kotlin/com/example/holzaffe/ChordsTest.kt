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

    @Test
    fun gateSessionDeckKeyModesWorkAsExpected() {
        // 1. Default SINGLE_KEY_TONALITY: one key AND only Dur or only Moll per app opening.
        for (mode in listOf(GameMode.DEGREES, GameMode.CADENCES, GameMode.CHORD_TYPES)) {
            val deck = GateSessionDeck(mode = mode, keyMode = GateKeyMode.SINGLE_KEY_TONALITY, rnd = Random(99 + mode.ordinal))
            val expectedPc = assertNotNull(deck.sessionKey, "SINGLE_KEY_TONALITY must pick a sessionKey").mod(12)
            val expectedMinor = assertNotNull(deck.sessionMinor, "SINGLE_KEY_TONALITY must pick Dur or Moll")
            repeat(15) { step ->
                val q = deck.nextQuestion()
                val firstChordPcs = q.chords.first().map { it.mod(12) }.toSet()
                assertTrue(
                    expectedPc in firstChordPcs,
                    "mode=$mode step=$step tonic pitch class $expectedPc missing from ${q.chords.first()}",
                )
                when (val ex = QuestionFactory.exerciseById(q.progressionId)) {
                    is Progression -> assertEquals(expectedMinor, ex.minor, "must stay strictly Dur or Moll")
                    is Cadence -> assertEquals(expectedMinor, ex.minor, "must stay strictly Dur or Moll")
                    else -> Unit
                }
            }
        }

        // Across multiple app openings, both key and Dur/Moll must vary.
        val sessionKeys = (1..20).mapNotNull { seed ->
            GateSessionDeck(GameMode.DEGREES, keyMode = GateKeyMode.SINGLE_KEY_TONALITY, rnd = Random(seed)).sessionKey
        }.toSet()
        val sessionGenders = (1..20).mapNotNull { seed ->
            GateSessionDeck(GameMode.DEGREES, keyMode = GateKeyMode.SINGLE_KEY_TONALITY, rnd = Random(seed)).sessionMinor
        }.toSet()
        assertTrue(sessionKeys.size > 1, "session key should vary across separate app openings")
        assertEquals(setOf(false, true), sessionGenders, "Dur and Moll should both appear across app openings")

        // 2. SAME_ROOT: single root note, allows both Dur and Moll in the same session.
        val sameRootDeck = GateSessionDeck(GameMode.DEGREES, maxPoolSize = 18, keyMode = GateKeyMode.SAME_ROOT, rnd = Random(7))
        val rootPc = assertNotNull(sameRootDeck.sessionKey).mod(12)
        val gendersInSameRoot = (1..18).map {
            val q = sameRootDeck.nextQuestion()
            assertTrue(rootPc in q.chords.first().map { it.mod(12) })
            (QuestionFactory.exerciseById(q.progressionId) as Progression).minor
        }.toSet()
        assertEquals(setOf(false, true), gendersInSameRoot)

        // 3. RANDOM ("Wirrwarr"): no fixed session key.
        val randomDeck = GateSessionDeck(GameMode.DEGREES, keyMode = GateKeyMode.RANDOM, rnd = Random(11))
        assertEquals(null, randomDeck.sessionKey)
        assertEquals(null, randomDeck.sessionMinor)
    }

    @Test
    fun gateSessionDeckOnlyAsksSelectedExercises() {
        val wanted = setOf("S:I:IV", "S:I:V", "S:I:vi")
        val disabled = EXERCISES_BY_MODE.getValue(GameMode.DEGREES).map { it.id }.toSet() - wanted
        for (keyMode in GateKeyMode.entries) {
            repeat(20) { seed ->
                val deck = GateSessionDeck(GameMode.DEGREES, keyMode = keyMode, rnd = Random(seed), disabledIds = disabled)
                assertEquals(wanted, deck.pool.map { it.id }.toSet(), "keyMode=$keyMode seed=$seed")
                // Only Dur progressions are selected, so a single-tonality session must never pick Moll.
                if (keyMode == GateKeyMode.SINGLE_KEY_TONALITY) assertEquals(false, deck.sessionMinor)
                repeat(12) { assertTrue(deck.nextQuestion().progressionId in wanted) }
            }
        }

        // Only Moll cadences selected → single-tonality sessions stay in Moll.
        val minorOnly = MINOR_CADENCES.take(2).map { it.id }.toSet()
        val disabledCadences = (MAJOR_CADENCES + MINOR_CADENCES).map { it.id }.toSet() - minorOnly
        repeat(10) { seed ->
            val deck = GateSessionDeck(GameMode.CADENCES, rnd = Random(seed), disabledIds = disabledCadences)
            assertEquals(true, deck.sessionMinor)
            assertEquals(minorOnly, deck.pool.map { it.id }.toSet())
        }

        // Mix: exclusions apply across all modes; chord types stay available alongside Dur-only progressions.
        val mixDisabled = ALL_EXERCISES.map { it.id }.toSet() - wanted - "T:maj7"
        val mixDeck = GateSessionDeck(GameMode.MIX, maxPoolSize = 18, rnd = Random(3), disabledIds = mixDisabled)
        assertTrue(mixDeck.pool.map { it.id }.toSet().all { it in wanted + "T:maj7" })
        assertTrue("T:maj7" in mixDeck.pool.map { it.id })

        // Deselecting everything falls back to the full catalog instead of an empty gate.
        val allDisabled = ALL_EXERCISES.map { it.id }.toSet()
        val fallback = GateSessionDeck(GameMode.CHORD_TYPES, maxPoolSize = 18, rnd = Random(5), disabledIds = allDisabled)
        assertEquals(CHORD_TYPE_EXERCISES.size, fallback.pool.size)
    }

    @Test
    fun exerciseGroupsCoverEachModeExactlyOnce() {
        for (mode in GameMode.PLAYABLE) {
            val grouped = exerciseGroups(mode).flatMap { it.exercises }.map { it.id }
            assertEquals(EXERCISES_BY_MODE.getValue(mode).map { it.id }.toSet(), grouped.toSet(), "mode=$mode")
            assertEquals(grouped.size, grouped.toSet().size)
        }
        assertEquals(ALL_EXERCISES.size, exerciseGroups(GameMode.MIX).sumOf { it.exercises.size })
        assertEquals("I → ♭VII", QuestionFactory.exerciseById("S:I:♭VII")!!.displayLabel)
        assertEquals("i → VI → VII", QuestionFactory.exerciseById("K:i:VI-VII")!!.displayLabel)
    }

    @Test
    fun gateTaskCodecAndChallengePickerSwitchBetweenQuizAndCustomTasks() {
        val tasks = listOf(
            GateTask(id = "t1", text = "Akkorde übers Griffbrett, C–G–D!", minutes = 1),
            GateTask(id = "t2", text = "Widder", minutes = 2, kind = GateTaskKind.APP, targetPackage = "de.privat.widder"),
            GateTask(id = "t3", text = "Widder Widget", minutes = 3, kind = GateTaskKind.WIDGET, targetPackage = "de.privat.widder", widgetId = 42),
        )
        val encoded = GateTaskCodec.encode(tasks)
        assertEquals(tasks, GateTaskCodec.decode(encoded))

        // Backwards compatibility with legacy 3-field v1.4.0 entries.
        val legacyDecoded = GateTaskCodec.decode("old1\u001FAlte Aufgabe\u001F2")
        assertEquals(listOf(GateTask(id = "old1", text = "Alte Aufgabe", minutes = 2, kind = GateTaskKind.TEXT)), legacyDecoded)

        // Default: only Quiz active -> always returns Quiz.
        repeat(10) {
            val c = GateChallengePicker.pickNext(includeQuiz = true, activeTasks = emptyList(), lastId = GateChallenge.QUIZ_ID)
            assertEquals(GateChallenge.Quiz, c)
        }

        // Quiz + custom tasks -> switches without repeating the exact same entry back-to-back.
        val rnd = Random(17)
        var lastId: String? = null
        val seenIds = mutableSetOf<String>()
        repeat(40) {
            val picked = GateChallengePicker.pickNext(includeQuiz = true, activeTasks = tasks, lastId = lastId, rnd = rnd)
            assertNotEquals(lastId, picked.id, "must switch to a different active challenge on consecutive openings")
            seenIds += picked.id
            lastId = picked.id
        }
        assertEquals(setOf(GateChallenge.QUIZ_ID, "t1", "t2", "t3"), seenIds)
    }

    @Test
    fun appSearchMatchesOnlyVisibleLabelsAndRanksPrefixesFirst() {
        data class Entry(val label: String, val pkg: String, val secondary: String = "")

        val entries = listOf(
            Entry("WhatsApp", "com.whatsapp"),
            Entry("Widder", "de.privat.widder"),
            Entry("AnkiDroid", "com.ichi2.anki"),
            Entry("Eröffnungspuzzle", "de.privat.eroeffnungspuzzle", "Lern-Widget"),
            Entry("WoodRooster", "de.leidenschaftlicheslernen.woodrooster", "Eröffnungs-Widget"),
            Entry("Schachtrainer", "de.leidenschaftlicheslernen.schachtrainer"),
            Entry("YouTube", "com.google.android.youtube"),
            Entry("YT Music", "com.google.android.apps.youtube.music"),
            Entry("BandLab", "com.bandlab.bandlab"),
        )

        // Blank query keeps original order unchanged.
        assertEquals(entries, AppSearch.filterAndRank(entries, "   ", labelOf = { it.label }))

        // Searching "an" must NOT match "com.google.android.*" or "WhatsApp" via package names.
        val anMatches = AppSearch.filterAndRank(entries, "an", labelOf = { it.label }).map { it.label }
        assertEquals(listOf("AnkiDroid", "BandLab"), anMatches)

        // Searching "id" matches visible labels containing "id" (ranked by earlier index), and "privat" must NOT match package names.
        val idMatches = AppSearch.filterAndRank(entries, "id", labelOf = { it.label }).map { it.label }
        assertEquals(listOf("Widder", "AnkiDroid"), idMatches)
        assertEquals(emptyList(), AppSearch.filterAndRank(entries, "privat", labelOf = { it.label }))

        // Umlaut folding works in both directions ("eröffnung" and "eroeffnung").
        assertEquals(
            listOf("Eröffnungspuzzle"),
            AppSearch.filterAndRank(entries, "eröffnung", labelOf = { it.label }).map { it.label },
        )
        assertEquals(
            listOf("Eröffnungspuzzle"),
            AppSearch.filterAndRank(entries, "eroeffnung", labelOf = { it.label }).map { it.label },
        )

        // Exact > primary prefix > word prefix > secondary prefix > substring order.
        val eroeffnungWithSecondary = AppSearch.filterAndRank(
            entries,
            "eröffnungs",
            labelOf = { it.label },
            secondaryOf = { it.secondary },
        ).map { it.label }
        assertEquals(listOf("Eröffnungspuzzle", "WoodRooster"), eroeffnungWithSecondary)

        val musicMatches = AppSearch.filterAndRank(
            listOf(
                Entry("Apple Music", "a"),
                Entry("YT Music", "b"),
                Entry("Music", "c"),
                Entry("MusicLab", "d"),
                Entry("MyMusicPlayer", "e"),
            ),
            "music",
            labelOf = { it.label },
        ).map { it.label }
        assertEquals(listOf("Music", "MusicLab", "YT Music", "Apple Music", "MyMusicPlayer"), musicMatches)
    }
}
