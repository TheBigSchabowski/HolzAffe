package com.example.holzaffe

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.TextView

/**
 * Watches which app comes to the foreground and puts the challenge in front of blocked ones.
 * Also handles:
 * - Re-locking on screen-off ([GateRelockMode.ON_SCREEN_OFF]) and on leaving an unlocked app ([GateRelockMode.ON_APP_LEAVE])
 * - Redirect sessions ([startRedirect]): shows a non-touchable floating countdown pill over a
 *   practice app (e.g. Widder) for 1..3 min, then vibrates and opens the originally requested app.
 */
class GateService : AccessibilityService() {
    // Lazy init avoids any race if an accessibility event arrives before onServiceConnected completes.
    private val store by lazy { Store(this) }

    /** Currently foregrounded unlocked blocked app (tracked for [GateRelockMode.ON_APP_LEAVE]). */
    private var activeUnlockedPkg: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var redirectOverlay: TextView? = null
    private var activeRedirect: RedirectSession? = null

    private data class RedirectSession(
        val blockedPkg: String,
        val blockedLabel: String,
        val practicePkg: String,
        val endElapsedMs: Long,
    )

    private val redirectTick = object : Runnable {
        override fun run() {
            val session = activeRedirect ?: return
            val remainingMs = session.endElapsedMs - SystemClock.elapsedRealtime()
            if (remainingMs <= 0L) {
                finishRedirect(session)
                return
            }
            val secs = ((remainingMs + 999L) / 1000L).toInt().coerceAtLeast(0)
            val mmss = "${secs / 60}:${(secs % 60).toString().padStart(2, '0')}"
            redirectOverlay?.text = "⏱ $mmss · danach ${session.blockedLabel}"
            mainHandler.postDelayed(this, 500L)
        }
    }

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
        instance = this
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onDestroy() {
        cancelRedirect()
        if (instance === this) instance = null
        runCatching { unregisterReceiver(screenOffReceiver) }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        // Ignore transient system overlays & soft keyboards so typing doesn't count as leaving the app.
        if (isTransientSystemPackage(pkg)) return

        // If a timed redirect to a practice app is active, bounce early attempts on the blocked app back to practice.
        val redirect = activeRedirect
        if (redirect != null && pkg == redirect.blockedPkg) {
            if (redirect.practicePkg.isNotEmpty()) {
                packageManager.getLaunchIntentForPackage(redirect.practicePkg)?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(it)
                }
            }
            return
        }

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

    private fun beginRedirect(blockedPkg: String, practicePkg: String, durationMs: Long) {
        mainHandler.post {
            cancelRedirect()
            val blockedLabel = runCatching {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(blockedPkg, 0)).toString()
            }.getOrDefault(blockedPkg)
            activeRedirect = RedirectSession(
                blockedPkg = blockedPkg,
                blockedLabel = blockedLabel,
                practicePkg = practicePkg,
                endElapsedMs = SystemClock.elapsedRealtime() + durationMs.coerceAtLeast(1_000L),
            )
            showOverlayPill()
            mainHandler.post(redirectTick)
        }
    }

    private fun finishRedirect(session: RedirectSession) {
        cancelRedirect()
        vibrateShort()
        store.unlock(session.blockedPkg)
        packageManager.getLaunchIntentForPackage(session.blockedPkg)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(it)
        }
    }

    private fun cancelRedirect() {
        mainHandler.removeCallbacks(redirectTick)
        activeRedirect = null
        redirectOverlay?.let { view ->
            runCatching { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view) }
        }
        redirectOverlay = null
    }

    /** Shows a small non-touchable countdown pill at the top of the screen while practicing in another app. */
    private fun showOverlayPill() {
        if (!Settings.canDrawOverlays(this)) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val dp = resources.displayMetrics.density
        val pill = TextView(this).apply {
            setTextColor(Color.parseColor("#FFE4A72E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding((14 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E614172B"))
                cornerRadius = 20 * dp
                setStroke((1.5f * dp).toInt(), Color.parseColor("#FFE4A72E"))
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (44 * dp).toInt()
        }
        runCatching {
            wm.addView(pill, params)
            redirectOverlay = pill
        }
    }

    private fun vibrateShort() {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.vibrate(VibrationEffect.createOneShot(180L, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun isTransientSystemPackage(pkg: String): Boolean {
        if (pkg == "com.android.systemui" || pkg == "android") return true
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return false
        return imm.enabledInputMethodList.any { it.packageName == pkg }
    }

    override fun onInterrupt() {}

    companion object {
        @Volatile
        private var instance: GateService? = null

        /** Starts a timed practice redirect: runs [durationMs] while user practices in [practicePkg], then opens [blockedPkg]. */
        fun startRedirect(blockedPkg: String, practicePkg: String, durationMs: Long): Boolean {
            val svc = instance ?: return false
            svc.beginRedirect(blockedPkg, practicePkg, durationMs)
            return true
        }
    }
}
