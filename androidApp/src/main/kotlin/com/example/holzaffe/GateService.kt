package com.example.holzaffe

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/** Watches which app comes to the foreground and puts the challenge in front of blocked ones. */
class GateService : AccessibilityService() {
    private lateinit var store: Store

    override fun onServiceConnected() {
        store = Store(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg !in store.blocked || store.isUnlocked(pkg)) return
        startActivity(
            Intent(this, GateActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(GateActivity.EXTRA_PACKAGE, pkg)
        )
    }

    override fun onInterrupt() {}
}
