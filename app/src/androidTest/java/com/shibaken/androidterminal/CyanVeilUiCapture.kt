package com.shibaken.androidterminal

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.shibaken.androidterminal.data.AppearanceSettings
import com.shibaken.androidterminal.data.HostProfile
import com.shibaken.androidterminal.data.AuthMethod
import com.shibaken.androidterminal.herdr.*
import com.shibaken.androidterminal.session.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** Optional emulator-only visual fixture. It never opens a network connection or ships in the APK. */
internal class CyanVeilUiCapture(private val instrumentation: Instrumentation) {
    private val app = instrumentation.targetContext.applicationContext as App
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val terminals = mutableListOf<PaneTerminal>()
    private lateinit var session: SshTerminalSession
    private lateinit var extensions: SessionExt
    private val scriptName = "cyan_veil_ui_capture.lua"
    private val scriptSource = """
        -- Cyan Veil / UI verification
        command("UI 動作確認", function()
          log("CYAN_UI_COMMAND")
        end)

        keys.add("確認", function()
          log("CYAN_UI_KEY")
        end)
    """.trimIndent()

    fun verifyAndCapture() {
        val appearance = app.appearance.settings.value
        val sessions = app.sessions.sessions.value
        val previousScript = app.scriptStore.readGlobal(scriptName)
        val host = HostProfile(name = "Mac mini", host = "mac-mini.local", username = "shibaken",
            authMethod = AuthMethod.PASSWORD, extEnabled = true, extLoadFromHost = true)
        val server = HostProfile(name = "Development", host = "dev.example.test", username = "dev", useTailscale = false)
        var activity: MainActivity? = null
        try {
            instrumentation.runOnMainSync {
                app.appearance.save(AppearanceSettings())
                app.scriptStore.deleteGlobal(scriptName)
                app.hosts.save(host); app.hosts.save(server)
                session = SshTerminalSession(app, host, AgentAlertListener { _, _, _ -> })
                extensions = SessionExt(session, app, scope)
                setFlow(session, "_ext", extensions)
                setFlow(session, "_state", ConnState.Connected)
                val tabs = listOf("androidTerminal", "infra", "notes").mapIndexed { i, label -> TabInfo("t$i", "w1", label = label) }
                val panes = tabs.mapIndexed { i, tab -> PaneInfo("p$i", "w1", tab.tabId,
                    label = listOf("Codex", "deploy", "shell")[i],
                    agentStatus = listOf(AgentStatus.BLOCKED, AgentStatus.WORKING, AgentStatus.IDLE)[i]) }
                val state = HerdrState(phase = HerdrState.Phase.READY,
                    workspaces = listOf(WorkspaceInfo("w1", label = "Development")), tabs = tabs, panes = panes,
                    focusedWorkspaceId = "w1", focusedTabId = "t0", focusedPaneId = "p0")
                setFlow(session.herdr!!, "_state", state)
                val backends = panes.associate { pane ->
                    pane.paneId to PaneTerminal(app, scope).also { term ->
                        terminals += term
                        setFlow(term, "_attached", true)
                        val content = "\u001b[36m❯ ~/workspace/androidTerminal\u001b[0m\r\n\r\n" +
                            "\u001b[32m✓ Connected to Mac mini\u001b[0m\r\n\r\n" +
                            "❯ git status --short\r\n" +
                            " M app/ui/Theme.kt\r\n M app/ui/TerminalScreen.kt\r\n\r\n" +
                            "\u001b[36mCodex\u001b[0m\r\nUI update is ready.\r\n\r\n" +
                            "\u001b[33mApply these changes?\u001b[0m\r\n1. Yes, continue\r\n2. Review the diff\r\n\r\n❯ "
                        val bytes = content.toByteArray()
                        term.emulator.append(bytes, bytes.size)
                    }
                }
                setFlow(session, "_panes", backends)
                setFlow(app.sessions, "_sessions", sessions + session)
            }
            activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            settle()
            capture("hosts-populated")
            click("セッションを開く")
            awaitNode { it.contentDescription?.startsWith("タブ一覧") == true }
            capture("terminal")
            click("タブ一覧", prefix = true)
            awaitNode { it.text?.toString() == "タブとペイン" }
            capture("overview")
            var card = awaitNode { it.contentDescription?.startsWith("androidTerminal、") == true }
            while (!card.isLongClickable) card = checkNotNull(card.parent)
            check(card.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
            settle()
            click("androidTerminalを閉じる")
            awaitNode { it.text?.toString() == "タブを閉じる" }
            click("キャンセル")
            awaitNode { it.text?.toString() == "タブとペイン" }
            click("一覧を閉じる")
            click("キーボードの表示/非表示")
            capture("terminal-keyboard")
            click("キーボードの表示/非表示")
            verifyExtensions(host)
        } catch (error: Throwable) {
            capture("failure")
            val tree = StringBuilder()
            fun record(node: AccessibilityNodeInfo?, depth: Int = 0) {
                node ?: return
                tree.appendLine("${"  ".repeat(depth)}text=${node.text} description=${node.contentDescription} clickable=${node.isClickable}")
                for (i in 0 until node.childCount) record(node.getChild(i), depth + 1)
            }
            record(instrumentation.uiAutomation.rootInActiveWindow)
            File(app.filesDir, "cyan-veil-captures/failure.txt").writeText(tree.toString())
            throw error
        } finally {
            instrumentation.runOnMainSync {
                activity?.finish()
                if (::session.isInitialized) session.dispose()
                terminals.forEach { it.close() }
                setFlow(app.sessions, "_sessions", sessions)
                app.hosts.delete(host.id); app.hosts.delete(server.id)
                app.appearance.save(appearance)
                if (previousScript == null) app.scriptStore.deleteGlobal(scriptName)
                else app.scriptStore.writeGlobal(scriptName, previousScript)
                scope.cancel()
            }
        }
    }

    private fun verifyExtensions(host: HostProfile) {
        click("ホスト一覧")
        scrollTo("拡張スクリプト")
        click("拡張スクリプト")
        awaitNode { it.text?.toString() == "共通スクリプト" }
        capture("extensions-empty")
        click("スクリプトを追加")
        setEditableText(scriptName)
        click("作成")
        awaitNode { it.text?.toString() == "スクリプトを編集" }
        setEditableText(scriptSource)
        capture("extension-editor")
        check(awaitNode { it.isEditable }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        settle()
        capture("extension-editor-keyboard")
        click("保存して反映")
        awaitNode { it.text?.toString() == scriptName }
        check(app.scriptStore.readGlobal(scriptName) == scriptSource)
        capture("extensions-list")
        click(scriptName)
        setEditableText(scriptSource + "\n-- unsaved draft")
        click("戻る")
        check(app.scriptStore.readGlobal(scriptName) == scriptSource)
        click("$scriptName を削除")
        awaitNode { it.text?.toString() == "スクリプトを削除" }
        click("キャンセル")
        check(app.scriptStore.readGlobal(scriptName) == scriptSource)
        click("戻る")

        awaitCondition { extensions.engine.registry.value.commands.any { it.name == "UI 動作確認" } }
        click("セッションを開く")
        click("メニュー")
        click("拡張コマンド")
        awaitNode { it.text?.toString() == "UI 動作確認" }
        capture("extension-commands")
        click("UI 動作確認")
        awaitCondition { extensions.log.value.any { "CYAN_UI_COMMAND" in it } }
        repeat(4) {
            if (find { it.contentDescription?.toString() == "拡張キー: 確認" } == null) {
                awaitNode { it.contentDescription?.toString() == "補助キー" }.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                settle()
            }
        }
        click("拡張キー: 確認")
        awaitCondition { extensions.log.value.any { "CYAN_UI_KEY" in it } }
        capture("extension-keys")

        click("ホスト一覧")
        click("${host.name}を編集")
        scrollTo("このホストで有効にする")
        awaitNode { it.isScrollable }.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        settle()
        capture("host-extensions")
        click("このホストで有効にする", prefix = true)
        click("保存")
        check(app.hosts.get(host.id)?.extEnabled == false)
        check(app.hosts.get(host.id)?.extLoadFromHost == false)

        scrollTo("拡張スクリプト")
        click("拡張スクリプト")
        click("$scriptName を削除")
        click("削除")
        awaitNode { it.text?.toString() == "共通スクリプト" }
        check(app.scriptStore.readGlobal(scriptName) == null)
    }

    private fun setEditableText(text: String) {
        val field = awaitNode { it.isEditable }
        check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }))
        settle()
    }

    private fun scrollTo(label: String) {
        fun matches(node: AccessibilityNodeInfo) = node.text?.toString()?.startsWith(label) == true ||
            node.contentDescription?.toString() == label
        repeat(8) {
            if (find(::matches) != null) return
            // A destination that fits on screen may expose its target after navigation
            // without ever exposing a scrollable node. Wait for either state.
            val node = awaitNode { matches(it) || it.isScrollable }
            if (matches(node) || find(::matches) != null) return
            check(node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            settle()
        }
        error("Cannot reveal $label")
    }

    private fun awaitCondition(predicate: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 10_000
        while (!predicate()) {
            check(android.os.SystemClock.uptimeMillis() < deadline) { "Extension operation did not complete" }
            android.os.SystemClock.sleep(100)
        }
    }

    private fun settle() {
        instrumentation.waitForIdleSync()
        // Compose schedules recomposition and rendering on subsequent Choreographer frames.
        // Accessibility idle alone can return before that frame has reached the screen.
        android.os.SystemClock.sleep(500)
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
    }

    private fun click(label: String, prefix: Boolean = false) {
        val node = awaitNode { n ->
            listOf(n.text?.toString(), n.contentDescription?.toString()).any { it != null && if (prefix) it.startsWith(label) else it == label }
        }
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Cannot click: $label" }
        settle()
    }

    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = android.os.SystemClock.uptimeMillis() + 10_000
        do {
            find(predicate)?.let { return it }
            android.os.SystemClock.sleep(200)
        } while (android.os.SystemClock.uptimeMillis() < deadline)
        error("Expected UI state did not become accessible")
    }

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (predicate(node)) return node
            for (i in 0 until node.childCount) visit(node.getChild(i))?.let { return it }
            return null
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun capture(name: String) {
        settle()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(app.filesDir, "cyan-veil-captures").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    /** Fixture injection is confined to androidTest; production state remains private. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> setFlow(owner: Any, field: String, value: T) {
        val state = owner.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(owner) as MutableStateFlow<T>
        state.value = value
    }
}
