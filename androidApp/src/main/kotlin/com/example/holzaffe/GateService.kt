package com.example.holzaffe

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager

/**
 * Watches which app comes to the foreground and puts the challenge in front of blocked ones.
 * Also handles re-locking on screen-off ([GateRelockMode.ON_SCREEN_OFF]) and on leaving an
 * unlocked app ([GateRelockMode.ON_APP_LEAVE]).
 */
class GateService : AccessibilityService() {
    // Lazy init avoids any race if an accessibility event arrives before onServiceConnected completes.
    private val store by lazy { Store(this) }

    /** Currently foregrounded unlocked blocked app (tracked for [GateRelockMode.ON_APP_LEAVE]). */
    private var activeUnlockedPkg: String? = null

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_OFF) return
            if (store.gateRelockMode != GateRelockMode.TIMER) {
                store.clearAllUnlocks()
                activeUnlockedPkg = null
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOffReceiver) }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        // Ignore transient system overlays & soft keyboards so typing doesn't count as leaving the app.
        if (isTransientSystemPackage(pkg)) return

        // In ON_APP_LEAVE mode, switching to any other app (including Home) immediately revokes the unlock.
        val prev = activeUnlockedPkg
        if (prev != null && pkg != prev) {
            if (store.gateRelockMode == GateRelockMode.ON_APP_LEAVE) {
                store.relock(prev)
            }
            activeUnlockedPkg = null
        }

        if (pkg !in store.blocked) return
        if (store.isUnlocked(pkg)) {
            activeUnlockedPkg = pkg
            return
        }

        startActivity(
            Intent(this, GateActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(GateActivity.EXTRA_PACKAGE, pkg)
        )
    }

    private fun isTransientSystemPackage(pkg: String): Boolean {
        if (pkg == "com.android.systemui" || pkg == "android") return true
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return false
        return imm.enabledInputMethodList.any { it.packageName == pkg }
    }

    override fun onInterrupt() {}
}
