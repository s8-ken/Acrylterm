package com.shibaken.androidterminal.ext

import org.luaj.vm2.Globals
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction

/** `keys.add` の動作。 */
internal sealed interface KeyAction {
    data class Send(val text: String?, val keys: List<String>) : KeyAction
    data class Call(val fn: LuaValue) : KeyAction
}

internal class OutputTrigger(val pattern: String, val regex: Regex, val fn: LuaValue)

/** 1 回の読み込みで集めた登録内容(Lua の値を含む。エンジン内部専用)。 */
internal class Registrations {
    val outputTriggers = ArrayList<OutputTrigger>()
    val events = LinkedHashMap<String, MutableList<LuaValue>>()
    val commands = ArrayList<Pair<String, LuaValue>>()
    val keys = ArrayList<Pair<String, KeyAction>>()
    val quickRules = ArrayList<ExtQuickRule>()

    fun toRegistry() = ExtRegistry(
        commands = commands.map { ExtCommand(it.first) },
        keys = keys.map { ExtKey(it.first) },
        quickReplyRules = quickRules.toList(),
        outputTriggerPatterns = outputTriggers.map { it.pattern },
        eventNames = events.keys.toList(),
    )
}

/**
 * Lua API を [Globals] に登録する。登録内容は [regs] に溜まる。
 * 正規表現は Java/Kotlin の `Regex` 構文(Lua パターンではない)。登録時にコンパイルし、不正なら Lua エラー。
 */
internal class ExtApi(
    private val host: ExtHost,
    private val runtime: LuaRuntime,
    private val regs: Registrations,
    private val timers: TimerSet,
) {
    private val paneMethods = LuaTable()
    private val paneMeta = LuaTable()

    private fun fn(body: (Varargs) -> Varargs): LuaValue = object : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs = body(args)
    }

    private fun <T> hostCall(what: String, block: () -> T): T = try {
        block()
    } catch (e: LuaError) {
        throw e
    } catch (e: Exception) {
        throw LuaError("$what: ${e.message ?: e.javaClass.simpleName}")
    }

    fun paneObject(p: ExtPane): LuaTable {
        val t = LuaTable()
        t.set("id", p.id)
        t.set("title", p.title)
        t.set("agent", p.agent?.let { LuaValue.valueOf(it) } ?: LuaValue.NIL)
        t.set("status", p.status)
        t.set("cwd", p.cwd?.let { LuaValue.valueOf(it) } ?: LuaValue.NIL)
        t.setmetatable(paneMeta)
        return t
    }

    /** id からペインオブジェクトを作る(一覧に無ければ最小限の項目で)。 */
    fun paneObjectFor(id: String): LuaTable {
        val p = try {
            host.panes().firstOrNull { it.id == id }
        } catch (_: Exception) {
            null
        } ?: ExtPane(id, id, null, "unknown", null)
        return paneObject(p)
    }

    private fun paneId(self: LuaValue): String {
        if (!self.istable()) throw LuaError("pane method must be called with ':' (pane:send(...))")
        val id = self.get("id")
        if (!id.isstring()) throw LuaError("invalid pane object")
        return id.tojstring()
    }

    private fun stringList(v: LuaValue, what: String): List<String> {
        if (v.isnil()) return emptyList()
        if (!v.istable()) throw LuaError("$what must be a list of strings")
        val t = v.checktable()
        return (1..t.length()).map {
            val e = t.get(it)
            if (!e.isstring()) throw LuaError("$what[$it] must be a string")
            e.tojstring()
        }
    }

    private fun compile(pattern: LuaValue): Pair<String, Regex> {
        if (!pattern.isstring()) throw LuaError("regex must be a string")
        val s = pattern.tojstring()
        val r = try {
            Regex(s)
        } catch (e: Exception) {
            throw LuaError("invalid regex '$s': ${e.message?.lineSequence()?.firstOrNull()}")
        }
        return s to r
    }

    private fun checkFn(v: LuaValue, what: String) {
        if (!v.isfunction()) throw LuaError("$what must be a function")
    }

    fun install(g: Globals) {
        // pane methods
        paneMethods.set("send", fn { a ->
            val id = paneId(a.arg(1))
            val text = a.arg(2).checkjstring()
            hostCall("pane:send") { host.sendText(id, text) }
            LuaValue.NONE
        })
        paneMethods.set("send_keys", fn { a ->
            val id = paneId(a.arg(1))
            val keys = stringList(a.arg(2), "keys")
            hostCall("pane:send_keys") { host.sendKeys(id, keys) }
            LuaValue.NONE
        })
        paneMethods.set("read", fn { a ->
            val id = paneId(a.arg(1))
            val n = a.arg(2).optint(40).coerceIn(1, 2000)
            LuaValue.valueOf(hostCall("pane:read") { runtime.blocking { host.readPane(id, n) } })
        })
        paneMeta.set(LuaValue.INDEX, paneMethods)

        // term.on_output
        val term = LuaTable()
        term.set("on_output", fn { a ->
            val (src, re) = compile(a.arg(1))
            checkFn(a.arg(2), "term.on_output handler")
            regs.outputTriggers += OutputTrigger(src, re, a.arg(2))
            LuaValue.NONE
        })
        g.set("term", term)

        g.set("on", fn { a ->
            val ev = a.arg(1).checkjstring()
            if (ev != "agent_status" && ev != "connected") throw LuaError("unknown event '$ev' (agent_status | connected)")
            checkFn(a.arg(2), "on handler")
            regs.events.getOrPut(ev) { ArrayList() }.add(a.arg(2))
            LuaValue.NONE
        })

        g.set("command", fn { a ->
            val name = a.arg(1).checkjstring()
            checkFn(a.arg(2), "command handler")
            regs.commands.removeAll { it.first == name }
            regs.commands += name to a.arg(2)
            LuaValue.NONE
        })

        val keys = LuaTable()
        keys.set("add", fn { a ->
            val label = a.arg(1).checkjstring()
            val spec = a.arg(2)
            val action: KeyAction = when {
                spec.isstring() -> KeyAction.Send(spec.tojstring(), emptyList())
                spec.isfunction() -> KeyAction.Call(spec)
                spec.istable() -> {
                    val send = spec.get("send")
                    KeyAction.Send(if (send.isstring()) send.tojstring() else null, stringList(spec.get("keys"), "keys"))
                }
                else -> throw LuaError("keys.add spec must be a string, table or function")
            }
            regs.keys += label to action
            LuaValue.NONE
        })
        g.set("keys", keys)

        val qr = LuaTable()
        qr.set("rule", fn { a ->
            val (src, _) = compile(a.arg(1))
            val list = a.arg(2)
            if (!list.istable()) throw LuaError("quick_reply.rule choices must be a list")
            val choices = (1..list.length()).map { i -> parseChoice(list.get(i), i) }
            regs.quickRules += ExtQuickRule(src, choices)
            LuaValue.NONE
        })
        g.set("quick_reply", qr)

        g.set("notify", fn { a ->
            val title = a.arg(1).checkjstring()
            val body = a.arg(2).optjstring("")
            hostCall("notify") { host.notify(title, body, host.focusedPaneId()) }
            LuaValue.NONE
        })
        g.set("toast", fn { a ->
            val text = a.arg(1).checkjstring()
            hostCall("toast") { host.toast(text) }
            LuaValue.NONE
        })
        val logFn = fn { a ->
            val line = (1..a.narg()).joinToString("\t") { a.arg(it).tojstring() }
            hostCall("log") { host.log(line) }
            LuaValue.NONE
        }
        g.set("log", logFn)
        g.set("print", logFn)

        val ssh = LuaTable()
        ssh.set("exec", fn { a ->
            val cmd = a.arg(1).checkjstring()
            val timeout = a.arg(2).optint(15).coerceIn(1, 120)
            LuaValue.valueOf(hostCall("ssh.exec") { runtime.blocking { host.exec(cmd, timeout) } })
        })
        g.set("ssh", ssh)

        timers.install(g) { body -> fn(body) }

        g.set("panes", fn { _ ->
            val t = LuaTable()
            hostCall("panes") { host.panes() }.forEachIndexed { i, p -> t.set(i + 1, paneObject(p)) }
            t
        })
    }

    private fun parseChoice(v: LuaValue, idx: Int): ExtChoice {
        if (v.isstring()) {
            val s = v.tojstring()
            // 文字列だけ: ラベル = 送信文字列、送信時に Enter(\r) を付ける
            return ExtChoice(s, s + "\r")
        }
        if (!v.istable()) throw LuaError("quick_reply choice #$idx must be a string or table")
        val send = v.get("send").let { if (it.isstring()) it.tojstring() else null }
        val keys = stringList(v.get("keys"), "choice #$idx keys")
        val label = v.get("label").let { if (it.isstring()) it.tojstring() else null }
            ?: send?.trim()?.takeIf { it.isNotEmpty() }
            ?: keys.firstOrNull()
            ?: throw LuaError("quick_reply choice #$idx needs label, send or keys")
        return ExtChoice(label, send, keys)
    }

    /** `command` コールバックに渡す ctx。 */
    fun commandContext(): LuaTable {
        val ctx = LuaTable()
        val id = try { host.focusedPaneId() } catch (_: Exception) { null }
        if (id != null) ctx.set("pane", paneObjectFor(id))
        return ctx
    }
}
