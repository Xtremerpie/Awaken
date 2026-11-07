package com.awaken.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.awaken.app.ui.nav.AwakenRoot
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
    }

    private var openPlayer by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openPlayer = intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true
        val container = (application as AwakenApp).container
        setContent {
            AwakenRoot(container, openPlayerRequested = openPlayer, onOpenPlayerHandled = { openPlayer = false })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) openPlayer = true
    }

    override fun onStart() {
        super.onStart()
        // Cheap safety net: re-arm alarms whenever the app is opened (covers permission changes made in Settings).
        val container = (application as AwakenApp).container
        container.appScope.launch { runCatching { if (container.ready.value) container.alarmScheduler.rescheduleAll() } }
    }
}
