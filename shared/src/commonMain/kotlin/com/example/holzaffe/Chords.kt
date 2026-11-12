package com.example.holzaffe

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** A chord as a scale degree: semitones above the tonic plus its triad intervals. */
data class Degree(val label: String, val root: Int, val intervals: List<Int>)

private val MAJ = listOf(0, 4, 7)
private val MIN = listOf(0, 3, 7)

private val MAJOR_TONIC = Degree("I", 0, MAJ)
private val MINOR_TONIC = Degree("i", 0, MIN)

private val MAJOR_TARGETS = listOf(
    Degree("ii", 2, MIN),
    Degree("iii", 4, MIN),
    Degree("IV", 5, MAJ),
    Degree("V", 7, MAJ),
    Degree("vi", 9, MIN),
)

private val MINOR_TARGETS = listOf(
    Degree("III", 3, MAJ),
    Degree("iv", 5, MIN),
    Degree("IV", 5, MAJ),
    Degree("v", 7, MIN),
    Degree("V", 7, MAJ),
    Degree("VI", 8, MAJ),
    Degree("VII", 10, MAJ),
)

/** One question: two chords (as MIDI note lists) and three answer labels. */
class Question(
    val chords: List<List<Int>>,
    val choices: List<String>,
    val correct: Int,
)

object QuestionFactory {
    /**
     * The progression always starts on the tonic (I or i), since without that anchor
     * a two-chord progression is ambiguous (IV→I sounds like I→V). All three choices
     * share the mode, so the first chord's quality does not give the answer away.
     */
    fun next(rnd: Random = Random): Question {
        val minor = rnd.nextBoolean()
        val tonic = if (minor) MINOR_TONIC else MAJOR_TONIC
        val options = (if (minor) MINOR_TARGETS else MAJOR_TARGETS).shuffled(rnd).take(3)
        val correct = rnd.nextInt(3)
        val key = 48 + rnd.nextInt(12) // tonic somewhere in C3..B3

        val first = voice(tonic, key, rnd.nextInt(3), near = null)
        val second = voice(options[correct], key, rnd.nextInt(3), near = first.first())
        return Question(
            chords = listOf(first, second),
            choices = options.map { "${tonic.label} → ${it.label}" },
            correct = correct,
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

/** Renders a progression to 16-bit mono PCM with a small piano-like additive synth. */
object Synth {
    const val RATE = 44100
    private const val FIRST_SEC = 0.55
    private const val LAST_SEC = 1.1

    fun render(chords: List<List<Int>>): ShortArray {
        val lengths = chords.indices.map { if (it == chords.lastIndex) LAST_SEC else FIRST_SEC }
        val total = (lengths.sum() * RATE).toInt()
        val mix = FloatArray(total)
        var offset = 0
        chords.forEachIndexed { i, notes ->
            val n = (lengths[i] * RATE).toInt()
            for (note in notes) addNote(mix, offset, n, note)
            offset += n
        }
        val peak = mix.maxOf { abs(it) }.coerceAtLeast(1e-6f)
        val gain = 0.85f * Short.MAX_VALUE / peak
        return ShortArray(total) { (mix[it] * gain).toInt().toShort() }
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
    fun stop()
}

/** Persisted game state; each platform backs it with its own preferences store. */
interface Scores {
    /** Most correct answers in one 1:30 run. */
    var highScore: Int

    /** Correct answers in a row right now; carries over between runs and app starts until a miss. */
    var streak: Int

    /** Longest run of consecutive correct answers ever. */
    var bestStreak: Int

    /** Wrong answers that end a run early; 0 turns the limit off. */
    var maxErrors: Int
}
