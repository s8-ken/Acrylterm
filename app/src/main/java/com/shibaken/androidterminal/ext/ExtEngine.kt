package com.shibaken.androidterminal.ext

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.luaj.vm2.LuaValue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 拡張エンジンの窓口(セッション 1 つにつき 1 インスタンス)。
 * 全ての Lua 呼び出しは専用スレッド上で直列化される。コールバックのエラーは
 * `host.log` + `host.toast` に流し、例外は呼び出し側に投げない。
 */
class ExtEngine(
    private val host: ExtHost,
    private val runtime: LuaRuntime = LuaRuntime(),
    /** タイマーの下限(秒)。テストで短縮するための差し替え口。 */
    private val minAfterSec: Double = TimerSet.MIN_AFTER_SEC,
    private val minEverySec: Double = TimerSet.MIN_EVERY_SEC,
    /** Formats the user-visible toast for a callback error (the session injects a localized one). */
    private val errorToast: (String) -> String = { "Extension error: $it" },
) {
    private class Loaded(val regs: Registrations, val api: ExtApi, val globals: org.luaj.vm2.Globals, val timers: TimerSet)

    private val timerScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ext-timer").apply { isDaemon = true }
    }

    @Volatile private var loaded: Loaded? = null
    private val closed = AtomicBoolean(false)
    private val _registry = MutableStateFlow(ExtRegistry.EMPTY)

    /** 現在の登録内容。再読込に成功すると差し替わる。 */
    val registry: StateFlow<ExtRegistry> = _registry

    /**
     * 新しい Globals で [scripts] を全て評価する。1 本でもエラーなら直前の登録を維持し、
     * 全エラー(`name:line: message` 形式)を返す。成功時は差し替えて [ExtLoadResult.errors] は空。
     */
    suspend fun reload(scripts: List<ExtScript>): ExtLoadResult = withContext(runtime.dispatcher) {
        val regs = Registrations()
        val errors = ArrayList<String>()
        val g = runtime.newGlobals()
        // タイマーは読み込み世代に属する。成功すれば旧世代のを止めて差し替え、失敗なら新世代のを捨てて旧世代を残す。
        val timers = TimerSet(
            schedule = { ms, r -> timerScheduler.schedule(r, ms, java.util.concurrent.TimeUnit.MILLISECONDS) },
            post = { b -> runtime.post(b) },
            invoke = { what, fn ->
                runtime.activate(g)
                callGuarded(what) { fn.call() }
            },
            onAutoCancel = { msg -> try { host.log(msg) } catch (_: Throwable) {} },
            minAfterSec = minAfterSec,
            minEverySec = minEverySec,
        )
        val api = ExtApi(host, runtime, regs, timers)
        api.install(g)
        for (s in scripts) {
            try {
                runtime.guarded { runtime.evaluate(g, s.name, s.source) }
            } catch (t: Throwable) {
                errors += LuaRuntime.formatError(t).let { if (it.startsWith(s.name)) it else "${s.name}: $it" }
            }
        }
        if (errors.isEmpty() && !closed.get()) {
            val old = loaded
            loaded = Loaded(regs, api, g, timers)
            old?.timers?.cancelAll()
            _registry.value = regs.toRegistry()
        } else {
            timers.cancelAll()
        }
        ExtLoadResult(errors, _registry.value)
    }

    /** 名前でコマンドを実行する(ctx.pane はフォーカス中のペイン)。 */
    fun runCommand(name: String) = submit("command '$name'") { l ->
        val fn = l.regs.commands.lastOrNull { it.first == name }?.second ?: error("no such command: $name")
        fn.call(l.api.commandContext())
    }

    /** 補助キー([ExtRegistry.keys] の [index] 番)を押す。[paneId] が null ならフォーカス中のペイン。 */
    fun pressKey(index: Int, paneId: String?) = submit("key") { l ->
        val (label, action) = l.regs.keys.getOrNull(index) ?: return@submit
        val id = paneId ?: host.focusedPaneId() ?: error("no focused pane")
        try {
            when (action) {
                is KeyAction.Send -> {
                    runtime.blocking { host.sendInput(id, action.text, action.keys) }
                }
                is KeyAction.Call -> action.fn.call(l.api.paneObjectFor(id))
            }
        } catch (t: Throwable) {
            throw RuntimeException("key '$label': ${LuaRuntime.formatError(t)}", t)
        }
    }

    /** herdr のエージェント状態遷移を `on("agent_status")` に渡す。 */
    fun dispatchAgentStatus(pane: ExtPane, status: String, prev: String?) = submit("agent_status") { l ->
        val hs = l.regs.events["agent_status"] ?: return@submit
        val p = l.api.paneObject(pane)
        for (h in hs) callGuarded("agent_status") {
            h.invoke(LuaValue.varargsOf(arrayOf(p, LuaValue.valueOf(status), prev?.let { LuaValue.valueOf(it) } ?: LuaValue.NIL)))
        }
    }

    /** 接続完了を `on("connected")` に渡す。 */
    fun dispatchConnected() = submit("connected") { l ->
        val hs = l.regs.events["connected"] ?: return@submit
        for (h in hs) callGuarded("connected") { h.call() }
    }

    /**
     * 変化した行 [changedLines](OutputWatcher の出力)を登録済みトリガーに照合し、一致した行だけ Lua を呼ぶ。
     * 照合は呼び出しスレッドで行う(軽い)。
     */
    fun dispatchOutput(paneId: String, changedLines: List<String>) {
        val l = loaded ?: return
        if (l.regs.outputTriggers.isEmpty() || changedLines.isEmpty()) return
        val lines = changedLines.toList()
        submit("on_output") { cur ->
            if (cur !== l) return@submit
            for (line in lines) for (t in l.regs.outputTriggers) {
                val m = safeFind(t.regex, t.pattern, line, OUTPUT_MATCH_MS) ?: continue
                callGuarded("on_output /${t.pattern}/") {
                    val mt = org.luaj.vm2.LuaTable()
                    mt.set(0, line)
                    m.groupValues.drop(1).forEachIndexed { i, g -> mt.set(i + 1, g) }
                    t.fn.call(l.api.paneObjectFor(paneId), mt)
                }
            }
        }
    }

    private val timedOut: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 時間制限付きの正規表現照合。タイムアウトは「不一致」扱いで、パターンごとに 1 度だけ log する。 */
    private fun safeFind(re: Regex, pattern: String, text: String, ms: Long): MatchResult? = try {
        re.find(DeadlineCharSequence(text, System.nanoTime() + ms * 1_000_000L))
    } catch (_: RegexTimeout) {
        if (timedOut.add(pattern)) try { host.log("regex timed out (treated as no match): /$pattern/") } catch (_: Throwable) {}
        null
    }

    /**
     * [paneText] に最初に一致した `quick_reply.rule` の選択肢を返す(無ければ null)。
     * メインスレッドを塞がないよう Dispatchers.Default で実行し、規則ごとに 200ms で打ち切る(打ち切りは不一致)。
     */
    suspend fun matchQuickReply(paneText: String): List<ExtChoice>? {
        val rules = _registry.value.quickReplyRules
        if (rules.isEmpty()) return null
        return withContext(Dispatchers.Default) {
            rules.firstOrNull { safeFind(it.regex, it.pattern, paneText, QUICK_MATCH_MS) != null }?.choices
        }
    }

    /** Lua スレッドを止める。以後の呼び出しは無視される。 */
    fun close() {
        if (closed.compareAndSet(false, true)) {
            loaded?.timers?.cancelAll()
            loaded = null
            timerScheduler.shutdownNow()
            runtime.close()
        }
    }

    private fun submit(what: String, block: (Loaded) -> Unit) {
        if (closed.get()) return
        runtime.post {
            val l = loaded ?: return@post
            runtime.activate(l.globals)
            callGuarded(what) { block(l) }
        }
    }

    private fun callGuarded(what: String, block: () -> Unit): Boolean {
        try {
            runtime.guarded(block)
            return true
        } catch (t: Throwable) {
            val msg = LuaRuntime.formatError(t)
            try {
                host.log("error in $what: $msg")
                host.toast(errorToast(msg.take(120)))
            } catch (_: Throwable) {
            }
            return false
        }
    }

    private companion object {
        const val OUTPUT_MATCH_MS = 50L
        const val QUICK_MATCH_MS = 200L
    }
}

internal class RegexTimeout : RuntimeException(null, null, false, false)

/** charAt のたびに(256 回に 1 回)期限を確認し、超過で [RegexTimeout] を投げる。破滅的バックトラッキング対策。 */
internal class DeadlineCharSequence(private val s: CharSequence, private val deadline: Long) : CharSequence {
    private var n = 0
    override val length: Int get() = s.length
    override fun get(index: Int): Char {
        if ((++n and 255) == 0 && System.nanoTime() > deadline) throw RegexTimeout()
        return s[index]
    }
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = DeadlineCharSequence(s.subSequence(startIndex, endIndex), deadline)
    override fun toString(): String = s.toString()
}
