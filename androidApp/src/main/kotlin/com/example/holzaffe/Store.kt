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

    var gateMode: GameMode
        get() = GameMode.fromKey(prefs.getString("gateMode", null))
        set(v) = prefs.edit().putString("gateMode", v.key).apply()

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

    /** How long an app stays open after a passed challenge. */
    var unlockMinutes: Int
        get() = prefs.getInt("unlockMinutes", 10)
        set(v) = prefs.edit().putInt("unlockMinutes", v).apply()

    fun unlock(pkg: String) {
        prefs.edit()
            .putLong("unlock:$pkg", System.currentTimeMillis() + unlockMinutes * 60_000L)
            .apply()
    }

    fun isUnlocked(pkg: String): Boolean =
        System.currentTimeMillis() < prefs.getLong("unlock:$pkg", 0)
}
