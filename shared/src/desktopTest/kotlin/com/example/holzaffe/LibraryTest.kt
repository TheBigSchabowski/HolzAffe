package com.example.holzaffe

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private class MemoryScores : Scores {
    val ints = HashMap<String, Int>()
    val strings = HashMap<String, String>()
    override var highScore: Int
        get() = highScore(GameMode.DEGREES)
        set(v) = setHighScore(GameMode.DEGREES, v)
    override var streak = 0
    override var bestStreak = 0
    override var maxErrors = 0
    override var missDelaySec = DEFAULT_MISS_DELAY_SEC
    override var reviewQueue = ""
    override var gameMode = GameMode.DEGREES
    override fun highScore(mode: GameMode): Int = ints["legacy:${mode.key}"] ?: 0
    override fun setHighScore(mode: GameMode, value: Int) { ints["legacy:${mode.key}"] = value }
    override fun getInt(key: String, default: Int): Int = ints[key] ?: default
    override fun putInt(key: String, value: Int) { ints[key] = value }
    override fun getString(key: String, default: String): String = strings[key] ?: default
    override fun putString(key: String, value: String) { strings[key] = value }
}

class LibraryTest {
    private val audios = listOf(
        CustomAudio("a1", "Wonderwall Intro", "/x/a1.mp3"),
        CustomAudio("a2", "Let It Be · Strophe", "/x/a2.mp3"),
        CustomAudio("a3", "Mein → Riff", "/x/a3.m4a"),
    )

    @Test
    fun codecRoundTripsNamesWithAnyVisibleCharacter() {
        assertEquals(audios, LibraryCodec.decodeAudios(LibraryCodec.encodeAudios(audios)))
        val runs = listOf(
            CustomRun("r1", "Pop, Folgen | 1", listOf("S:I:V", "A:a1", "K:I:IV-V")),
            CustomRun("r2", "Nur Audio", listOf("A:a2")),
        )
        assertEquals(runs, LibraryCodec.decodeRuns(LibraryCodec.encodeRuns(runs)))
        assertEquals(emptyList(), LibraryCodec.decodeRuns(""))
    }

    @Test
    fun libraryResolvesItemsAndCleansUpDeletedAudios() {
        val lib = Library(MemoryScores())
        audios.forEach(lib::addAudio)
        lib.saveRun(CustomRun("r1", "Mix", listOf("S:I:V", "A:a1", "A:a2", "gibt-es-nicht")))
        val run = lib.runs.single()
        assertEquals(listOf("I → V", "Wonderwall Intro", "Let It Be · Strophe"), lib.items(run).map { it.label })

        lib.renameAudio("a1", "  Oasis  ")
        assertEquals("Oasis", lib.audios.first { it.id == "a1" }.name)

        assertEquals("/x/a1.mp3", lib.deleteAudio("a1")?.path)
        assertEquals(listOf("S:I:V", "A:a2", "gibt-es-nicht"), lib.runs.single().itemIds)

        // A run whose items are all gone falls back to the built-in mode.
        lib.saveRun(CustomRun("r2", "Leer", listOf("A:a1")))
        assertEquals(RunTarget.Builtin(GameMode.CADENCES), lib.target("r2", GameMode.CADENCES))
        assertTrue(lib.target("r1", GameMode.DEGREES) is RunTarget.Custom)
    }

    @Test
    fun customRunDeckAsksOnlyRunItemsWithoutBackToBackRepeats() {
        val items = listOf(
            RunItem.Builtin(QuestionFactory.exerciseById("S:I:IV")!!),
            RunItem.Builtin(QuestionFactory.exerciseById("T:maj7")!!),
            RunItem.Audio(audios[0]),
            RunItem.Audio(audios[1]),
        )
        val deck = CustomRunDeck(items, allAudios = audios, rnd = Random(4))
        val ids = items.map { it.id }.toSet()
        var prev: String? = null
        repeat(60) {
            val q = deck.nextQuestion()
            assertTrue(q.progressionId in ids)
            assertNotEquals(prev, q.progressionId)
            prev = q.progressionId
            if (q.isAudio) {
                // Correct name is among the choices, all choices are distinct recordings.
                assertEquals(audios.first { it.itemId == q.progressionId }.name, q.choices[q.correct])
                assertEquals(q.choices.size, q.choices.toSet().size)
                assertTrue(q.choices.size in 2..QuestionFactory.CHOICE_COUNT)
                assertTrue(q.chords.isEmpty())
            } else {
                assertEquals(QuestionFactory.CHOICE_COUNT, q.choices.size)
            }
            deck.record(correct = true)
        }
    }

    @Test
    fun missedItemComesBackSoon() {
        val items = ALL_PROGRESSIONS.take(8).map { RunItem.Builtin(it) }
        val deck = CustomRunDeck(items, rnd = Random(1))
        val missed = deck.nextQuestion().progressionId
        deck.record(correct = false)
        val nextIds = (1..3).map { deck.nextQuestion().progressionId.also { deck.record(true) } }
        assertTrue(missed in nextIds, "missed $missed should return within 3 questions, got $nextIds")
    }

    @Test
    fun audioChoicesUseOtherRecordingsAsDistractorsAndStayCapped() {
        val many = (1..14).map { CustomAudio("m$it", "Song $it", "/x/$it.wav") }
        val deck = CustomRunDeck(listOf(RunItem.Audio(many[0])), allAudios = many, rnd = Random(2))
        val q = deck.nextQuestion()
        assertEquals(QuestionFactory.CHOICE_COUNT, q.choices.size)
        assertEquals("Song 1", q.choices[q.correct])
        // Positions follow upload order so the same recording sits in the same place.
        assertEquals(q.choices.sortedBy { it.removePrefix("Song ").toInt() }, q.choices)
    }

    @Test
    fun gatePoolSizeLimitsCustomRun() {
        val items = ALL_EXERCISES.take(12).map { RunItem.Builtin(it) }
        val deck = CustomRunDeck(items, maxPoolSize = 4, fixedKey = 50, rnd = Random(9))
        assertEquals(4, deck.pool.size)
        val poolIds = deck.pool.map { it.id }.toSet()
        repeat(20) { assertTrue(deck.nextQuestion().progressionId in poolIds) }
    }

    @Test
    fun runLengthStepsAndBestScoresPerLength() {
        assertEquals(105, RunLength.step(90, up = true))
        assertEquals(75, RunLength.step(90, up = false))
        assertEquals(150, RunLength.step(120, up = true))
        assertEquals(105, RunLength.step(120, up = false))
        assertEquals(360, RunLength.step(300, up = true))
        assertEquals(RunLength.MAX, RunLength.step(RunLength.MAX, up = true))
        assertEquals(RunLength.MIN, RunLength.step(RunLength.MIN, up = false))
        assertEquals("1:30", RunLength.format(90))
        assertEquals("10:00", RunLength.format(600))

        val scores = MemoryScores()
        assertEquals(RUN_SECONDS, scores.runSeconds)
        val stufen = RunTarget.Builtin(GameMode.DEGREES)
        scores.setHighScore(GameMode.DEGREES, 17) // existing 1:30 record
        assertEquals(17, scores.bestScore(stufen, 90))
        assertEquals(0, scores.bestScore(stufen, 180))
        scores.setBestScore(stufen, 180, 30)
        assertEquals(30, scores.bestScore(stufen, 180))
        assertEquals(17, scores.bestScore(stufen, 90))

        val own = RunTarget.Custom(CustomRun("r", "Eigen", listOf("S:I:V")), listOf(RunItem.Builtin(ALL_PROGRESSIONS[4])))
        scores.setBestScore(own, 90, 9)
        assertEquals(9, scores.bestScore(own, 90))
        assertEquals(17, scores.bestScore(stufen, 90))
    }

    @Test
    fun reviewQueueIgnoresUploadedRecordings() {
        val review = ReviewQueue()
        review.record("A:a1", correct = false)
        assertEquals(0, review.pendingCount)
    }
}

class ChordLengthTest {
    @Test
    fun chordDurationStepsFormatsAndDrivesSynth() {
        assertEquals(600, ChordLength.step(550, up = true))
        assertEquals(500, ChordLength.step(550, up = false))
        assertEquals(1250, ChordLength.step(1000, up = true))
        assertEquals(950, ChordLength.step(1000, up = false))
        assertEquals(ChordLength.MIN, ChordLength.step(ChordLength.MIN, up = false))
        assertEquals(ChordLength.MAX, ChordLength.step(ChordLength.MAX, up = true))
        assertEquals("0,55 s", ChordLength.format(550))
        assertEquals("1,25 s", ChordLength.format(1250))

        val chords = listOf(listOf(48, 52, 55), listOf(55, 59, 62))
        try {
            Synth.chordMs = Synth.DEFAULT_CHORD_MS
            // Default keeps the classic timing: 0.55 s + 1.1 s.
            assertEquals((0.55 * Synth.RATE).toInt() + (1.1 * Synth.RATE).toInt(), Synth.render(chords).size)
            val defaultArp = Synth.renderArpeggiated(chords).size

            Synth.chordMs = 1000
            assertEquals(Synth.RATE + 2 * Synth.RATE, Synth.render(chords).size)
            assertTrue(Synth.renderArpeggiated(chords).size > defaultArp)
        } finally {
            Synth.chordMs = Synth.DEFAULT_CHORD_MS
        }
    }
}

class TempoAndSelectionTest {
    @Test
    fun chordTempoComputesMsAndMigratesLegacyDuration() {
        assertEquals(500, ChordTempo(120, ChordBeatLength.ONE).chordMs)
        assertEquals(1000, ChordTempo(120, ChordBeatLength.TWO).chordMs)
        assertEquals(1500, ChordTempo(120, ChordBeatLength.THREE).chordMs)
        assertEquals(2000, ChordTempo(120, ChordBeatLength.FOUR).chordMs)
        assertEquals(545, ChordTempo(110, ChordBeatLength.ONE).chordMs)

        assertEquals(ChordTempo.MAX_CHORD_MS, ChordTempo(40, ChordBeatLength.FOUR).chordMs)
        assertEquals(250, ChordTempo(240, ChordBeatLength.ONE).chordMs)

        assertEquals(115, ChordTempo.stepBpm(110, up = true))
        assertEquals(105, ChordTempo.stepBpm(110, up = false))
        assertEquals(ChordTempo.MIN_BPM, ChordTempo.stepBpm(ChordTempo.MIN_BPM, up = false))
        assertEquals(ChordTempo.MAX_BPM, ChordTempo.stepBpm(ChordTempo.MAX_BPM, up = true))

        assertEquals(ChordTempo(110, ChordBeatLength.ONE), ChordTempo.fromLegacyChordMs(550))
        assertEquals(ChordTempo(60, ChordBeatLength.ONE), ChordTempo.fromLegacyChordMs(1000))

        val scores = MemoryScores()
        scores.chordMs = 750
        assertEquals(ChordTempo(80, ChordBeatLength.ONE), scores.chordTempo)

        scores.chordTempo = ChordTempo(120, ChordBeatLength.TWO)
        assertEquals(1000, scores.chordMs)
        assertEquals(1000, Synth.chordMs)
        assertEquals(ChordTempo(120, ChordBeatLength.TWO), scores.chordTempo)
        Synth.chordMs = Synth.DEFAULT_CHORD_MS
    }

    @Test
    fun customRunCodecPreservesOptionalTempoAndLegacyEntries() {
        val runs = listOf(
            CustomRun("r1", "Schnell", listOf("S:I:V", "K:I:IV-V"), bpm = 140, beats = 1),
            CustomRun("r2", "Ohne Tempo", listOf("T:maj7")),
        )
        val decoded = LibraryCodec.decodeRuns(LibraryCodec.encodeRuns(runs))
        assertEquals(runs, decoded)
        assertEquals(ChordTempo(140, ChordBeatLength.ONE), decoded[0].customTempo)
        assertEquals(null, decoded[1].customTempo)
    }

    @Test
    fun multiRunSelectionResolvesSingleCombinedAndMigratesLegacy() {
        val scores = MemoryScores()
        val lib = Library(scores)
        val run1 = CustomRun("r1", "Jazz", listOf("T:maj7", "T:m7"), bpm = 90, beats = 2)
        val run2 = CustomRun("r2", "Pop", listOf("S:I:V", "S:I:vi"))
        lib.saveRun(run1)
        lib.saveRun(run2)

        // Single mode preserves Builtin target & legacy high score.
        val singleMode = lib.resolveSelection(setOf(RunSelectionCodec.modeToken(GameMode.DEGREES)))
        assertEquals(RunTarget.Builtin(GameMode.DEGREES), singleMode)

        // All 4 playable modes resolve to Builtin(MIX).
        val allModes = GameMode.PLAYABLE.map { RunSelectionCodec.modeToken(it) }.toSet()
        assertEquals(RunTarget.Builtin(GameMode.MIX), lib.resolveSelection(allModes))

        // Single custom run resolves to RunTarget.Custom.
        val singleCustom = lib.resolveSelection(setOf(RunSelectionCodec.runToken("r1")))
        assertTrue(singleCustom is RunTarget.Custom)

        // Combined selection mixes modes and custom runs with distinct items and dedicated high scores.
        val comboTokens = setOf(
            RunSelectionCodec.modeToken(GameMode.DEGREES),
            RunSelectionCodec.runToken("r1"),
            RunSelectionCodec.runToken("r2"),
        )
        val combo = lib.resolveSelection(comboTokens) as RunTarget.Combined
        assertEquals(listOf(GameMode.DEGREES), combo.modes)
        assertEquals(listOf(run1, run2), combo.runs)
        assertTrue(combo.items.any { it.id == "T:maj7" })
        assertTrue(combo.items.any { it.id == "S:I:IV" })
        assertEquals(combo.items.size, combo.items.map { it.id }.toSet().size)

        scores.setBestScore(combo, 90, 21)
        assertEquals(21, scores.bestScore(combo, 90))
        assertEquals(0, scores.bestScore(RunTarget.Builtin(GameMode.DEGREES), 90))

        // Toggling never leaves selection empty.
        val sole = setOf(RunSelectionCodec.modeToken(GameMode.DEGREES))
        assertEquals(sole, RunSelectionCodec.toggle(sole, RunSelectionCodec.modeToken(GameMode.DEGREES)))

        // Legacy migration from selectedRunId / gameMode.
        scores.selectedRunId = "r2"
        assertEquals(setOf(RunSelectionCodec.runToken("r2")), scores.speedrunSelection)
        scores.selectedRunId = ""
        scores.gameMode = GameMode.MIX
        assertEquals(allModes, scores.speedrunSelection)
    }

    @Test
    fun gateSessionDeckSupportsMultiModeSelection() {
        val deck = GateSessionDeck(
            mode = GameMode.MIX,
            maxPoolSize = 18,
            keyMode = GateKeyMode.SINGLE_KEY_TONALITY,
            rnd = Random(12),
            modes = setOf(GameMode.DEGREES, GameMode.CHORD_TYPES),
        )
        assertTrue(deck.pool.all { it.mode == GameMode.DEGREES || it.mode == GameMode.CHORD_TYPES })
        repeat(20) {
            val q = deck.nextQuestion()
            assertTrue(q.mode == GameMode.DEGREES || q.mode == GameMode.CHORD_TYPES)
        }
    }

    @Test
    fun customChordExerciseRoundTripsBuildsMidiAndIntegratesWithDeck() {
        val c1 = CustomChordExercise(
            id = "ch1",
            name = "I → ♭III → IV",
            chords = listOf(
                ChordSpec(0, CustomChordQuality.MAJ),
                ChordSpec(3, CustomChordQuality.MAJ),
                ChordSpec(5, CustomChordQuality.MIN, inversion = 1),
            ),
        )
        val c2 = CustomChordExercise(
            id = "ch2",
            name = "Cmaj9",
            chords = listOf(ChordSpec(0, CustomChordQuality.MAJ9)),
            fixedRootMidi = 48,
        )
        val encoded = LibraryCodec.encodeCustomChords(listOf(c1, c2))
        assertEquals(listOf(c1, c2), LibraryCodec.decodeCustomChords(encoded))

        assertEquals("I → ♭III → IVm (1.)", CustomChordExercise.suggestName(c1.chords, fixedRootMidi = null))
        assertEquals("Cmaj9", CustomChordExercise.suggestName(c2.chords, fixedRootMidi = 48))

        val (midi1, infos1) = c1.buildMidiAndInfos(48)
        assertEquals(3, midi1.size)
        assertEquals(3, infos1.size)
        midi1.forEach { chord -> assertEquals(chord.sorted(), chord) }
        assertEquals("C-Dur", infos1[0].name)
        assertEquals("E♭-Dur", infos1[1].name)

        val scores = MemoryScores()
        val lib = Library(scores)
        lib.saveCustomChord(c1)
        lib.saveCustomChord(c2)
        lib.saveRun(CustomRun("r1", "Eigene", listOf(c1.itemId, c2.itemId)))
        val resolved = lib.items(lib.runs.single())
        assertEquals(2, resolved.size)

        val deck = CustomRunDeck(resolved, allCustomChords = lib.customChords, rnd = Random(5))
        repeat(10) {
            val q = deck.nextQuestion()
            assertEquals(false, q.isAudio)
            assertEquals(2, q.choices.size)
            assertTrue(q.chords.isNotEmpty())
            assertEquals(q.chords.size, q.chordInfos.size)
            deck.record(true)
        }

        lib.deleteCustomChord("ch1")
        assertEquals(listOf("C:ch2"), lib.runs.single().itemIds)
    }
}
