package com.example.holzaffe

import android.content.Context
import android.content.SharedPreferences

/** All persisted state: scores, gate settings, temporary unlocks. */
class Store(context: Context) : Scores {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("holzaffe", Context.MODE_PRIVATE)

    override var highScore: Int
        get() = prefs.getInt("highScore", 0)
        set(v) = prefs.edit().putInt("highScore", v).apply()

    override var streak: Int
        get() = prefs.getInt("streak", 0)
        set(v) = prefs.edit().putInt("streak", v).apply()

    override var bestStreak: Int
        get() = prefs.getInt("bestStreak", 0)
        set(v) = prefs.edit().putInt("bestStreak", v).apply()

    override var maxErrors: Int
        get() = prefs.getInt("maxErrors", 0)
        set(v) = prefs.edit().putInt("maxErrors", v).apply()

    override var missDelaySec: Int
        get() = prefs.getInt("missDelaySec", DEFAULT_MISS_DELAY_SEC).coerceIn(1, 10)
        set(v) = prefs.edit().putInt("missDelaySec", v.coerceIn(1, 10)).apply()

    override var reviewQueue: String
        get() = prefs.getString("reviewQueue", "").orEmpty()
        set(v) = prefs.edit().putString("reviewQueue", v).apply()

    override var gameMode: GameMode
        get() = GameMode.fromKey(prefs.getString("gameMode", null))
        set(v) = prefs.edit().putString("gameMode", v.key).apply()

    override fun highScore(mode: GameMode): Int = prefs.getInt(highScoreKey(mode), 0)

    override fun setHighScore(mode: GameMode, value: Int) {
        prefs.edit().putInt(highScoreKey(mode), value).apply()
    }

    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    override fun getString(key: String, default: String): String = prefs.getString(key, default) ?: default
    override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()

    /** Own run used by the app gate instead of [gateMode]; empty means the built-in mode. */
    var gateRunId: String
        get() = prefs.getString("gateRunId", "").orEmpty()
        set(v) = prefs.edit().putString("gateRunId", v).apply()

    var gateMode: GameMode
        get() = GameMode.fromKey(prefs.getString("gateMode", null))
        set(v) = prefs.edit().putString("gateMode", v.key).apply()

    /**
     * Multi-select run tokens (`M:<mode>` and `R:<runId>`) for the Android app gate.
     * Automatically migrates legacy [gateRunId]/[gateMode] preferences.
     */
    var gateSelection: Set<String>
        get() {
            val raw = prefs.getString("gateSelection", "").orEmpty()
            if (raw.isNotBlank()) {
                val decoded = RunSelectionCodec.decode(raw)
                if (decoded.isNotEmpty()) return decoded
            }
            val legacyRun = gateRunId
            if (legacyRun.isNotEmpty()) return setOf(RunSelectionCodec.runToken(legacyRun))
            return if (gateMode == GameMode.MIX) {
                GameMode.PLAYABLE.map { RunSelectionCodec.modeToken(it) }.toSet()
            } else {
                setOf(RunSelectionCodec.modeToken(gateMode))
            }
        }
        set(v) {
            val clean = v.ifEmpty { setOf(RunSelectionCodec.modeToken(GameMode.DEGREES)) }
            prefs.edit().putString("gateSelection", RunSelectionCodec.encode(clean)).apply()
            val modes = GameMode.PLAYABLE.filter { RunSelectionCodec.modeToken(it) in clean }
            val runTokens = clean.filter { it.startsWith(RunSelectionCodec.RUN_PREFIX) }
            when {
                runTokens.size == 1 && modes.isEmpty() -> {
                    gateRunId = runTokens.first().removePrefix(RunSelectionCodec.RUN_PREFIX)
                }
                runTokens.isEmpty() && modes.size == 1 -> {
                    gateRunId = ""
                    gateMode = modes.first()
                }
                runTokens.isEmpty() && modes.size == GameMode.PLAYABLE.size -> {
                    gateRunId = ""
                    gateMode = GameMode.MIX
                }
                else -> {
                    gateRunId = ""
                }
            }
        }

    private fun highScoreKey(mode: GameMode): String =
        if (mode == GameMode.DEGREES) "highScore" else "highScore:${mode.key}"

    var blocked: Set<String>
        get() = prefs.getStringSet("blocked", null) ?: setOf("com.whatsapp")
        set(v) = prefs.edit().putStringSet("blocked", v).apply()

    /** Correct answers needed before a blocked app opens. */
    var required: Int
        get() = prefs.getInt("required", 5)
        set(v) = prefs.edit().putInt("required", v).apply()

    /** Maximum distinct exercises drawn per app-start gate session (default 7). */
    var gatePoolSize: Int
        get() = prefs.getInt("gatePoolSize", GateSessionDeck.DEFAULT_POOL_SIZE).coerceIn(2, 18)
        set(v) = prefs.edit().putInt("gatePoolSize", v.coerceIn(2, 18)).apply()

    /**
     * Exercise IDs the user deselected for the app gate (e.g. `S:I:iii`). Stored as exclusions so
     * everything is asked by default and exercises added in later versions start out enabled.
     */
    var gateDisabledExerciseIds: Set<String>
        get() = prefs.getStringSet("gateDisabledExerciseIds", null)?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet("gateDisabledExerciseIds", v.toSet()).apply()

    /** How key and major/minor tonality behave per app-start gate session (default: single key + single gender). */
    var gateKeyMode: GateKeyMode
        get() = GateKeyMode.fromKey(prefs.getString("gateKeyMode", null))
        set(v) = prefs.edit().putString("gateKeyMode", v.key).apply()

    /** All user-created timed text tasks (1..3 min) available for the app gate. */
    var gateTasks: List<GateTask>
        get() = GateTaskCodec.decode(prefs.getString("gateTasks", "").orEmpty())
        set(v) = prefs.edit().putString("gateTasks", GateTaskCodec.encode(v)).apply()

    /** IDs of custom [GateTask]s currently placed in the active gate rotation field. */
    var gateActiveTaskIds: Set<String>
        get() = prefs.getStringSet("gateActiveTaskIds", null)?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet("gateActiveTaskIds", v.toSet()).apply()

    /** Whether ear-training is in the active gate field (default true; fallback if no tasks are active). */
    var gateIncludeQuiz: Boolean
        get() = prefs.getBoolean("gateIncludeQuiz", true)
        set(v) = prefs.edit().putBoolean("gateIncludeQuiz", v).apply()

    /** Last shown gate challenge ID so consecutive app starts switch between active entries. */
    var lastGateChallengeId: String?
        get() = prefs.getString("lastGateChallengeId", null)
        set(v) = prefs.edit().putString("lastGateChallengeId", v).apply()

    /** Returns only those custom tasks currently placed in the active gate field. */
    fun activeGateTasks(): List<GateTask> {
        val activeIds = gateActiveTaskIds
        return gateTasks.filter { it.id in activeIds }
    }

    /** When an unlocked app locks again (default: timer for [unlockMinutes]). */
    var gateRelockMode: GateRelockMode
        get() = GateRelockMode.fromKey(prefs.getString("gateRelockMode", null))
        set(v) = prefs.edit().putString("gateRelockMode", v.key).apply()

    /** How long an app stays open after a passed challenge (used in [GateRelockMode.TIMER]). */
    var unlockMinutes: Int
        get() = prefs.getInt("unlockMinutes", 10)
        set(v) = prefs.edit().putInt("unlockMinutes", v).apply()

    fun unlock(pkg: String) {
        val now = System.currentTimeMillis()
        val until = when (gateRelockMode) {
            GateRelockMode.TIMER -> now + unlockMinutes * 60_000L
            GateRelockMode.ON_SCREEN_OFF, GateRelockMode.ON_APP_LEAVE -> Long.MAX_VALUE
        }
        prefs.edit()
            .putLong("unlock:$pkg", until)
            .putLong("unlockedAt:$pkg", now)
            .apply()
    }

    /** True shortly after [unlock] so closing gate/practice window transitions do not relock prematurely. */
    fun wasRecentlyUnlocked(pkg: String, graceMs: Long = 2_000L): Boolean {
        val unlockedAt = prefs.getLong("unlockedAt:$pkg", 0L)
        return unlockedAt > 0L && (System.currentTimeMillis() - unlockedAt) < graceMs
    }

    fun relock(pkg: String) {
        prefs.edit().remove("unlock:$pkg").remove("unlockedAt:$pkg").apply()
    }

    fun clearAllUnlocks() {
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith("unlock:") || it.startsWith("unlockedAt:") }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    fun isUnlocked(pkg: String): Boolean =
        System.currentTimeMillis() < prefs.getLong("unlock:$pkg", 0)
}
