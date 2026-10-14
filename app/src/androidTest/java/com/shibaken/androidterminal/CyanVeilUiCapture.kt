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

    fun verifyAndCapture(sessionsOnly: Boolean = false) {
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
                val tabs = listOf("dev", "review", "logs").mapIndexed { i, label -> TabInfo("t$i", "w1", label = label) }
                val panes = tabs.mapIndexed { i, tab -> PaneInfo("p$i", "w1", tab.tabId,
                    label = listOf("Codex", "deploy", "shell")[i],
                    agentStatus = listOf(AgentStatus.WORKING, AgentStatus.BLOCKED, AgentStatus.IDLE)[i]) }
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
            verifyTopTargets()
            verifySessionPicker()
            verifyFullscreen(activity)
            verifyTerminalMenu()
            verifyMissingWorkspacePrompt()
            val originalTabs = session.herdr!!.state.value
            val longTitle = "androidTerminal-with-a-very-long-title"
            instrumentation.runOnMainSync { setFlow(session.herdr!!, "_state", originalTabs.copy(tabs = originalTabs.tabs.map { if (it.tabId == "t0") it.copy(label = longTitle) else it })) }
            settle()
            verifyTopTargets(longTitle)
            capture("terminal-long-title")
            instrumentation.runOnMainSync { setFlow(session.herdr!!, "_state", originalTabs) }
            settle()
            click("devを閉じる")
            awaitNode { it.text?.toString() == "元に戻す" }
            check(session.herdr!!.state.value.tabs.size == 3) { "Undo must keep the raw remote tabs alive" }
            check(session.panes.value.size == 3) { "Undo must keep pane backends alive" }
            click("元に戻す")
            check(session.herdr!!.pendingTabCloses.value.isEmpty())
            click("タブ一覧", prefix = true)
            awaitNode { it.text?.toString() == "3 個のタブ" }
            capture("overview")
            verifyOverviewActions()
            click("logsのプレビューを拡大")
            awaitNode { it.text?.toString() == "このタブを開く" }
            check(session.herdr!!.state.value.focusedTabId == "t0") { "Preview must not change focus" }
            capture("expanded-preview")
            click("プレビューを閉じる")
            swipeCard("review")
            awaitNode { it.text?.toString() == "2 個のタブ" }
            capture("overview-undo")
            click("元に戻す")
            awaitNode { it.text?.toString() == "3 個のタブ" }
            instrumentation.runOnMainSync { (0..2).forEach { session.herdr!!.closeTabWithUndo("t$it") } }
            awaitNode { it.text?.toString() == "0 個のタブ" }
            check(session.herdr!!.state.value.tabs.size == 3 && session.panes.value.size == 3)
            capture("overview-all-closed")
            instrumentation.runOnMainSync { (0..2).forEach { session.herdr!!.undoCloseTab("t$it") } }
            awaitNode { it.text?.toString() == "3 個のタブ" }
            click("一覧を閉じる")
            click("メニュー")
            click("ワークスペース一覧")
            awaitNode { it.text?.toString() == "Development" }
            capture("workspaces")
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            settle()
            click("キーボードの表示/非表示")
            capture("terminal-keyboard")
            click("キーボードの表示/非表示")
            if (!sessionsOnly) verifyExtensions(host)
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

    private fun verifySessionPicker() {
        awaitNode { it.contentDescription?.toString() == "herdr セッションを選択、Mac mini / android" }
        capture("session-header")
        click("herdr セッションを選択、", prefix = true)
        awaitNode { it.text?.toString() == "herdr セッション" }
        // This fixture stays offline. Inject discovery states to verify the production UI.
        instrumentation.runOnMainSync { setFlow(session, "_sessionPicker", HerdrSessionPickerState(loading = true)) }
        awaitNode { it.text?.toString() == "セッション一覧を取得中…" }
        capture("session-loading")
        val longName = "Work-session-with-a-very-long-exact-name-0123456789"
        val entries = listOf(HerdrSessionInfo("android", "/config/sessions/android/herdr.sock", true),
            HerdrSessionInfo("default", "/config/herdr.sock", true),
            HerdrSessionInfo(longName, "/config/sessions/long/herdr.sock", true),
            HerdrSessionInfo("Stopped", "/config/sessions/Stopped/herdr.sock", false))
        instrumentation.runOnMainSync {
            setFlow(session, "_connectedHerdrSession", "android")
            setFlow(session, "_sessionPicker", HerdrSessionPickerState(sessions = entries))
        }
        awaitNode { it.contentDescription?.toString() == "herdr セッション android、稼働中、このアプリで接続中" }
        awaitNode { it.contentDescription?.toString() == "herdr セッション Stopped、停止中" }
        capture("session-picker")
        click("閉じる")
        check(session.selectedHerdrSession.value == "android") { "Dismiss must preserve selection" }
        click("herdr セッションを選択、", prefix = true)
        instrumentation.runOnMainSync { setFlow(session, "_sessionPicker", HerdrSessionPickerState(error = "一覧を取得できませんでした")) }
        awaitNode { it.text?.toString() == "再試行" }
        capture("session-error")
        click("再試行")
        awaitNode { it.text?.toString()?.startsWith("SSH に接続されていません") == true }
        check(session.selectedHerdrSession.value == "android")
        click("閉じる")
        // Same pane/tab ids are valid in separate herdr sessions. Header must follow session identity,
        // and workspace filtering must not reintroduce active-workspace emphasis.
        instrumentation.runOnMainSync { setFlow(session, "_selectedHerdrSession", longName); setFlow(session, "_connectedHerdrSession", longName) }
        awaitNode { it.contentDescription?.toString() == "herdr セッションを選択、Mac mini / $longName" }
        verifyTopTargets()
        capture("session-header-long")
        click("タブ一覧", prefix = true)
        awaitNode { it.contentDescription?.toString() == "Development" }
        check(find { it.contentDescription?.endsWith("、表示中") == true } == null)
        capture("workspace-filter-without-marker")
        click("一覧を閉じる")
        instrumentation.runOnMainSync { setFlow(session, "_selectedHerdrSession", "android"); setFlow(session, "_connectedHerdrSession", "android") }
        awaitNode { it.contentDescription?.toString() == "herdr セッションを選択、Mac mini / android" }
        settle()
    }

    private fun verifyTerminalMenu() {
        click("メニュー")
        check(find { it.text?.toString() == "メッセージを作成" } == null)
        check(find { it.text?.toString()?.contains("直接入力") == true } == null)
        check(find { it.text?.toString() == "セッションを閉じる" } == null)
        awaitNode { it.text?.toString() == "切断" }
        capture("terminal-menu")
        click("切断")
        awaitNode { it.text?.toString() == "接続を閉じますか？" }
        capture("close-connection-confirm")
        click("キャンセル")
        check(app.sessions.get(session.id) === session && session.state.value is ConnState.Connected)
        awaitNode { it.text?.toString() == "日本語で返信を作成…" }
    }

    private fun verifyMissingWorkspacePrompt() {
        val controller = session.herdr!!
        val before = controller.state.value
        instrumentation.runOnMainSync { setFlow(controller, "_state", before.copy(workspaces = emptyList(), tabs = emptyList(),
            panes = emptyList(), layouts = emptyMap(), focusedWorkspaceId = null, focusedTabId = null, focusedPaneId = null)) }
        settle()
        click("新しいタブ")
        awaitNode { it.text?.toString() == "アクティブなワークスペースがありません" }
        capture("missing-workspace")
        click("キャンセル")
        check(!controller.workspaceRequired.value && controller.state.value.workspaces.isEmpty())
        click("新しいタブ")
        click("作成") // Offline fixture: request path fails safely without making a network connection.
        awaitCondition { !controller.workspaceRequired.value }
        check(controller.state.value.workspaces.isEmpty())
        instrumentation.runOnMainSync { setFlow(controller, "_state", before); setFlow(controller, "_workspaceRequired", true) }
        awaitNode { it.text?.toString() == "アクティブなワークスペースがありません" }
        click("キャンセル")
        check(controller.state.value == before)
    }

    private fun verifyOverviewActions() {
        val controller = session.herdr!!
        val before = controller.state.value
        click("タブ一覧メニュー")
        awaitNode { it.text?.toString() == "新しいワークスペース" }
        check(find { it.text?.toString() == "外観設定" || it.text?.toString() == "貼り付け" } == null)
        capture("overview-menu")
        click("新しいワークスペース")
        awaitNode { it.text?.toString() == "最初のタブを含むワークスペースを作成します。" }
        capture("new-workspace")
        click("キャンセル")
        check(controller.state.value == before)
        for (label in listOf("すべてのタブを閉じる", "すべてのワークスペースを閉じる")) {
            click("タブ一覧メニュー")
            click(label)
            awaitNode { it.text?.toString()?.contains("表示フィルタにかかわらず全ワークスペースが対象") == true }
            awaitNode { it.text?.toString() == "すべて閉じる" }
            capture(if (label == "すべてのタブを閉じる") "close-all-tabs-confirm" else "close-all-workspaces-confirm")
            click("キャンセル")
            check(controller.state.value == before && controller.pendingTabCloses.value.isEmpty())
        }
        // Long partial errors scroll inside the dialog and never push its OK action off-screen.
        val plan = before.bulkClosePlan(BulkCloseKind.TABS).copy(ids = (1..5).map { "failed-$it" })
        val failure = "終了に失敗しました。".repeat(12)
        instrumentation.runOnMainSync { setFlow(controller, "_bulkCloseResult", BulkCloseResult(plan, 0, plan.ids.map { BulkCloseFailure(it, failure) })) }
        awaitNode { it.text?.toString() == "一部を閉じられませんでした" }
        val ok = awaitNode { it.text?.toString() == "OK" }
        val bounds = android.graphics.Rect().also { ok.getBoundsInScreen(it) }
        check(bounds.bottom <= app.resources.displayMetrics.heightPixels)
        capture("bulk-close-errors")
        click("OK")
        check(controller.bulkCloseResult.value == null)
    }

    private fun verifyFullscreen(activity: MainActivity) {
        val before = terminalView(activity)
        val normalHeight = before.height
        val rawBefore = session.herdr!!.state.value
        click("全画面表示")
        dismissSystemFullscreenHint()
        awaitNode { it.contentDescription?.toString() == "全画面を解除" }
        check(find { it.contentDescription?.startsWith("タブ一覧") == true } == null)
        check(find { it.text?.toString() == "ESC" } == null)
        check(terminalView(activity) === before) { "Fullscreen must keep the terminal and IME target alive" }
        check(before.height > normalHeight + (120 * app.resources.displayMetrics.density).toInt()) { "Fullscreen must visibly expand the terminal" }
        check(session.herdr!!.state.value == rawBefore) { "Fullscreen must not depend on remote pane.zoom" }
        capture("terminal-fullscreen")
        click("キーボードの表示/非表示")
        awaitCondition { imeVisible(activity) }
        capture("terminal-fullscreen-keyboard")
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        awaitCondition { !imeVisible(activity) }
        awaitNode { it.contentDescription?.toString() == "全画面を解除" }
        click("全画面を解除")
        awaitNode { it.contentDescription?.toString() == "全画面表示" }
        awaitCondition { systemBarsVisible(activity) }
        check(terminalView(activity) === before)
        capture("terminal-fullscreen-restored")
        // Enter with IME already visible; Back dismisses it first, then exits fullscreen.
        click("キーボードの表示/非表示")
        awaitCondition { imeVisible(activity) }
        click("全画面表示")
        awaitCondition { imeVisible(activity) }
        awaitNode { it.contentDescription?.toString() == "全画面を解除" }
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        awaitCondition { !imeVisible(activity) }
        awaitNode { it.contentDescription?.toString() == "全画面を解除" }
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        awaitNode { it.contentDescription?.toString() == "全画面表示" }
        awaitNode { it.contentDescription?.startsWith("タブ一覧") == true }
        awaitCondition { systemBarsVisible(activity) }
    }

    private fun dismissSystemFullscreenHint() {
        // Android can show a delayed, one-time immersive-mode education window on fresh emulators.
        val deadline = android.os.SystemClock.uptimeMillis() + 3_000
        do {
            if (find { it.text?.toString() == "Got it" } != null) {
                click("Got it")
                return
            }
            android.os.SystemClock.sleep(100)
        } while (android.os.SystemClock.uptimeMillis() < deadline)
    }

    private fun terminalView(activity: MainActivity): com.shibaken.androidterminal.terminal.TerminalCanvasView {
        var found: com.shibaken.androidterminal.terminal.TerminalCanvasView? = null
        instrumentation.runOnMainSync {
            fun visit(view: android.view.View) {
                if (view is com.shibaken.androidterminal.terminal.TerminalCanvasView && view.isPaneFocused) found = view
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
            }
            visit(activity.window.decorView)
        }
        return checkNotNull(found)
    }

    private fun imeVisible(activity: MainActivity): Boolean {
        var visible = false
        instrumentation.runOnMainSync {
            visible = androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
        }
        return visible
    }

    private fun systemBarsVisible(activity: MainActivity): Boolean {
        var visible = false
        instrumentation.runOnMainSync {
            visible = androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.statusBars() or androidx.core.view.WindowInsetsCompat.Type.navigationBars()) == true
        }
        return visible
    }

    private fun swipeCard(title: String) {
        val node = awaitNode { it.contentDescription?.startsWith("$title、") == true }
        val rect = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        val startX = rect.centerX().toFloat(); val startY = rect.centerY().toFloat()
        val delta = 96 * app.resources.displayMetrics.density * if (startX < app.resources.displayMetrics.widthPixels / 2) 1 else -1
        val started = android.os.SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, elapsed: Long) {
            val event = android.view.MotionEvent.obtain(started, started + elapsed, action, x, startY, 0)
            check(instrumentation.uiAutomation.injectInputEvent(event, true))
            event.recycle()
        }
        send(android.view.MotionEvent.ACTION_DOWN, startX, 0)
        for (i in 1..6) send(android.view.MotionEvent.ACTION_MOVE, startX + delta * i / 6, i * 35L)
        send(android.view.MotionEvent.ACTION_UP, startX + delta, 250)
        settle()
    }

    private fun verifyTopTargets(selectedTitle: String = "dev") {
        val density = app.resources.displayMetrics.density
        val centers = mutableListOf<Int>()
        for (label in listOf("${selectedTitle}を閉じる", "新しいタブ", "タブ一覧", "メニュー")) {
            var node = awaitNode { it.contentDescription?.startsWith(label) == true }
            while (!node.isClickable) node = checkNotNull(node.parent)
            val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
            check(bounds.width() >= (48 * density).toInt() - 1 && bounds.height() >= (48 * density).toInt() - 1) { "$label needs a 48dp target: $bounds" }
            check(bounds.left >= 0 && bounds.right <= app.resources.displayMetrics.widthPixels) { "$label must stay fully visible: $bounds" }
            centers += bounds.centerY()
        }
        check(centers.max() - centers.min() <= density.toInt() + 1) { "B2 actions must stay on one row" }
    }

    private fun verifyExtensions(host: HostProfile) {
        click("メニュー")
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

        click("メニュー")
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
