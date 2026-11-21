package com.example.holzaffe

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Selectable ear-training modes; [MIX] draws from all four [PLAYABLE] modes. */
enum class GameMode(val key: String, val label: String) {
    DEGREES("degrees", "Stufen"),
    CHORD_TYPES("types", "Akkordtypen"),
    CADENCES("cadences", "Kadenzen"),
    INVERSIONS("inversions", "Umkehrungen"),
    MIX("mix", "Mix");

    companion object {
        val PLAYABLE: List<GameMode> = listOf(DEGREES, CHORD_TYPES, CADENCES, INVERSIONS)
        fun fromKey(key: String?): GameMode = entries.firstOrNull { it.key == key } ?: DEGREES
    }
}

/** A chord as a scale degree: semitones above the tonic plus its triad intervals. */
data class Degree(val label: String, val root: Int, val intervals: List<Int>)

private val MAJ = listOf(0, 4, 7)
private val MIN = listOf(0, 3, 7)
private val DIM = listOf(0, 3, 6)
private val AUG = listOf(0, 4, 8)

private val MAJOR_TONIC = Degree("I", 0, MAJ)
private val MINOR_TONIC = Degree("i", 0, MIN)

internal val MAJOR_TARGETS = listOf(
    Degree("ii", 2, MIN),
    Degree("iii", 4, MIN),
    Degree("IV", 5, MAJ),
    Degree("iv", 5, MIN),
    Degree("V", 7, MAJ),
    Degree("♭VI", 8, MAJ),
    Degree("vi", 9, MIN),
    Degree("♭VII", 10, MAJ),
    Degree("vii°", 11, DIM),
)

internal val MINOR_TARGETS = listOf(
    Degree("ii°", 2, DIM),
    Degree("III", 3, MAJ),
    Degree("iv", 5, MIN),
    Degree("IV", 5, MAJ),
    Degree("v", 7, MIN),
    Degree("V", 7, MAJ),
    Degree("VI", 8, MAJ),
    Degree("VII", 10, MAJ),
    Degree("vii°", 11, DIM),
)

internal data class ChordType(val id: String, val label: String, val intervals: List<Int>)

internal val CHORD_TYPES = listOf(
    ChordType("Dur", "Dur", MAJ),
    ChordType("Moll", "Moll", MIN),
    ChordType("dim", "dim", DIM),
    ChordType("aug", "aug", AUG),
    ChordType("sus2", "sus2", listOf(0, 2, 7)),
    ChordType("sus4", "sus4", listOf(0, 5, 7)),
    ChordType("maj7", "maj7", listOf(0, 4, 7, 11)),
    ChordType("7", "7", listOf(0, 4, 7, 10)),
    ChordType("m7", "m7", listOf(0, 3, 7, 10)),
)

/** A concrete item from any of the four playable ear-training modes. */
sealed interface Exercise {
    val id: String
    val mode: GameMode
}

/** Mode 1: Two-chord progression starting on the tonic. */
data class Progression(val minor: Boolean, val target: Degree) : Exercise {
    val tonic: Degree get() = if (minor) MINOR_TONIC else MAJOR_TONIC
    override val id: String get() = "S:${tonic.label}:${target.label}"
    override val mode: GameMode get() = GameMode.DEGREES
}

/** Mode 2: Recognizing chord quality / extension in isolation. */
@ConsistentCopyVisibility
data class ChordTypeExercise internal constructor(internal val type: ChordType) : Exercise {
    override val id: String get() = "T:${type.id}"
    override val mode: GameMode get() = GameMode.CHORD_TYPES
}

/** Mode 3: Three-chord cadence starting on the tonic. */
data class Cadence(val minor: Boolean, val second: Degree, val third: Degree) : Exercise {
    val tonic: Degree get() = if (minor) MINOR_TONIC else MAJOR_TONIC
    val choiceLabel: String get() = "${tonic.label} → ${second.label} → ${third.label}"
    override val id: String get() = "K:${tonic.label}:${second.label}-${third.label}"
    override val mode: GameMode get() = GameMode.CADENCES
}

/** Mode 4: Recognizing triad quality and inversion together. */
data class InversionExercise(val quality: String, val intervals: List<Int>, val inversion: Int) : Exercise {
    private val invLabel: String
        get() = when (inversion) {
            0 -> "Grund"
            1 -> "1. Umk"
            else -> "2. Umk"
        }
    val choiceLabel: String get() = "$quality · $invLabel"
    override val id: String get() = "U:$quality:$inversion"
    override val mode: GameMode get() = GameMode.INVERSIONS
}

private fun majorDegree(label: String): Degree = MAJOR_TARGETS.first { it.label == label }
private fun minorDegree(label: String): Degree = MINOR_TARGETS.first { it.label == label }

internal val MAJOR_CADENCES: List<Cadence> = listOf(
    "IV" to "V",
    "V" to "vi",
    "vi" to "IV",
    "ii" to "V",
    "V" to "IV",
    "iii" to "IV",
    "IV" to "vi",
    "♭VII" to "IV",
    "IV" to "iv",
).map { (a, b) -> Cadence(minor = false, second = majorDegree(a), third = majorDegree(b)) }

internal val MINOR_CADENCES: List<Cadence> = listOf(
    "iv" to "V",
    "VI" to "VII",
    "VII" to "VI",
    "VI" to "III",
    "iv" to "VII",
    "III" to "VII",
    "ii°" to "V",
    "v" to "VI",
    "IV" to "VII",
).map { (a, b) -> Cadence(minor = true, second = minorDegree(a), third = minorDegree(b)) }

internal val CHORD_TYPE_EXERCISES: List<ChordTypeExercise> = CHORD_TYPES.map { ChordTypeExercise(it) }

internal val INVERSION_EXERCISES: List<InversionExercise> = listOf(
    "Dur" to MAJ,
    "Moll" to MIN,
    "dim" to DIM,
).flatMap { (quality, intervals) ->
    (0..2).map { inv -> InversionExercise(quality = quality, intervals = intervals, inversion = inv) }
}

val ALL_PROGRESSIONS: List<Progression> =
    MAJOR_TARGETS.map { Progression(minor = false, target = it) } +
        MINOR_TARGETS.map { Progression(minor = true, target = it) }

val EXERCISES_BY_MODE: Map<GameMode, List<Exercise>> = mapOf(
    GameMode.DEGREES to ALL_PROGRESSIONS,
    GameMode.CHORD_TYPES to CHORD_TYPE_EXERCISES,
    GameMode.CADENCES to (MAJOR_CADENCES + MINOR_CADENCES),
    GameMode.INVERSIONS to INVERSION_EXERCISES,
)

val ALL_EXERCISES: List<Exercise> = GameMode.PLAYABLE.flatMap { EXERCISES_BY_MODE.getValue(it) }

private val EXERCISE_BY_ID: Map<String, Exercise> = ALL_EXERCISES.associateBy { it.id }

/** Human-readable chord metadata for Check mode (guitar double-check). */
data class ChordInfo(
    val name: String,
    val degree: String,
    val notes: String,
)

private val SHARP_NOTES = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "H")
private val FLAT_NOTES = listOf("C", "D♭", "D", "E♭", "E", "F", "G♭", "G", "A♭", "A", "B♭", "H")
private val GUITAR_NOTES = listOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "H")

private fun noteNames(useSharps: Boolean): List<String> = if (useSharps) SHARP_NOTES else FLAT_NOTES

internal fun useSharpsForKey(keyPc: Int, minor: Boolean, degreeLabel: String): Boolean {
    if (degreeLabel.startsWith("♭")) return false
    if (degreeLabel == "vii°") return true
    return (!minor && keyPc in setOf(7, 2, 9, 4, 11, 6)) || (minor && keyPc in setOf(9, 4, 11, 6, 1, 8))
}

internal fun formatNotes(midiNotes: List<Int>, useSharps: Boolean): String {
    val names = noteNames(useSharps)
    return midiNotes.joinToString("–") { names[it.mod(12)] }
}

internal fun degreeChordInfo(degree: Degree, key: Int, minor: Boolean, midiNotes: List<Int>): ChordInfo {
    val keyPc = key.mod(12)
    val rootPc = (key + degree.root).mod(12)
    val useSharps = useSharpsForKey(keyPc, minor, degree.label)
    val rootName = noteNames(useSharps)[rootPc]
    val chordName = when (degree.intervals) {
        MAJ -> "$rootName-Dur"
        MIN -> "$rootName-Moll"
        DIM -> "$rootName-dim"
        else -> "$rootName-aug"
    }
    val noteSharps = if ('♭' in rootName) {
        false
    } else {
        when (degree.intervals) {
            MIN -> rootPc in setOf(4, 11, 6, 1, 8)
            DIM -> rootPc in setOf(6, 1, 8, 3)
            else -> rootPc in setOf(7, 2, 9, 4, 11, 6, 1)
        }
    }
    return ChordInfo(
        name = chordName,
        degree = degree.label,
        notes = formatNotes(midiNotes, noteSharps),
    )
}

internal fun chordTypeInfo(type: ChordType, key: Int, midiNotes: List<Int>): ChordInfo {
    val rootPc = key.mod(12)
    val rootName = GUITAR_NOTES[rootPc]
    val name = when (type.id) {
        "Dur" -> "$rootName-Dur"
        "Moll" -> "$rootName-Moll"
        "dim" -> "$rootName-dim"
        "aug" -> "$rootName-aug"
        else -> "$rootName${type.label}"
    }
    val noteSharps = when (type.id) {
        "Moll", "m7" -> rootPc in setOf(4, 11, 6, 1)
        "dim" -> rootPc in setOf(6, 1)
        "aug" -> rootPc !in setOf(3, 8, 10)
        else -> rootPc in setOf(7, 2, 9, 4, 11, 6, 1)
    }
    return ChordInfo(
        name = name,
        degree = type.label,
        notes = formatNotes(midiNotes, noteSharps),
    )
}

internal fun inversionChordInfo(exercise: InversionExercise, bass: Int, midiNotes: List<Int>): ChordInfo {
    val rootPc = (bass - exercise.intervals[exercise.inversion]).mod(12)
    val bassPc = bass.mod(12)
    val rootName = GUITAR_NOTES[rootPc]
    val baseName = when (exercise.quality) {
        "Dur" -> "$rootName-Dur"
        "Moll" -> "$rootName-Moll"
        else -> "$rootName-dim"
    }
    val useSharps = when (exercise.quality) {
        "Dur" -> rootPc in setOf(7, 2, 9, 4, 11, 6, 1)
        "Moll" -> rootPc in setOf(4, 11, 6, 1)
        else -> rootPc in setOf(6, 1)
    }
    val bassName = noteNames(useSharps)[bassPc]
    val fullName = if (exercise.inversion == 0) baseName else "$baseName/$bassName"
    return ChordInfo(
        name = fullName,
        degree = exercise.choiceLabel,
        notes = formatNotes(midiNotes, useSharps),
    )
}

/**
 * One question: MIDI chord voicings, 9 answer labels, the correct index,
 * the underlying [Exercise.id], its [GameMode], a short UI prompt, and concrete chord names.
 * Intentionally a plain class so assigning a fresh question always retriggers playback.
 */
class Question(
    val chords: List<List<Int>>,
    val choices: List<String>,
    val correct: Int,
    val progressionId: String,
    val mode: GameMode,
    val prompt: String,
    val chordInfos: List<ChordInfo>,
) {
    val chordSummary: String get() = chordInfos.joinToString(" → ") { it.name }
}

object QuestionFactory {
    const val CHOICE_COUNT = 9

    fun next(mode: GameMode = GameMode.DEGREES, rnd: Random = Random): Question {
        val resolved = if (mode == GameMode.MIX) GameMode.PLAYABLE[rnd.nextInt(GameMode.PLAYABLE.size)] else mode
        val pool = EXERCISES_BY_MODE.getValue(resolved)
        return forExercise(pool[rnd.nextInt(pool.size)], rnd)
    }

    /**
     * Builds a question for a specific [exercise].
     * When [fixedKey] (MIDI root in 48..59) is supplied, the question stays in that key instead
     * of picking a new random key every time.
     */
    fun forExercise(exercise: Exercise, rnd: Random = Random, fixedKey: Int? = null): Question = when (exercise) {
        is Progression -> buildProgression(exercise, rnd, fixedKey)
        is ChordTypeExercise -> buildChordType(exercise, rnd, fixedKey)
        is Cadence -> buildCadence(exercise, rnd, fixedKey)
        is InversionExercise -> buildInversion(exercise, rnd, fixedKey)
    }

    fun exerciseById(id: String): Exercise? = EXERCISE_BY_ID[id]

    fun progressionById(id: String): Progression? = EXERCISE_BY_ID[id] as? Progression

    private fun buildProgression(progression: Progression, rnd: Random, fixedKey: Int?): Question {
        val targets = if (progression.minor) MINOR_TARGETS else MAJOR_TARGETS
        val tonic = progression.tonic
        val correct = targets.indexOf(progression.target)
        require(correct >= 0) { "Unknown target ${progression.target.label} for mode minor=${progression.minor}" }
        val key = fixedKey ?: (48 + rnd.nextInt(12)) // tonic somewhere in C3..B3

        val first = voice(tonic, key, rnd.nextInt(3), near = null)
        val second = voice(progression.target, key, rnd.nextInt(3), near = first.first())
        return Question(
            chords = listOf(first, second),
            choices = targets.map { "${tonic.label} → ${it.label}" },
            correct = correct,
            progressionId = progression.id,
            mode = GameMode.DEGREES,
            prompt = "Akkordfolge · ${if (progression.minor) "Moll" else "Dur"}",
            chordInfos = listOf(
                degreeChordInfo(tonic, key, progression.minor, first),
                degreeChordInfo(progression.target, key, progression.minor, second),
            ),
        )
    }

    private fun buildChordType(exercise: ChordTypeExercise, rnd: Random, fixedKey: Int?): Question {
        val correct = CHORD_TYPE_EXERCISES.indexOf(exercise)
        val key = fixedKey ?: (48 + rnd.nextInt(12))
        val intervals = exercise.type.intervals
        // Keep root in the bass so sus2 and sus4 stay unambiguously distinct.
        val notes = if (rnd.nextBoolean()) {
            intervals.map { key + it }
        } else if (intervals.size == 3) {
            listOf(key, key + intervals[2], key + intervals[1] + 12)
        } else {
            listOf(key, key + intervals[2], key + intervals[3], key + intervals[1] + 12)
        }
        return Question(
            chords = listOf(notes),
            choices = CHORD_TYPES.map { it.label },
            correct = correct,
            progressionId = exercise.id,
            mode = GameMode.CHORD_TYPES,
            prompt = "Akkordtyp erkennen",
            chordInfos = listOf(chordTypeInfo(exercise.type, key, notes)),
        )
    }

    private fun buildCadence(cadence: Cadence, rnd: Random, fixedKey: Int?): Question {
        val pool = if (cadence.minor) MINOR_CADENCES else MAJOR_CADENCES
        val correct = pool.indexOf(cadence)
        require(correct >= 0) { "Unknown cadence ${cadence.id}" }
        val key = fixedKey ?: (48 + rnd.nextInt(12))

        val first = voice(cadence.tonic, key, rnd.nextInt(3), near = null)
        val second = voice(cadence.second, key, rnd.nextInt(3), near = first.first())
        val third = voice(cadence.third, key, rnd.nextInt(3), near = (first.first() + second.first()) / 2)
        return Question(
            chords = listOf(first, second, third),
            choices = pool.map { it.choiceLabel },
            correct = correct,
            progressionId = cadence.id,
            mode = GameMode.CADENCES,
            prompt = "Kadenz · ${if (cadence.minor) "Moll" else "Dur"}",
            chordInfos = listOf(
                degreeChordInfo(cadence.tonic, key, cadence.minor, first),
                degreeChordInfo(cadence.second, key, cadence.minor, second),
                degreeChordInfo(cadence.third, key, cadence.minor, third),
            ),
        )
    }

    private fun buildInversion(exercise: InversionExercise, rnd: Random, fixedKey: Int?): Question {
        val correct = INVERSION_EXERCISES.indexOf(exercise)
        val bass = fixedKey ?: (48 + rnd.nextInt(12)) // uniform bass so inversion cannot be guessed from pitch height
        val base = exercise.intervals
        val rotated = base.drop(exercise.inversion) + base.take(exercise.inversion).map { it + 12 }
        val shift = bass - rotated.first()
        val notes = rotated.map { it + shift }
        return Question(
            chords = listOf(notes),
            choices = INVERSION_EXERCISES.map { it.choiceLabel },
            correct = correct,
            progressionId = exercise.id,
            mode = GameMode.INVERSIONS,
            prompt = "Dreiklang & Umkehrung",
            chordInfos = listOf(inversionChordInfo(exercise, bass, notes)),
        )
    }

    /** Builds a close-position triad in the given inversion, its bass kept near [near]. */
    private fun voice(degree: Degree, key: Int, inversion: Int, near: Int?): List<Int> {
        val base = degree.intervals.map { key + degree.root + it }
        val notes = base.drop(inversion) + base.take(inversion).map { it + 12 }
        var shift = 0
        if (near != null) {
            while (notes.first() + shift - near > 6) shift -= 12
            while (near - (notes.first() + shift) > 6) shift += 12
        }
        return notes.map { it + shift }
    }
}

/**
 * Schedules missed exercises to return after a short gap ([RETRY_GAP]), then once more
 * after a longer confirmation gap ([CONFIRM_GAP]) before graduating out of the queue.
 */
class ReviewQueue(initial: List<Entry> = emptyList()) {
    data class Entry(val id: String, val stage: Int, val due: Int)

    private val entries = initial.toMutableList()
    private var lastId: String? = null

    val pendingCount: Int get() = entries.size

    fun pendingCount(mode: GameMode): Int = entries.count { matches(it.id, mode) }

    fun snapshot(): List<Entry> = entries.toList()

    private fun matches(id: String, mode: GameMode): Boolean =
        mode == GameMode.MIX || EXERCISE_BY_ID[id]?.mode == mode

    /** Picks the next question without mutating the queue so abandoning before answering loses no review. */
    fun nextQuestion(mode: GameMode = GameMode.DEGREES, rnd: Random = Random): Question {
        val dueEntry = entries.firstOrNull { it.due <= 0 && it.id != lastId && matches(it.id, mode) }
        if (dueEntry != null) {
            EXERCISE_BY_ID[dueEntry.id]?.let { return QuestionFactory.forExercise(it, rnd) }
        }
        val queuedIds = entries.mapTo(HashSet()) { it.id }
        val targetMode = if (mode == GameMode.MIX) GameMode.PLAYABLE[rnd.nextInt(GameMode.PLAYABLE.size)] else mode
        val modePool = EXERCISES_BY_MODE.getValue(targetMode)
        val fresh = modePool.filter { it.id != lastId && it.id !in queuedIds }
        val pool = fresh.ifEmpty { modePool.filter { it.id != lastId } }.ifEmpty { modePool }
        return QuestionFactory.forExercise(pool[rnd.nextInt(pool.size)], rnd)
    }

    /** Records an answer for [progressionId] within [mode], updating due counters and graduation state. */
    fun record(progressionId: String, correct: Boolean, mode: GameMode = GameMode.MIX) {
        lastId = progressionId
        for (i in entries.indices) {
            val e = entries[i]
            if (e.id != progressionId && e.due > 0 && matches(e.id, mode)) {
                entries[i] = e.copy(due = e.due - 1)
            }
        }
        val idx = entries.indexOfFirst { it.id == progressionId }
        if (!correct) {
            if (idx >= 0) entries.removeAt(idx)
            entries.add(Entry(progressionId, stage = 0, due = RETRY_GAP))
        } else if (idx >= 0) {
            val current = entries.removeAt(idx)
            if (current.stage == 0) {
                entries.add(Entry(progressionId, stage = 1, due = CONFIRM_GAP))
            }
        }
    }

    fun encode(): String = entries.joinToString(",") { "${it.id}|${it.stage}|${it.due}" }

    companion object {
        const val RETRY_GAP = 2
        const val CONFIRM_GAP = 5

        fun decode(raw: String): ReviewQueue {
            if (raw.isBlank()) return ReviewQueue()
            val seen = HashSet<String>()
            val parsed = mutableListOf<Entry>()
            for (token in raw.split(',')) {
                if (token.isBlank()) continue
                val parts = token.split('|')
                if (parts.size != 3) continue
                val id = parts[0]
                if (id !in EXERCISE_BY_ID || id in seen) continue
                val stage = parts[1].toIntOrNull()?.coerceIn(0, 1) ?: continue
                val due = parts[2].toIntOrNull()?.coerceAtLeast(0) ?: continue
                seen.add(id)
                parsed.add(Entry(id, stage, due))
            }
            return ReviewQueue(parsed)
        }
    }
}

/** How key and major/minor tonality behave during a single Android app-gate opening. */
enum class GateKeyMode(val key: String, val label: String) {
    /** Standard: one key AND only major OR only minor per app opening (e.g. only F-Dur or only F-Moll). */
    SINGLE_KEY_TONALITY("single_tonality", "1 Tonart"),
    /** Same tonic root per app opening, mixing parallel major and minor (e.g. F-Dur + F-Moll). */
    SAME_ROOT("same_root", "Dur + Moll"),
    /** Random key and major/minor on every single question. */
    RANDOM("random", "Wirrwarr");

    companion object {
        fun fromKey(key: String?): GateKeyMode = entries.firstOrNull { it.key == key } ?: SINGLE_KEY_TONALITY
    }
}

/** When a temporarily unlocked blocked app locks again on Android. */
enum class GateRelockMode(val key: String, val label: String) {
    /** Standard: stays open for the configured number of minutes. */
    TIMER("timer", "Timer"),
    /** Locks again as soon as the phone screen turns off. */
    ON_SCREEN_OFF("screen_off", "Display aus"),
    /** Locks again immediately when leaving the app or turning the screen off. */
    ON_APP_LEAVE("app_leave", "App zu");

    companion object {
        fun fromKey(key: String?): GateRelockMode = entries.firstOrNull { it.key == key } ?: TIMER
    }
}

/**
 * Per-app-launch deck for the Android app gate.
 *
 * Picks a fresh subset of at most [maxPoolSize] (default 7) distinct exercises for a single
 * unlock session so the user can recognize and learn them by ear/memory rather than guessing
 * from the full catalog. Exercises repeat in freshly reshuffled bags whose order changes on
 * every cycle and never repeats the same exercise back-to-back, preventing position memorization.
 */
class GateSessionDeck(
    val mode: GameMode,
    maxPoolSize: Int = DEFAULT_POOL_SIZE,
    val keyMode: GateKeyMode = GateKeyMode.SINGLE_KEY_TONALITY,
    private val rnd: Random = Random,
) {
    /** Fixed subset of distinct exercises active for this single app-start challenge. */
    val pool: List<Exercise>

    /**
     * Single tonic MIDI root (C3..B3) held constant throughout this app-start session when
     * [keyMode] is not [GateKeyMode.RANDOM]; changes on every new app opening.
     */
    val sessionKey: Int? = if (keyMode != GateKeyMode.RANDOM) 48 + rnd.nextInt(12) else null

    /**
     * When [keyMode] is [GateKeyMode.SINGLE_KEY_TONALITY], locks the session to either only
     * major (`false`) or only minor (`true`) progressions/cadences; changes on every new app opening.
     */
    val sessionMinor: Boolean? = if (keyMode == GateKeyMode.SINGLE_KEY_TONALITY) rnd.nextBoolean() else null

    private val bag = ArrayDeque<Exercise>()
    private var lastId: String? = null
    private var lastCycleOrder: List<String> = emptyList()

    init {
        require(maxPoolSize >= 1) { "maxPoolSize must be >= 1, was $maxPoolSize" }
        val source = if (mode == GameMode.MIX) ALL_EXERCISES else EXERCISES_BY_MODE.getValue(mode)
        val filtered = if (sessionMinor != null) {
            source.filter { ex ->
                when (ex) {
                    is Progression -> ex.minor == sessionMinor
                    is Cadence -> ex.minor == sessionMinor
                    is ChordTypeExercise, is InversionExercise -> true
                }
            }
        } else {
            source
        }
        pool = filtered.shuffled(rnd).take(maxPoolSize.coerceAtMost(filtered.size))
    }

    /** Returns the next question drawn exclusively from [pool] in a non-repeating shuffled order. */
    fun nextQuestion(): Question {
        if (bag.isEmpty()) refillBag()
        val exercise = bag.removeFirst()
        lastId = exercise.id
        return QuestionFactory.forExercise(exercise, rnd, fixedKey = sessionKey)
    }

    /**
     * Refills [bag] with a new permutation of [pool] that:
     * 1. does not start with [lastId] (no back-to-back repeat across bag boundaries), and
     * 2. differs from [lastCycleOrder] whenever pool.size >= 3 (so the sequence cannot be memorized).
     */
    private fun refillBag() {
        if (pool.size == 1) {
            bag.addLast(pool.first())
            return
        }
        var candidate = pool.shuffled(rnd)
        var attempts = 0
        while (attempts < MAX_SHUFFLE_ATTEMPTS) {
            val repeatsBoundary = candidate.first().id == lastId
            val sameCycle = pool.size >= 3 && lastCycleOrder.isNotEmpty() && candidate.map { it.id } == lastCycleOrder
            if (!repeatsBoundary && !sameCycle) break
            candidate = pool.shuffled(rnd)
            attempts++
        }
        // Deterministic fallback if random shuffles still collided at the boundary.
        if (candidate.first().id == lastId) {
            val mutable = candidate.toMutableList()
            val swapIdx = mutable.indexOfFirst { it.id != lastId }.coerceAtLeast(1)
            val tmp = mutable[0]
            mutable[0] = mutable[swapIdx]
            mutable[swapIdx] = tmp
            candidate = mutable
        }
        lastCycleOrder = candidate.map { it.id }
        candidate.forEach { bag.addLast(it) }
    }

    companion object {
        const val DEFAULT_POOL_SIZE = 7
        private const val MAX_SHUFFLE_ATTEMPTS = 16
    }
}

/**
 * A user-defined text task for the Android app gate (e.g. "Akkorde übers Griffbrett")
 * that keeps the blocked app locked for [minutes] (1..3) before unlocking with a short vibration.
 */
data class GateTask(
    val id: String,
    val text: String,
    val minutes: Int,
) {
    init {
        require(minutes in MIN_MINUTES..MAX_MINUTES) { "minutes must be in $MIN_MINUTES..$MAX_MINUTES, was $minutes" }
    }

    companion object {
        const val MIN_MINUTES = 1
        const val MAX_MINUTES = 3
    }
}

/** Encodes/decodes [GateTask] lists safely using ASCII control separators so arbitrary user text works. */
object GateTaskCodec {
    private const val RECORD_SEP = '\u001E'
    private const val FIELD_SEP = '\u001F'

    fun encode(tasks: List<GateTask>): String = tasks.joinToString(RECORD_SEP.toString()) { t ->
        val cleanId = t.id.replace(RECORD_SEP, ' ').replace(FIELD_SEP, ' ')
        val cleanText = t.text.replace(RECORD_SEP, ' ').replace(FIELD_SEP, ' ')
        "$cleanId$FIELD_SEP$cleanText$FIELD_SEP${t.minutes.coerceIn(GateTask.MIN_MINUTES, GateTask.MAX_MINUTES)}"
    }

    fun decode(raw: String): List<GateTask> {
        if (raw.isBlank()) return emptyList()
        val seen = HashSet<String>()
        val result = mutableListOf<GateTask>()
        for (record in raw.split(RECORD_SEP)) {
            if (record.isBlank()) continue
            val parts = record.split(FIELD_SEP)
            if (parts.size != 3) continue
            val id = parts[0].trim()
            val text = parts[1].trim()
            val minutes = parts[2].toIntOrNull()?.coerceIn(GateTask.MIN_MINUTES, GateTask.MAX_MINUTES) ?: continue
            if (id.isEmpty() || text.isEmpty() || !seen.add(id)) continue
            result.add(GateTask(id = id, text = text, minutes = minutes))
        }
        return result
    }
}

/** One entry in the Android app-gate rotation pool: either the ear-training quiz or a timed text task. */
sealed interface GateChallenge {
    val id: String

    data object Quiz : GateChallenge {
        override val id: String = QUIZ_ID
    }

    data class TimedTask(val task: GateTask) : GateChallenge {
        override val id: String get() = task.id
    }

    companion object {
        const val QUIZ_ID = "__quiz__"
    }
}

/**
 * Picks the next challenge from the user's active gate pool.
 * When multiple entries are active (e.g. ear-training + 2 custom tasks), avoids picking
 * [lastId] twice in a row so the gate visibly switches between them on each app opening.
 */
object GateChallengePicker {
    fun pickNext(
        includeQuiz: Boolean,
        activeTasks: List<GateTask>,
        lastId: String? = null,
        rnd: Random = Random,
    ): GateChallenge {
        val candidates = buildList {
            if (includeQuiz) add(GateChallenge.Quiz)
            activeTasks.forEach { add(GateChallenge.TimedTask(it)) }
        }
        if (candidates.isEmpty()) return GateChallenge.Quiz
        val pool = if (candidates.size > 1 && lastId != null) {
            candidates.filter { it.id != lastId }.ifEmpty { candidates }
        } else {
            candidates
        }
        return pool[rnd.nextInt(pool.size)]
    }
}

/** Renders chords to 16-bit mono PCM with a small piano-like additive synth. */
object Synth {
    const val RATE = 44100
    private const val FIRST_SEC = 0.55
    private const val LAST_SEC = 1.1
    private const val ARP_STEP_SEC = 0.08

    fun render(chords: List<List<Int>>): ShortArray {
        val counts = chords.indices.map {
            ((if (it == chords.lastIndex) LAST_SEC else FIRST_SEC) * RATE).toInt()
        }
        val mix = FloatArray(counts.sum())
        var offset = 0
        chords.forEachIndexed { i, notes ->
            val n = counts[i]
            for (note in notes) addNote(mix, offset, n, note)
            offset += n
        }
        return normalize(mix)
    }

    /** Renders each chord rolled upward note by note so dense voicings are easier to dissect. */
    fun renderArpeggiated(chords: List<List<Int>>): ShortArray {
        val counts = chords.mapIndexed { i, notes ->
            val tail = if (i == chords.lastIndex) 0.85 else 0.45
            val duration = notes.size * ARP_STEP_SEC + tail
            (duration * RATE).toInt()
        }
        val mix = FloatArray(counts.sum())
        var offset = 0
        chords.forEachIndexed { i, notes ->
            val n = counts[i]
            notes.forEachIndexed { j, note ->
                val noteOffset = (j * ARP_STEP_SEC * RATE).toInt()
                val length = (n - noteOffset).coerceAtLeast(1)
                addNote(mix, offset + noteOffset, length, note)
            }
            offset += n
        }
        return normalize(mix)
    }

    private fun normalize(mix: FloatArray): ShortArray {
        val peak = mix.maxOfOrNull { abs(it) }?.coerceAtLeast(1e-6f) ?: 1e-6f
        val gain = 0.85f * Short.MAX_VALUE / peak
        return ShortArray(mix.size) { (mix[it] * gain).toInt().toShort() }
    }

    private fun addNote(mix: FloatArray, start: Int, length: Int, midi: Int) {
        val freq = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val release = (0.03 * RATE).toInt()
        for (h in 1..6) {
            val f = freq * h
            if (f > RATE / 2.5) break
            val amp = 1.0 / h.toDouble().pow(1.6)
            val decay = 2.2 + 1.3 * h // higher partials die away faster
            val step = 2 * PI * f / RATE
            for (s in 0 until length) {
                val time = s.toDouble() / RATE
                val attack = (s / (0.004 * RATE)).coerceAtMost(1.0)
                val tail = ((length - s).toDouble() / release).coerceAtMost(1.0)
                mix[start + s] += (amp * attack * tail * exp(-decay * time) * sin(step * s)).toFloat()
            }
        }
    }
}

/** Plays rendered PCM on the platform's audio output; a new call replaces the running one. */
expect object ChordPlayer {
    fun play(chords: List<List<Int>>)
    fun playArpeggiated(chords: List<List<Int>>)
    fun stop()
}

/** Default duration (in seconds) that a wrong/correct answer stays highlighted after a miss. */
const val DEFAULT_MISS_DELAY_SEC = 3

/** Persisted game state; each platform backs it with its own preferences store. */
interface Scores {
    /** Most correct answers in one 1:30 run (legacy alias for [GameMode.DEGREES]). */
    var highScore: Int

    /** Correct answers in a row right now; carries over between runs and app starts until a miss. */
    var streak: Int

    /** Longest run of consecutive correct answers ever. */
    var bestStreak: Int

    /** Wrong answers that end a run early; 0 turns the limit off. */
    var maxErrors: Int

    /** Seconds a missed answer stays highlighted (red/green) before advancing (1..10, default 3). */
    var missDelaySec: Int

    /** Serialized mistake-review queue so missed exercises repeat across runs and app starts. */
    var reviewQueue: String

    /** Selected ear-training mode for timed runs. */
    var gameMode: GameMode

    fun highScore(mode: GameMode): Int
    fun setHighScore(mode: GameMode, value: Int)
}
