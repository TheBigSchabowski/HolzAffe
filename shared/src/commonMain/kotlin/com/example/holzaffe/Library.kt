package com.example.holzaffe

import kotlin.random.Random

/** A recording the user uploaded and named in the app; [path] points into the app's own storage. */
data class CustomAudio(val id: String, val name: String, val path: String) {
    val itemId: String get() = AUDIO_PREFIX + id
}

/** A user-assembled run: a name plus the built-in exercise IDs and `A:<audioId>` items it asks. */
data class CustomRun(val id: String, val name: String, val itemIds: List<String>)

internal const val AUDIO_PREFIX = "A:"

/** One askable entry of a [CustomRun]: a built-in exercise or an uploaded recording. */
sealed interface RunItem {
    val id: String
    val label: String

    data class Builtin(val exercise: Exercise) : RunItem {
        override val id: String get() = exercise.id
        override val label: String get() = exercise.displayLabel
    }

    data class Audio(val audio: CustomAudio) : RunItem {
        override val id: String get() = audio.itemId
        override val label: String get() = audio.name
    }

    /** Plays a short sample, e.g. from the run editor. */
    fun preview() = when (this) {
        is Builtin -> ChordPlayer.play(QuestionFactory.forExercise(exercise).chords)
        is Audio -> ChordPlayer.playFile(audio.path)
    }
}

/** What a run (or the app gate) asks: a built-in mode or one of the user's own runs. */
sealed interface RunTarget {
    val label: String

    data class Builtin(val mode: GameMode) : RunTarget {
        override val label: String get() = mode.label
    }

    data class Custom(val run: CustomRun, val items: List<RunItem>) : RunTarget {
        override val label: String get() = run.name
    }
}

/** Lets the platform pick an audio file and copy it into app storage (Android: system file picker). */
interface AudioStorage {
    /** Short hint which file types play on this platform. */
    val formatsHint: String

    /** Opens the platform picker; [onPicked] gets the stored copy, or null if cancelled or failed. */
    fun pick(onPicked: (ImportedAudio?) -> Unit)

    /** Removes the stored copy of a deleted recording. */
    fun delete(path: String)
}

data class ImportedAudio(val path: String, val suggestedName: String)

/** Encodes audios and runs with ASCII control separators so names may contain any visible character. */
object LibraryCodec {
    private const val RECORD_SEP = '\u001E'
    private const val FIELD_SEP = '\u001F'
    private const val ITEM_SEP = '\u001D'

    private fun clean(s: String) = s.replace(RECORD_SEP, ' ').replace(FIELD_SEP, ' ').replace(ITEM_SEP, ' ')

    fun encodeAudios(audios: List<CustomAudio>): String =
        audios.joinToString(RECORD_SEP.toString()) { listOf(clean(it.id), clean(it.name), clean(it.path)).joinToString(FIELD_SEP.toString()) }

    fun decodeAudios(raw: String): List<CustomAudio> {
        if (raw.isBlank()) return emptyList()
        val seen = HashSet<String>()
        return raw.split(RECORD_SEP).mapNotNull { record ->
            val parts = record.split(FIELD_SEP)
            if (parts.size != 3) return@mapNotNull null
            val (id, name, path) = parts.map { it.trim() }
            if (id.isEmpty() || name.isEmpty() || path.isEmpty() || !seen.add(id)) null else CustomAudio(id, name, path)
        }
    }

    fun encodeRuns(runs: List<CustomRun>): String =
        runs.joinToString(RECORD_SEP.toString()) { run ->
            listOf(clean(run.id), clean(run.name), run.itemIds.joinToString(ITEM_SEP.toString()) { clean(it) })
                .joinToString(FIELD_SEP.toString())
        }

    fun decodeRuns(raw: String): List<CustomRun> {
        if (raw.isBlank()) return emptyList()
        val seen = HashSet<String>()
        return raw.split(RECORD_SEP).mapNotNull { record ->
            val parts = record.split(FIELD_SEP)
            if (parts.size != 3) return@mapNotNull null
            val id = parts[0].trim()
            val name = parts[1].trim()
            val items = parts[2].split(ITEM_SEP).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (id.isEmpty() || name.isEmpty() || !seen.add(id)) null else CustomRun(id, name, items)
        }
    }
}

/** The user's uploaded recordings and own runs, persisted through [Scores]' key/value store. */
class Library(private val scores: Scores) {
    var audios: List<CustomAudio>
        get() = LibraryCodec.decodeAudios(scores.getString(KEY_AUDIOS, ""))
        set(v) = scores.putString(KEY_AUDIOS, LibraryCodec.encodeAudios(v))

    var runs: List<CustomRun>
        get() = LibraryCodec.decodeRuns(scores.getString(KEY_RUNS, ""))
        set(v) = scores.putString(KEY_RUNS, LibraryCodec.encodeRuns(v))

    /** Resolves [run]'s item IDs, silently dropping deleted recordings or retired exercises. */
    fun items(run: CustomRun, audios: List<CustomAudio> = this.audios): List<RunItem> {
        val byItemId = audios.associateBy { it.itemId }
        return run.itemIds.mapNotNull { id ->
            byItemId[id]?.let { RunItem.Audio(it) }
                ?: QuestionFactory.exerciseById(id)?.let { RunItem.Builtin(it) }
        }
    }

    /** The custom run with [runId] as a [RunTarget], or the built-in [fallback] mode if it no longer exists or is empty. */
    fun target(runId: String, fallback: GameMode): RunTarget {
        val run = runs.firstOrNull { it.id == runId } ?: return RunTarget.Builtin(fallback)
        val items = items(run)
        return if (items.isEmpty()) RunTarget.Builtin(fallback) else RunTarget.Custom(run, items)
    }

    fun saveRun(run: CustomRun) {
        val current = runs
        runs = if (current.any { it.id == run.id }) current.map { if (it.id == run.id) run else it } else current + run
    }

    fun deleteRun(id: String) {
        runs = runs.filterNot { it.id == id }
    }

    fun addAudio(audio: CustomAudio) {
        audios = audios + audio
    }

    fun renameAudio(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        audios = audios.map { if (it.id == id) it.copy(name = trimmed) else it }
    }

    /** Removes the recording and every run reference to it; returns it so the platform can delete the file. */
    fun deleteAudio(id: String): CustomAudio? {
        val removed = audios.firstOrNull { it.id == id } ?: return null
        audios = audios.filterNot { it.id == id }
        runs = runs.map { run -> run.copy(itemIds = run.itemIds - removed.itemId) }
        return removed
    }

    companion object {
        private const val KEY_AUDIOS = "customAudios"
        private const val KEY_RUNS = "customRuns"

        fun newId(prefix: String, rnd: Random = Random): String =
            prefix + rnd.nextLong().toULong().toString(36)
    }
}

/**
 * Question source for a [CustomRun]: draws its items in reshuffled bags without back-to-back
 * repeats; a missed item comes back two questions later. [maxPoolSize] picks a random subset
 * (used by the app gate), [fixedKey] keeps built-in exercises in one key.
 */
class CustomRunDeck(
    items: List<RunItem>,
    private val allAudios: List<CustomAudio> = emptyList(),
    maxPoolSize: Int = Int.MAX_VALUE,
    private val fixedKey: Int? = null,
    private val rnd: Random = Random,
) {
    val pool: List<RunItem>
    private val bag = ArrayDeque<RunItem>()
    private var last: RunItem? = null

    init {
        require(items.isNotEmpty()) { "a custom run needs at least one item" }
        val distinct = items.distinctBy { it.id }
        pool = if (maxPoolSize >= distinct.size) distinct else distinct.shuffled(rnd).take(maxPoolSize.coerceAtLeast(1))
    }

    fun nextQuestion(): Question {
        if (bag.isEmpty()) refill()
        val item = bag.removeFirst()
        last = item
        return when (item) {
            is RunItem.Builtin -> QuestionFactory.forExercise(item.exercise, rnd, fixedKey)
            is RunItem.Audio -> audioQuestion(item.audio)
        }
    }

    /** Reports the answer to the question drawn last; a miss schedules it again soon. */
    fun record(correct: Boolean) {
        val item = last ?: return
        if (!correct && pool.size > 1) {
            bag.remove(item)
            bag.add(minOf(RETRY_GAP, bag.size), item)
        }
    }

    private fun refill() {
        var order = pool.shuffled(rnd)
        if (order.size > 1 && order.first() == last) order = order.drop(1) + order.first()
        bag.addAll(order)
    }

    /**
     * Up to nine names to pick from: the correct one, then the run's other recordings, then
     * other uploaded recordings as distractors. Positions follow upload order so they stay stable.
     */
    internal fun audioQuestion(audio: CustomAudio): Question {
        val runAudios = pool.filterIsInstance<RunItem.Audio>().map { it.audio }
        val library = (runAudios + allAudios).distinctBy { it.id }
        val others = (runAudios.shuffled(rnd) + allAudios.shuffled(rnd))
            .distinctBy { it.id }
            .filter { it.id != audio.id && it.name != audio.name }
            .distinctBy { it.name }
        val picked = (listOf(audio) + others.take(QuestionFactory.CHOICE_COUNT - 1))
            .sortedBy { a -> library.indexOfFirst { it.id == a.id } }
        return Question(
            chords = emptyList(),
            choices = picked.map { it.name },
            correct = picked.indexOf(audio),
            progressionId = audio.itemId,
            mode = GameMode.MIX,
            prompt = "Eigenes Audio",
            chordInfos = emptyList(),
            audioPath = audio.path,
        )
    }

    companion object {
        const val RETRY_GAP = 2
    }
}

/** Run length in seconds (default 1:30). */
var Scores.runSeconds: Int
    get() = getInt("runSeconds", RUN_SECONDS).coerceIn(RunLength.MIN, RunLength.MAX)
    set(v) = putInt("runSeconds", v.coerceIn(RunLength.MIN, RunLength.MAX))

/** ID of the own run picked for timed runs; empty means the built-in [Scores.gameMode]. */
var Scores.selectedRunId: String
    get() = getString("selectedRunId", "")
    set(v) = putString("selectedRunId", v)

/** Duration of each chord in ms; also pushed into [Synth.chordMs] so every playback uses it. */
var Scores.chordMs: Int
    get() = getInt("chordMs", Synth.DEFAULT_CHORD_MS).coerceIn(ChordLength.MIN, ChordLength.MAX)
    set(v) {
        val clamped = v.coerceIn(ChordLength.MIN, ChordLength.MAX)
        putInt("chordMs", clamped)
        Synth.chordMs = clamped
    }

/** Applies the saved chord duration to the synthesizer (call once per screen/activity start). */
fun Scores.applySoundSettings() {
    Synth.chordMs = chordMs
}

/** Steps for the chord-duration setting: 50 ms up to 1 s, then 250 ms up to 3 s. */
object ChordLength {
    const val MIN = 200
    const val MAX = 3000

    fun step(ms: Int, up: Boolean): Int {
        val v = ms.coerceIn(MIN, MAX)
        val next = if (up) v + (if (v < 1000) 50 else 250) else v - (if (v <= 1000) 50 else 250)
        return next.coerceIn(MIN, MAX)
    }

    /** German decimal format, e.g. `0,55 s`. */
    fun format(ms: Int): String = "${ms / 1000},${((ms % 1000) / 10).toString().padStart(2, '0')} s"
}

/** Steps for the run-length setting: 15 s up to 2:00, 30 s up to 5:00, then whole minutes up to 10:00. */
object RunLength {
    const val MIN = 15
    const val MAX = 600

    fun step(seconds: Int, up: Boolean): Int {
        val s = seconds.coerceIn(MIN, MAX)
        val next = if (up) {
            s + when {
                s < 120 -> 15
                s < 300 -> 30
                else -> 60
            }
        } else {
            s - when {
                s <= 120 -> 15
                s <= 300 -> 30
                else -> 60
            }
        }
        return next.coerceIn(MIN, MAX)
    }

    fun format(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

/**
 * All-time high per mode or own run and run length. The classic 1:30 runs of built-in
 * modes keep their old keys, so existing high scores stay where they were.
 */
fun Scores.bestScore(target: RunTarget, seconds: Int): Int = when {
    target is RunTarget.Builtin && seconds == RUN_SECONDS -> highScore(target.mode)
    else -> getInt(bestScoreKey(target, seconds), 0)
}

fun Scores.setBestScore(target: RunTarget, seconds: Int, value: Int) {
    if (target is RunTarget.Builtin && seconds == RUN_SECONDS) {
        setHighScore(target.mode, value)
    } else {
        putInt(bestScoreKey(target, seconds), value)
    }
}

internal fun bestScoreKey(target: RunTarget, seconds: Int): String = when (target) {
    is RunTarget.Builtin -> "highScore:${target.mode.key}:${seconds}s"
    is RunTarget.Custom -> "highScore:run:${target.run.id}:${seconds}s"
}
