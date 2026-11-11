package com.example.holzaffe

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shown in front of a blocked app: N correct answers open it for a while. */
class GateActivity : ComponentActivity() {
    private var target by mutableStateOf("")
    private var solved by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        target = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val store = Store(this)
        val required = store.required

        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize()) {
                    BackHandler { goHome() }
                    Column(
                        Modifier.safeDrawingPadding().padding(24.dp).fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Erst hören, dann ${appLabel(target)}", fontSize = 22.sp)
                        Text("$solved / $required", fontSize = 56.sp, color = MaterialTheme.colorScheme.primary)
                        LinearProgressIndicator(
                            progress = { solved.toFloat() / required },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.weight(1f))
                        QuizPanel(
                            active = solved < required,
                            onAnswer = { ok ->
                                if (ok && ++solved >= required) {
                                    store.unlock(target)
                                    open(target)
                                }
                            },
                        )
                        TextButton(onClick = ::goHome) { Text("Doch nicht öffnen") }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val pkg = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        if (pkg != target) {
            target = pkg
            solved = 0
        }
    }

    override fun onStop() {
        super.onStop()
        ChordPlayer.stop()
        // Leaving mid-challenge forfeits the progress.
        if (!isChangingConfigurations) finish()
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun open(pkg: String) {
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
        finish()
    }

    /** Back must not fall through to the blocked app underneath. */
    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    companion object {
        const val EXTRA_PACKAGE = "package"
    }
}
