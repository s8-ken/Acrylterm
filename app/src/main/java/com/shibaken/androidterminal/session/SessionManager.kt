package com.shibaken.androidterminal.session

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.shibaken.androidterminal.App
import com.shibaken.androidterminal.MainActivity
import com.shibaken.androidterminal.R
import com.shibaken.androidterminal.data.HostProfile
import com.shibaken.androidterminal.herdr.AgentAlertListener
import com.shibaken.androidterminal.herdr.AgentStatus
import com.shibaken.androidterminal.herdr.PaneInfo
import com.shibaken.androidterminal.herdr.WorkspaceInfo
import com.shibaken.androidterminal.service.ConnectionService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns all open terminal sessions for the lifetime of the process. Main thread only. */
class SessionManager(private val app: App) {
    private val _sessions = MutableStateFlow<List<SshTerminalSession>>(emptyList())
    val sessions: StateFlow<List<SshTerminalSession>> = _sessions.asStateFlow()

    /** Set by MainActivity: whether the UI is visible and which session it shows. */
    @Volatile var uiVisible: Boolean = false
        set(value) {
            field = value
            // Detach pane channels while the UI is in the background, reattach on return.
            _sessions.value.forEach { it.setForeground(value) }
        }
    @Volatile var visibleSessionId: String? = null

    fun get(id: String?): SshTerminalSession? = _sessions.value.firstOrNull { it.id == id }

    fun open(profile: HostProfile): SshTerminalSession {
        lateinit var session: SshTerminalSession
        val alerts = AgentAlertListener { pane, ws, status -> notifyAgent(session, pane, ws, status) }
        session = SshTerminalSession(app, profile, alerts)
        _sessions.value = _sessions.value + session
        ConnectionService.update(app, _sessions.value.size)
        session.connect()
        return session
    }

    fun close(id: String) {
        val s = get(id) ?: return
        s.dispose()
        _sessions.value = _sessions.value.filterNot { it.id == id }
        ConnectionService.update(app, _sessions.value.size)
    }

    fun closeAll() {
        _sessions.value.forEach { it.dispose() }
        _sessions.value = emptyList()
        ConnectionService.update(app, 0)
    }

    private fun notifyAgent(session: SshTerminalSession, pane: PaneInfo, ws: WorkspaceInfo?, status: String) {
        val herdrState = session.herdr?.state?.value
        val lookingAtIt = uiVisible && visibleSessionId == session.id && herdrState?.focusedPaneId == pane.paneId
        if (lookingAtIt) return
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return

        val who = pane.agentName ?: pane.title2
        val where = listOfNotNull(ws?.title, session.profile.displayName).joinToString(" · ")
        val text = when (status) {
            AgentStatus.BLOCKED -> "$who が入力を待っています" + (pane.stateLabels[status]?.let { "($it)" } ?: "")
            else -> "$who の作業が完了しました"
        }
        post(session, text, where, pane.paneId, status == AgentStatus.BLOCKED)
    }

    /** General notification (used by extensions). Tapping it opens [session] (and [paneId] if given). */
    fun notify(session: SshTerminalSession, title: String, body: String, paneId: String?) {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        post(session, title, body, paneId, false)
    }

    private fun post(session: SshTerminalSession, title: String, body: String, paneId: String?, high: Boolean) {
        val key = (session.id + (paneId ?: "")).hashCode()
        val intent = Intent(app, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_SESSION_ID, session.id)
            paneId?.let { putExtra(MainActivity.EXTRA_PANE_ID, it) }
        }
        val pi = PendingIntent.getActivity(app, key, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(app, App.CHANNEL_AGENT)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (high) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(app).notify(key, n)
        } catch (_: SecurityException) {
        }
    }

    /** Re-evaluates the scripts of every live session that has extensions on (after a global script was saved). */
    fun reloadExtensions() {
        _sessions.value.forEach { it.reloadExtensions() }
    }
}
