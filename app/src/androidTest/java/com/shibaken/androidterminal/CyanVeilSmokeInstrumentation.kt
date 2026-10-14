package com.shibaken.androidterminal

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import com.shibaken.androidterminal.data.AppearanceRepository
import com.shibaken.androidterminal.data.AppearanceSettings
import com.shibaken.androidterminal.terminal.TerminalBackend
import com.shibaken.androidterminal.terminal.TerminalCanvasView
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSessionClient
import java.lang.reflect.Proxy

/** Device regression checks without a live SSH connection or additional test dependencies. */
class CyanVeilSmokeInstrumentation : Instrumentation() {
    private var captureUi = false
    private var sessionsOnly = false
    private var extensionInputOnly = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        captureUi = arguments?.getString("capture_ui") == "true"
        sessionsOnly = arguments?.getString("herdr_sessions") == "true"
        extensionInputOnly = arguments?.getString("extension_input") == "true"
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            if (extensionInputOnly) {
                ExtensionInputChecks(this).run()
                result.putString("stream", "PASS: extension paste/submit/raw encoding, LF/CRLF/CR, UTF-8, ordered shell writes and disconnected/failed writes.\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            runOnMainSync {
                verifyPersistence()
                verifyTerminalTransparency()
                verifyHerdrSessionPersistence()
            }
            if (captureUi || sessionsOnly) CyanVeilUiCapture(this).verifyAndCapture(sessionsOnly)
            result.putString("stream", "PASS: appearance persistence and bounds; terminal background transparency; opaque text, ANSI fills and cursor." +
                if (sessionsOnly) " herdr session picker, loading/error/retry/cancel, exact and long names, XML persistence, fullscreen/IME, tabs/Undo and overview verified.\n"
                else if (captureUi) " UI navigation, scripts create/save/cancel/delete, Lua command/key execution, host extension settings, keyboard and screenshots verified.\n" else "\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAIL: ${error.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun verifyPersistence() {
        val repository = (targetContext.applicationContext as App).appearance
        val before = repository.settings.value
        try {
            repository.save(AppearanceSettings(55, false))
            check(AppearanceRepository(targetContext).settings.value == AppearanceSettings(55, false))
            repository.save(AppearanceSettings(200, true))
            check(AppearanceRepository(targetContext).settings.value.transparencyPercent == 100)
            repository.save(AppearanceSettings(-20, false))
            check(AppearanceRepository(targetContext).settings.value.backgroundOpacity == 1f)
        } finally { repository.save(before) }
    }

    private fun verifyHerdrSessionPersistence() {
        val app = targetContext.applicationContext
        val name = "herdr_session_persistence_test"
        val prefs = app.getSharedPreferences(name, 0)
        val profile = com.shibaken.androidterminal.data.HostProfile(id = "persistence", host = "host", username = "user")
        val key = com.shibaken.androidterminal.herdr.herdrSessionPreferenceKey(profile)
        try {
            check(prefs.edit().putString(key, "Work-v2").commit())
            val file = java.io.File(app.applicationInfo.dataDir, "shared_prefs/$name.xml")
            val parser = android.util.Xml.newPullParser()
            file.inputStream().use { stream ->
                parser.setInput(stream, "UTF-8")
                var restored: String? = null
                while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "string" && parser.getAttributeValue(null, "name") == key) restored = parser.nextText()
                }
                check(restored == "Work-v2") { "Remembered session must survive XML serialization and reload" }
            }
        } finally { app.deleteSharedPreferences(name) }
    }

    private fun verifyTerminalTransparency() {
        val backend = TestTerminal()
        val view = TerminalCanvasView(targetContext).apply { session = backend; layout(0, 0, 600, 300) }
        backend.append("\u001b[?25l\u001b[2J\u001b[H\u001b[38;2;228;247;246mMMMM\u001b[0m\r\n\u001b[48;2;180;50;70m    \u001b[0m")
        fun render(opacity: Float): Bitmap {
            view.backgroundOpacity = opacity
            return Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val transparent = render(0f)
        val translucent = render(.72f)
        val solid = render(1f)
        check(Color.alpha(transparent.getPixel(590, 290)) == 0)
        check(Color.alpha(translucent.getPixel(590, 290)) in 182..185)
        check(Color.alpha(solid.getPixel(590, 290)) == 255)
        var textPixels = 0
        var ansiPixels = 0
        for (y in 0 until 120) for (x in 0 until 200) {
            val p = transparent.getPixel(x, y)
            if (p == Color.rgb(228, 247, 246)) { textPixels++; check(p == solid.getPixel(x, y)) }
            if (p == Color.rgb(180, 50, 70)) { ansiPixels++; check(p == solid.getPixel(x, y)) }
        }
        check(textPixels > 10) { "Foreground must stay opaque at 100% transparency" }
        check(ansiPixels > 10) { "An explicit ANSI background must stay opaque" }
        backend.append("\u001b[?25h\u001b[4;1H")
        val cursor = render(0f)
        var cursorPixels = 0
        for (y in 0 until cursor.height) for (x in 0 until cursor.width) {
            if (cursor.getPixel(x, y) == Color.rgb(100, 244, 229)) cursorPixels++
        }
        check(cursorPixels > 10) { "Cursor must remain visible on a transparent background" }
        view.session = null
        listOf(transparent, translucent, solid, cursor).forEach { it.recycle() }
    }

    private class TestTerminal : TerminalBackend {
        private val output = object : TerminalOutput() {
            override fun write(data: ByteArray, offset: Int, count: Int) {}
            override fun titleChanged(oldTitle: String?, newTitle: String?) {}
            override fun onCopyTextToClipboard(text: String?) {}
            override fun onPasteTextFromClipboard() {}
            override fun onBell() {}
            override fun onColorsChanged() {}
        }
        private val client = Proxy.newProxyInstance(TerminalSessionClient::class.java.classLoader,
            arrayOf(TerminalSessionClient::class.java)) { _, method, _ ->
            if (method.name == "getTerminalCursorStyle") TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK else null
        } as TerminalSessionClient
        override val emulator = TerminalEmulator(output, 40, 10, 10, 20, 100, client)
        override var onScreenUpdate: (() -> Unit)? = null
        override fun sendText(text: String) {}
        override fun pasteFromClipboard() {}
        override fun resize(newCols: Int, newRows: Int, cellWidth: Int, cellHeight: Int) {
            emulator.resize(newCols, newRows, cellWidth, cellHeight)
        }
        fun append(text: String) { val bytes = text.toByteArray(); emulator.append(bytes, bytes.size) }
    }
}
