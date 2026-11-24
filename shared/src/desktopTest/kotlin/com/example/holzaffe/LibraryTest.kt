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
