package com.shibaken.androidterminal

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.shibaken.androidterminal.ui.AppTheme
import com.shibaken.androidterminal.ui.ExtScriptsScreen
import com.shibaken.androidterminal.ui.HostEditScreen
import com.shibaken.androidterminal.ui.HostListScreen
import com.shibaken.androidterminal.ui.KeysScreen
import com.shibaken.androidterminal.ui.PromptHost
import com.shibaken.androidterminal.ui.TailscaleScreen
import com.shibaken.androidterminal.ui.TerminalScreen

class MainActivity : FragmentActivity() {

    /** (sessionId, paneId) from a notification tap, consumed by the nav graph. */
    private var pendingOpen by mutableStateOf<Pair<String, String?>?>(null)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        handleIntent(intent)

        val app = application as App
        setContent {
            AppTheme {
                val nav = rememberNavController()
                PromptHost(this)

                LaunchedEffect(pendingOpen) {
                    val (sessionId, paneId) = pendingOpen ?: return@LaunchedEffect
                    pendingOpen = null
                    val session = app.sessions.get(sessionId) ?: return@LaunchedEffect
                    paneId?.let { session.herdr?.focusPane(it) }
                    nav.navigate("terminal/$sessionId") { launchSingleTop = true }
                }

                NavHost(navController = nav, startDestination = "hosts") {
                    composable("hosts") {
                        HostListScreen(
                            onConnect = { profile ->
                                val s = app.sessions.open(profile)
                                nav.navigate("terminal/${s.id}")
                            },
                            onOpenSession = { id -> nav.navigate("terminal/$id") },
                            onEdit = { id -> nav.navigate("host/${id ?: "new"}") },
                            onKeys = { nav.navigate("keys") },
                            onTailscale = { nav.navigate("tailscale") },
                            onExtScripts = { nav.navigate("ext") },
                        )
                    }
                    composable("host/{id}") { entry ->
                        HostEditScreen(
                            hostId = entry.arguments?.getString("id")?.takeIf { it != "new" },
                            onDone = { nav.popBackStack() },
                            onManageKeys = { nav.navigate("keys") },
                        )
                    }
                    composable("keys") { KeysScreen(onBack = { nav.popBackStack() }) }
                    composable("ext") { ExtScriptsScreen(onBack = { nav.popBackStack() }) }
                    composable("tailscale") { TailscaleScreen(onBack = { nav.popBackStack() }) }
                    composable("terminal/{sessionId}") { entry ->
                        TerminalScreen(
                            sessionId = entry.arguments?.getString("sessionId") ?: "",
                            onBack = { nav.popBackStack("hosts", inclusive = false) },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID) ?: return
        pendingOpen = sessionId to intent.getStringExtra(EXTRA_PANE_ID)
    }

    override fun onStart() {
        super.onStart()
        (application as App).sessions.uiVisible = true
    }

    override fun onStop() {
        super.onStop()
        (application as App).sessions.uiVisible = false
    }

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_PANE_ID = "pane_id"
    }
}
