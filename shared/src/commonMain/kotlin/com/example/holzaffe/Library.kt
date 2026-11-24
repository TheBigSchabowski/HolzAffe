package com.example.holzaffe

import kotlin.random.Random

/** A recording the user uploaded and named in the app; [path] points into the app's own storage. */
data class CustomAudio(val id: String, val name: String, val path: String) {
    val itemId: String get() = AUDIO_PREFIX + id
}

/** Supported chord qualities for user-defined synthesized chords and progressions. */
enum class CustomChordQuality(val key: String, val label: String, val intervals: List<Int>) {
    MAJ("maj", "Dur", listOf(0, 4, 7)),
    MIN("min", "Moll", listOf(0, 3, 7)),
    DIM("dim", "dim", listOf(0, 3, 6)),
    AUG("aug", "aug", listOf(0, 4, 8)),
    SUS2("sus2", "sus2", listOf(0, 2, 7)),
    SUS4("sus4", "sus4", listOf(0, 5, 7)),
    POWER("5", "5", listOf(0, 7, 12)),
    MAJ7("maj7", "maj7", listOf(0, 4, 7, 11)),
    DOM7("7", "7", listOf(0, 4, 7, 10)),
    MIN7("m7", "m7", listOf(0, 3, 7, 10)),
    HALF_DIM("m7b5", "m7♭5", listOf(0, 3, 6, 10)),
    DIM7("dim7", "dim7", listOf(0, 3, 6, 9)),
    ADD9("add9", "add9", listOf(0, 4, 7, 14)),
    MADD9("madd9", "madd9", listOf(0, 3, 7, 14)),
    SIX("6", "6", listOf(0, 4, 7, 9)),
    MIN6("m6", "m6", listOf(0, 3, 7, 9)),
    MAJ9("maj9", "maj9", listOf(0, 4, 7, 11, 14)),
    DOM9("9", "9", listOf(0, 4, 7, 10, 14)),
    MIN9("m9", "m9", listOf(0, 3, 7, 10, 14));

    companion object {
        fun fromKey(key: String?): CustomChordQuality = entries.firstOrNull { it.key == key } ?: MAJ
    }
}

/** One chord inside a [CustomChordExercise], voiced relative to a tonic (or fixed root). */
data class ChordSpec(
    val rootOffset: Int,
    val quality: CustomChordQuality,
    val inversion: Int = 0,
) {
    /** Renders ascending MIDI pitches for [keyMidi] (C3..B3), keeping bass near [near] if given. */
    fun toMidiNotes(keyMidi: Int, near: Int? = null): List<Int> {
        val normOffset = rootOffset.mod(12)
        val base = quality.intervals.map { keyMidi + normOffset + it }
        val inv = inversion.coerceIn(0, minOf(2, base.lastIndex))
        val rotated = base.drop(inv) + base.take(inv).map { it + 12 }
        var shift = 0
        if (near != null) {
            while (rotated.first() + shift - near > 6) shift -= 12
            while (near - (rotated.first() + shift) > 6) shift += 12
        }
        while (rotated.first() + shift < 43) shift += 12
        while (rotated.last() + shift > 82) shift -= 12
        return rotated.map { it + shift }
    }

    /** Produces guitar-friendly chord metadata for Check mode. */
    fun toChordInfo(keyMidi: Int, midiNotes: List<Int>): ChordInfo {
        val normOffset = rootOffset.mod(12)
        val rootPc = (keyMidi + normOffset).mod(12)
        val rootName = noteLabel(rootPc)
        val baseName = when (quality) {
            CustomChordQuality.MAJ -> "$rootName-Dur"
            CustomChordQuality.MIN -> "$rootName-Moll"
            CustomChordQuality.DIM -> "$rootName-dim"
            CustomChordQuality.AUG -> "$rootName-aug"
            else -> "$rootName${quality.label}"
        }
        val bassPc = midiNotes.first().mod(12)
        val inv = inversion.coerceIn(0, 2)
        val fullName = if (inv == 0 || bassPc == rootPc) baseName else "$baseName/${noteLabel(bassPc)}"
        val degree = degreeLabel(normOffset) + if (quality == CustomChordQuality.MAJ) "" else " ${quality.label}"
        val notes = midiNotes.joinToString("–") { noteLabel(it.mod(12)) }
        return ChordInfo(name = fullName, degree = degree, notes = notes)
    }

    companion object {
        private val DEGREE_LABELS = listOf("I", "♭II", "II", "♭III", "III", "IV", "♯IV", "V", "♭VI", "VI", "♭VII", "VII")
        private val NOTE_LABELS = listOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "H")

        fun degreeLabel(offset: Int): String = DEGREE_LABELS[offset.mod(12)]
        fun noteLabel(pitchClass: Int): String = NOTE_LABELS[pitchClass.mod(12)]
    }
}

/**
 * User-created synthesized chord or chord progression (1..4 chords), either transposing
 * across random keys or fixed to [fixedRootMidi] (48..59).
 */
data class CustomChordExercise(
    val id: String,
    val name: String,
    val chords: List<ChordSpec>,
    val fixedRootMidi: Int? = null,
) {
    val itemId: String get() = CHORD_PREFIX + id

    /** Renders MIDI voicings and [ChordInfo] metadata for [keyMidi] with smooth voice leading. */
    fun buildMidiAndInfos(keyMidi: Int): Pair<List<List<Int>>, List<ChordInfo>> {
        val root = fixedRootMidi ?: keyMidi
        val midiList = ArrayList<List<Int>>(chords.size)
        val infoList = ArrayList<ChordInfo>(chords.size)
        chords.forEachIndexed { idx, spec ->
            val near = if (idx == 0) null else midiList[idx - 1].first()
            val notes = spec.toMidiNotes(root, near)
            midiList += notes
            infoList += spec.toChordInfo(root, notes)
        }
        return midiList to infoList
    }

    companion object {
        fun suggestName(chords: List<ChordSpec>, fixedRootMidi: Int?): String {
            if (chords.isEmpty()) return "Eigener Akkord"
            return chords.joinToString(" → ") { spec ->
                val suffix = when (spec.quality) {
                    CustomChordQuality.MAJ -> ""
                    CustomChordQuality.MIN -> "m"
                    CustomChordQuality.DIM -> "°"
                    CustomChordQuality.AUG -> "+"
                    else -> spec.quality.label
                }
                val invSuffix = if (spec.inversion in 1..2) " (${spec.inversion}.)" else ""
                if (fixedRootMidi != null) {
                    val rootName = ChordSpec.noteLabel((fixedRootMidi + spec.rootOffset).mod(12))
                    "$rootName$suffix$invSuffix"
                } else {
                    "${ChordSpec.degreeLabel(spec.rootOffset)}$suffix$invSuffix"
                }
            }
        }
    }
}

/**
 * A user-assembled run: a name, the built-in exercise IDs, `C:<chordId>` and `A:<audioId>` items
 * it asks, plus an optional per-run BPM and beat length that override the global tempo during playback.
 */
data class CustomRun(
    val id: String,
    val name: String,
    val itemIds: List<String>,
    val bpm: Int? = null,
    val beats: Int? = null,
) {
    /** Optional per-run tempo; null means the user's global [Scores.chordTempo] applies. */
    val customTempo: ChordTempo?
        get() = if (bpm != null && beats != null) {
            ChordTempo(
                bpm = bpm.coerceIn(ChordTempo.MIN_BPM, ChordTempo.MAX_BPM),
                beatLength = ChordBeatLength.fromBeats(beats),
            )
        } else {
            null
        }
}

internal const val AUDIO_PREFIX = "A:"
internal const val CHORD_PREFIX = "C:"

/** One askable entry of a [CustomRun]: a built-in exercise, a custom synthesized chord, or an uploaded recording. */
sealed interface RunItem {
    val id: String
    val label: String

    data class Builtin(val exercise: Exercise) : RunItem {
        override val id: String get() = exercise.id
        override val label: String get() = exercise.displayLabel
    }

    data class CustomChord(val chord: CustomChordExercise) : RunItem {
        override val id: String get() = chord.itemId
        override val label: String get() = chord.name
    }

    data class Audio(val audio: CustomAudio) : RunItem {
        override val id: String get() = audio.itemId
        override val label: String get() = audio.name
    }

    /** Plays a short sample, e.g. from the run editor or chord library. */
    fun preview() = when (this) {
        is Builtin -> ChordPlayer.play(QuestionFactory.forExercise(exercise).chords)
        is CustomChord -> ChordPlayer.play(chord.buildMidiAndInfos(chord.fixedRootMidi ?: 48).first)
        is Audio -> ChordPlayer.playFile(audio.path)
    }
}

/** What a run (or the app gate) asks: a built-in mode, one custom run, or a multi-run combination. */
sealed interface RunTarget {
    val label: String

    data class Builtin(val mode: GameMode) : RunTarget {
        override val label: String get() = mode.label
    }

    data class Custom(val run: CustomRun, val items: List<RunItem>) : RunTarget {
        override val label: String get() = run.name
    }

    /** Multiple built-in modes and/or custom runs combined into one mixed training pool. */
    data class Combined(
        override val label: String,
        val key: String,
        val items: List<RunItem>,
        val modes: List<GameMode>,
        val runs: List<CustomRun>,
    ) : RunTarget
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

/**
 * Encodes multi-select run tokens (`M:<modeKey>` for built-in modes and `R:<runId>` for custom runs)
 * so both Speedrun and the Android app gate can mix arbitrary selections.
 */
object RunSelectionCodec {
    const val MODE_PREFIX = "M:"
    const val RUN_PREFIX = "R:"
    private const val SEP = '\u001D'

    fun modeToken(mode: GameMode): String = MODE_PREFIX + mode.key
    fun runToken(runId: String): String = RUN_PREFIX + runId

    fun encode(tokens: Set<String>): String = tokens.joinToString(SEP.toString())

    fun decode(raw: String): Set<String> {
        if (raw.isBlank()) return emptySet()
        return raw.split(SEP).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    /** Toggles [token] in [current], refusing to remove the very last selected entry. */
    fun toggle(current: Set<String>, token: String): Set<String> =
        if (token in current) {
            if (current.size <= 1) current else current - token
        } else {
            current + token
        }
}

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
            val base = listOf(
                clean(run.id),
                clean(run.name),
                run.itemIds.joinToString(ITEM_SEP.toString()) { clean(it) },
            )
            // Keep legacy 3-field layout when no per-run tempo is set.
            val fields = if (run.bpm != null && run.beats != null) {
                base + listOf(run.bpm.toString(), run.beats.toString())
            } else {
                base
            }
            fields.joinToString(FIELD_SEP.toString())
        }

    fun decodeRuns(raw: String): List<CustomRun> {
        if (raw.isBlank()) return emptyList()
        val seen = HashSet<String>()
        return raw.split(RECORD_SEP).mapNotNull { record ->
            val parts = record.split(FIELD_SEP)
            if (parts.size != 3 && parts.size != 5) return@mapNotNull null
            val id = parts[0].trim()
            val name = parts[1].trim()
            val items = parts[2].split(ITEM_SEP).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val bpm = if (parts.size == 5) parts[3].trim().toIntOrNull()?.coerceIn(ChordTempo.MIN_BPM, ChordTempo.MAX_BPM) else null
            val beats = if (parts.size == 5) parts[4].trim().toIntOrNull()?.let { ChordBeatLength.fromBeats(it).beats } else null
            if (id.isEmpty() || name.isEmpty() || !seen.add(id)) null else CustomRun(id, name, items, bpm, beats)
        }
    }

    fun encodeCustomChords(chords: List<CustomChordExercise>): String =
        chords.joinToString(RECORD_SEP.toString()) { ex ->
            val encodedSpecs = ex.chords.joinToString(ITEM_SEP.toString()) { spec ->
                "${spec.rootOffset.mod(12)}:${spec.quality.key}:${spec.inversion.coerceIn(0, 2)}"
            }
            listOf(
                clean(ex.id),
                clean(ex.name),
                encodedSpecs,
                ex.fixedRootMidi?.toString().orEmpty(),
            ).joinToString(FIELD_SEP.toString())
        }

    fun decodeCustomChords(raw: String): List<CustomChordExercise> {
        if (raw.isBlank()) return emptyList()
        val seen = HashSet<String>()
        return raw.split(RECORD_SEP).mapNotNull { record ->
            val parts = record.split(FIELD_SEP)
            if (parts.size != 4) return@mapNotNull null
            val id = parts[0].trim()
            val name = parts[1].trim()
            val specs = parts[2].split(ITEM_SEP).mapNotNull { token ->
                val sub = token.trim().split(':')
                if (sub.size != 3) return@mapNotNull null
                val offset = sub[0].toIntOrNull()?.mod(12) ?: return@mapNotNull null
                val quality = CustomChordQuality.fromKey(sub[1])
                val inv = sub[2].toIntOrNull()?.coerceIn(0, 2) ?: 0
                ChordSpec(offset, quality, inv)
            }
            val fixedRoot = parts[3].trim().toIntOrNull()?.takeIf { it in 48..59 }
            if (id.isEmpty() || name.isEmpty() || specs.isEmpty() || !seen.add(id)) null
            else CustomChordExercise(id, name, specs.take(4), fixedRoot)
        }
    }
}

/** The user's uploaded recordings, custom synthesized chords, and own runs, persisted through [Scores]. */
class Library(private val scores: Scores) {
    var audios: List<CustomAudio>
        get() = LibraryCodec.decodeAudios(scores.getString(KEY_AUDIOS, ""))
        set(v) = scores.putString(KEY_AUDIOS, LibraryCodec.encodeAudios(v))

    var customChords: List<CustomChordExercise>
        get() = LibraryCodec.decodeCustomChords(scores.getString(KEY_CUSTOM_CHORDS, ""))
        set(v) = scores.putString(KEY_CUSTOM_CHORDS, LibraryCodec.encodeCustomChords(v))

    var runs: List<CustomRun>
        get() = LibraryCodec.decodeRuns(scores.getString(KEY_RUNS, ""))
        set(v) = scores.putString(KEY_RUNS, LibraryCodec.encodeRuns(v))

    /** Resolves [run]'s item IDs, silently dropping deleted items or retired exercises. */
    fun items(
        run: CustomRun,
        audios: List<CustomAudio> = this.audios,
        customChords: List<CustomChordExercise> = this.customChords,
    ): List<RunItem> {
        val audioById = audios.associateBy { it.itemId }
        val chordById = customChords.associateBy { it.itemId }
        return run.itemIds.mapNotNull { id ->
            audioById[id]?.let { RunItem.Audio(it) }
                ?: chordById[id]?.let { RunItem.CustomChord(it) }
                ?: QuestionFactory.exerciseById(id)?.let { RunItem.Builtin(it) }
        }
    }

    /** The custom run with [runId] as a [RunTarget], or the built-in [fallback] mode if it no longer exists or is empty. */
    fun target(runId: String, fallback: GameMode): RunTarget {
        val run = runs.firstOrNull { it.id == runId } ?: return RunTarget.Builtin(fallback)
        val items = items(run)
        return if (items.isEmpty()) RunTarget.Builtin(fallback) else RunTarget.Custom(run, items)
    }

    /**
     * Resolves a multi-select token set ([RunSelectionCodec]) into a [RunTarget].
     * Single modes, all four playable modes (= [GameMode.MIX]), and single custom runs map back
     * to [RunTarget.Builtin] / [RunTarget.Custom] so existing high scores and review queues stay intact.
     */
    fun resolveSelection(tokens: Set<String>, fallbackMode: GameMode = GameMode.DEGREES): RunTarget {
        val hasMixToken = RunSelectionCodec.modeToken(GameMode.MIX) in tokens
        val rawModes = if (hasMixToken) {
            GameMode.PLAYABLE
        } else {
            tokens.mapNotNull { token ->
                if (token.startsWith(RunSelectionCodec.MODE_PREFIX)) {
                    val key = token.removePrefix(RunSelectionCodec.MODE_PREFIX)
                    GameMode.PLAYABLE.firstOrNull { it.key == key }
                } else {
                    null
                }
            }
        }
        val selectedModes = GameMode.PLAYABLE.filter { it in rawModes }

        val runsById = runs.associateBy { it.id }
        val resolvedRuns = tokens.mapNotNull { token ->
            if (!token.startsWith(RunSelectionCodec.RUN_PREFIX)) return@mapNotNull null
            val id = token.removePrefix(RunSelectionCodec.RUN_PREFIX)
            val run = runsById[id] ?: return@mapNotNull null
            val resolvedItems = items(run)
            if (resolvedItems.isEmpty()) null else run to resolvedItems
        }

        if (selectedModes.isEmpty() && resolvedRuns.isEmpty()) {
            return RunTarget.Builtin(fallbackMode)
        }
        if (resolvedRuns.isEmpty() && selectedModes.size == 1) {
            return RunTarget.Builtin(selectedModes.first())
        }
        if (resolvedRuns.isEmpty() && selectedModes.size == GameMode.PLAYABLE.size) {
            return RunTarget.Builtin(GameMode.MIX)
        }
        if (selectedModes.isEmpty() && resolvedRuns.size == 1) {
            val (run, items) = resolvedRuns.first()
            return RunTarget.Custom(run, items)
        }

        val combinedItems = buildList {
            selectedModes.forEach { mode ->
                EXERCISES_BY_MODE.getValue(mode).forEach { add(RunItem.Builtin(it)) }
            }
            resolvedRuns.forEach { (_, items) -> addAll(items) }
        }.distinctBy { it.id }

        val customRuns = resolvedRuns.map { it.first }
        val names = selectedModes.map { it.label } + customRuns.map { it.name }
        val label = if (names.size <= 2) {
            names.joinToString(" + ")
        } else {
            "${names.first()} + ${names.size - 1} weitere"
        }
        val keyParts = (selectedModes.map { "m:${it.key}" } + customRuns.map { "r:${it.id}" }).sorted()
        return RunTarget.Combined(
            label = label,
            key = keyParts.joinToString("+"),
            items = combinedItems,
            modes = selectedModes,
            runs = customRuns,
        )
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

    fun saveCustomChord(chord: CustomChordExercise) {
        val current = customChords
        customChords = if (current.any { it.id == chord.id }) {
            current.map { if (it.id == chord.id) chord else it }
        } else {
            current + chord
        }
    }

    /** Removes a custom synthesized chord/progression and strips its ID from every custom run. */
    fun deleteCustomChord(id: String) {
        val removed = customChords.firstOrNull { it.id == id } ?: return
        customChords = customChords.filterNot { it.id == id }
        runs = runs.map { run -> run.copy(itemIds = run.itemIds - removed.itemId) }
    }

    companion object {
        private const val KEY_AUDIOS = "customAudios"
        private const val KEY_CUSTOM_CHORDS = "customChords"
        private const val KEY_RUNS = "customRuns"

        fun newId(prefix: String, rnd: Random = Random): String =
            prefix + rnd.nextLong().toULong().toString(36)
    }
}

/**
 * Question source for a [CustomRun] or combined selection: draws its items in reshuffled bags
 * without back-to-back repeats; a missed item comes back two questions later.
 * [maxPoolSize] picks a random subset (used by the app gate), [fixedKey] keeps built-in exercises
 * in one key, and [singleTonality] optionally restricts built-in progressions/cadences to major (`false`)
 * or minor (`true`).
 */
class CustomRunDeck(
    items: List<RunItem>,
    private val allAudios: List<CustomAudio> = emptyList(),
    maxPoolSize: Int = Int.MAX_VALUE,
    private val fixedKey: Int? = null,
    private val rnd: Random = Random,
    singleTonality: Boolean? = null,
    private val allCustomChords: List<CustomChordExercise> = emptyList(),
) {
    val pool: List<RunItem>
    private val bag = ArrayDeque<RunItem>()
    private var last: RunItem? = null

    init {
        require(items.isNotEmpty()) { "a custom run needs at least one item" }
        val distinct = items.distinctBy { it.id }
        val filtered = if (singleTonality != null) {
            val matching = distinct.filter { item ->
                when (item) {
                    is RunItem.Builtin -> when (val ex = item.exercise) {
                        is Progression -> ex.minor == singleTonality
                        is Cadence -> ex.minor == singleTonality
                        is ChordTypeExercise, is InversionExercise -> true
                    }
                    is RunItem.CustomChord, is RunItem.Audio -> true
                }
            }
            matching.ifEmpty { distinct }
        } else {
            distinct
        }
        pool = if (maxPoolSize >= filtered.size) filtered else filtered.shuffled(rnd).take(maxPoolSize.coerceAtLeast(1))
    }

    fun nextQuestion(): Question {
        if (bag.isEmpty()) refill()
        val item = bag.removeFirst()
        last = item
        return when (item) {
            is RunItem.Builtin -> QuestionFactory.forExercise(item.exercise, rnd, fixedKey)
            is RunItem.CustomChord -> customChordQuestion(item.chord)
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

    /**
     * Synthesizes a user-created chord or progression, using other custom chords from the run
     * (and library) as distractors in stable creation order.
     */
    internal fun customChordQuestion(chord: CustomChordExercise): Question {
        val runChords = pool.filterIsInstance<RunItem.CustomChord>().map { it.chord }
        val library = (runChords + allCustomChords).distinctBy { it.id }
        val others = (runChords.shuffled(rnd) + allCustomChords.shuffled(rnd))
            .distinctBy { it.id }
            .filter { it.id != chord.id && it.name != chord.name }
            .distinctBy { it.name }
        val picked = (listOf(chord) + others.take(QuestionFactory.CHOICE_COUNT - 1))
            .sortedBy { c -> library.indexOfFirst { it.id == c.id } }
        val key = chord.fixedRootMidi ?: fixedKey ?: (48 + rnd.nextInt(12))
        val (midiChords, infos) = chord.buildMidiAndInfos(key)
        return Question(
            chords = midiChords,
            choices = picked.map { it.name },
            correct = picked.indexOf(chord),
            progressionId = chord.itemId,
            mode = GameMode.MIX,
            prompt = if (chord.chords.size == 1) "Eigener Akkord" else "Eigene Akkordfolge",
            chordInfos = infos,
            audioPath = null,
        )
    }

    companion object {
        const val RETRY_GAP = 2
    }
}

/** Musical note value controlling how many beats each chord sounds at the chosen BPM. */
enum class ChordBeatLength(val beats: Int, val label: String, val subtitle: String) {
    ONE(1, "1/4", "1 Schlag"),
    TWO(2, "2/4", "2 Schläge"),
    THREE(3, "3/4", "3 Schläge"),
    FOUR(4, "4/4", "1 Takt");

    companion object {
        fun fromBeats(beats: Int): ChordBeatLength = entries.firstOrNull { it.beats == beats } ?: ONE
    }
}

/**
 * Musical tempo specification for chord playback: [bpm] beats per minute paired with a
 * [ChordBeatLength] note value (`1/4` = 1 beat, `4/4` = 4 beats / 1 bar).
 */
data class ChordTempo(
    val bpm: Int = DEFAULT_BPM,
    val beatLength: ChordBeatLength = ChordBeatLength.ONE,
) {
    /** Derived chord duration in ms for [Synth.chordMs], clamped to a playable range. */
    val chordMs: Int
        get() = ((60_000.0 * beatLength.beats) / bpm.coerceIn(MIN_BPM, MAX_BPM))
            .toInt()
            .coerceIn(MIN_CHORD_MS, MAX_CHORD_MS)

    fun formatShort(): String = "${bpm.coerceIn(MIN_BPM, MAX_BPM)} BPM · ${beatLength.label}"

    fun formatDetailed(): String = "${formatShort()} (${ChordLength.format(chordMs)})"

    companion object {
        const val MIN_BPM = 40
        const val MAX_BPM = 240
        const val STEP_BPM = 5
        const val DEFAULT_BPM = 110
        const val MIN_CHORD_MS = 200
        const val MAX_CHORD_MS = 4000

        fun stepBpm(bpm: Int, up: Boolean): Int {
            val clamped = bpm.coerceIn(MIN_BPM, MAX_BPM)
            val next = if (up) clamped + STEP_BPM else clamped - STEP_BPM
            return next.coerceIn(MIN_BPM, MAX_BPM)
        }

        /** Migrates an older v1.6.0 millisecond setting onto the nearest 5-BPM grid. */
        fun fromLegacyChordMs(ms: Int): ChordTempo {
            val clampedMs = ms.coerceIn(ChordLength.MIN, ChordLength.MAX)
            val beatLength = when {
                clampedMs <= 1250 -> ChordBeatLength.ONE
                clampedMs <= 2200 -> ChordBeatLength.TWO
                else -> ChordBeatLength.FOUR
            }
            val rawBpm = (60_000.0 * beatLength.beats) / clampedMs
            val lower = (rawBpm / STEP_BPM).toInt() * STEP_BPM
            val upper = lower + STEP_BPM
            val roundedBpm = (if (rawBpm - lower >= upper - rawBpm) upper else lower).coerceIn(MIN_BPM, MAX_BPM)
            return ChordTempo(roundedBpm, beatLength)
        }
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

/**
 * Multi-select run tokens (`M:<mode>` and `R:<runId>`) used in the Speedrun tab.
 * Automatically migrates legacy single-selection [selectedRunId]/[gameMode] preferences.
 */
var Scores.speedrunSelection: Set<String>
    get() {
        val raw = getString("speedrunSelection", "")
        if (raw.isNotBlank()) {
            val decoded = RunSelectionCodec.decode(raw)
            if (decoded.isNotEmpty()) return decoded
        }
        val legacyRun = selectedRunId
        if (legacyRun.isNotEmpty()) return setOf(RunSelectionCodec.runToken(legacyRun))
        return if (gameMode == GameMode.MIX) {
            GameMode.PLAYABLE.map { RunSelectionCodec.modeToken(it) }.toSet()
        } else {
            setOf(RunSelectionCodec.modeToken(gameMode))
        }
    }
    set(v) {
        val clean = v.ifEmpty { setOf(RunSelectionCodec.modeToken(GameMode.DEGREES)) }
        putString("speedrunSelection", RunSelectionCodec.encode(clean))
        // Keep legacy fields consistent when selection maps to a single built-in mode or custom run.
        val modes = GameMode.PLAYABLE.filter { RunSelectionCodec.modeToken(it) in clean }
        val runTokens = clean.filter { it.startsWith(RunSelectionCodec.RUN_PREFIX) }
        when {
            runTokens.size == 1 && modes.isEmpty() -> {
                selectedRunId = runTokens.first().removePrefix(RunSelectionCodec.RUN_PREFIX)
            }
            runTokens.isEmpty() && modes.size == 1 -> {
                selectedRunId = ""
                gameMode = modes.first()
            }
            runTokens.isEmpty() && modes.size == GameMode.PLAYABLE.size -> {
                selectedRunId = ""
                gameMode = GameMode.MIX
            }
            else -> {
                selectedRunId = ""
            }
        }
    }

/** Duration of each chord in ms; also pushed into [Synth.chordMs] so every playback uses it. */
var Scores.chordMs: Int
    get() = getInt("chordMs", Synth.DEFAULT_CHORD_MS).coerceIn(ChordLength.MIN, ChordLength.MAX)
    set(v) {
        val clamped = v.coerceIn(ChordLength.MIN, ChordLength.MAX)
        putInt("chordMs", clamped)
        Synth.chordMs = clamped
    }

/** Global BPM & beat-length tempo setting; derives [chordMs] and updates [Synth.chordMs]. */
var Scores.chordTempo: ChordTempo
    get() {
        val savedBpm = getInt("chordBpm", -1)
        val savedBeats = getInt("chordBeats", -1)
        if (savedBpm > 0 && savedBeats > 0) {
            return ChordTempo(
                bpm = savedBpm.coerceIn(ChordTempo.MIN_BPM, ChordTempo.MAX_BPM),
                beatLength = ChordBeatLength.fromBeats(savedBeats),
            )
        }
        val legacyMs = getInt("chordMs", -1)
        return if (legacyMs > 0) ChordTempo.fromLegacyChordMs(legacyMs) else ChordTempo()
    }
    set(v) {
        val clamped = ChordTempo(v.bpm.coerceIn(ChordTempo.MIN_BPM, ChordTempo.MAX_BPM), v.beatLength)
        putInt("chordBpm", clamped.bpm)
        putInt("chordBeats", clamped.beatLength.beats)
        putInt("chordMs", clamped.chordMs)
        Synth.chordMs = clamped.chordMs
    }

/** Applies the saved chord tempo/duration to the synthesizer (call once per screen/activity start). */
fun Scores.applySoundSettings() {
    val savedBpm = getInt("chordBpm", -1)
    val legacyMs = getInt("chordMs", -1)
    Synth.chordMs = when {
        savedBpm > 0 -> chordTempo.chordMs
        legacyMs > 0 -> legacyMs.coerceIn(ChordLength.MIN, ChordLength.MAX)
        else -> Synth.DEFAULT_CHORD_MS
    }
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
 * All-time high per mode, own run, or combined selection and run length. The classic 1:30 runs of
 * built-in modes keep their old keys, so existing high scores stay where they were.
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
    is RunTarget.Combined -> "highScore:combo:${target.key}:${seconds}s"
}
