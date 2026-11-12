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

    var blocked: Set<String>
        get() = prefs.getStringSet("blocked", null) ?: setOf("com.whatsapp")
        set(v) = prefs.edit().putStringSet("blocked", v).apply()

    /** Correct answers needed before a blocked app opens. */
    var required: Int
        get() = prefs.getInt("required", 5)
        set(v) = prefs.edit().putInt("required", v).apply()

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
