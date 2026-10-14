package com.shibaken.androidterminal.ext

import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import java.util.concurrent.Future

/**
 * `timer.*` の状態。読み込み 1 世代(Globals 1 つ)につき 1 インスタンスで、世代ごとに破棄される
 * (再読込の成功で旧世代を、失敗で新世代を [cancelAll])。
 *
 * - [schedule]: 指定ミリ秒後に [Runnable] を実行する(別スレッドで良い。発火後は [post] で Lua スレッドへ移す)。
 * - [post]: Lua スレッドで実行する。
 * - [invoke]: Lua スレッド上でコールバックを保護付きで呼ぶ。成功したら true(エラーは呼び出し側がログ済み)。
 * 状態は Lua スレッドと close を呼ぶスレッドの双方から触るので、synchronized で守る。
 */
internal class TimerSet(
    private val schedule: (Long, Runnable) -> Future<*>,
    private val post: (() -> Unit) -> Unit,
    private val invoke: (String, LuaValue) -> Boolean,
    private val onAutoCancel: (String) -> Unit,
    private val minAfterSec: Double = MIN_AFTER_SEC,
    private val minEverySec: Double = MIN_EVERY_SEC,
) {
    private class Entry(val id: Int, val fn: LuaValue, val everyMs: Long?) {
        var future: Future<*>? = null
        var errors = 0
    }

    private val entries = HashMap<Int, Entry>()
    private var nextId = 1
    private var dead = false

    fun install(g: org.luaj.vm2.Globals, fn: ((org.luaj.vm2.Varargs) -> org.luaj.vm2.Varargs) -> LuaValue) {
        val t = LuaTable()
        t.set("after", fn { a -> add(a.arg(1), a.arg(2), repeat = false, name = "timer.after") })
        t.set("every", fn { a -> add(a.arg(1), a.arg(2), repeat = true, name = "timer.every") })
        t.set("cancel", fn { a ->
            val id = a.arg(1)
            if (id.type() != LuaValue.TNUMBER) throw LuaError("timer.cancel id must be a number")
            LuaValue.valueOf(cancel(id.toint()))
        })
        g.set("timer", t)
    }

    private fun add(sec: LuaValue, fn: LuaValue, repeat: Boolean, name: String): LuaValue {
        if (sec.type() != LuaValue.TNUMBER || sec.todouble().isNaN()) throw LuaError("$name: seconds must be a number")
        if (!fn.isfunction()) throw LuaError("$name: callback must be a function")
        val ms = clampMs(sec.todouble(), repeat, minAfterSec, minEverySec)
        synchronized(this) {
            if (dead) throw LuaError("$name: engine is closed")
            if (entries.size >= MAX_TIMERS) throw LuaError("too many timers (max $MAX_TIMERS)")
            val e = Entry(nextId++, fn, if (repeat) ms else null)
            entries[e.id] = e
            arm(e, ms)
            return LuaValue.valueOf(e.id)
        }
    }

    private fun arm(e: Entry, ms: Long) {
        synchronized(this) {
            if (dead || entries[e.id] !== e) return
            e.future = try {
                schedule(ms, Runnable { post { fire(e) } })
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                null
            }
        }
    }

    /** Lua スレッド上で実行される。 */
    private fun fire(e: Entry) {
        if (synchronized(this) { dead || entries[e.id] !== e }) return
        val ok = invoke(if (e.everyMs != null) "timer.every #${e.id}" else "timer.after #${e.id}", e.fn)
        if (e.everyMs == null) {
            synchronized(this) { if (entries[e.id] === e) entries.remove(e.id) }
            return
        }
        // 固定遅延: コールバック完了後に次を予約する。コールバック内で cancel されていれば何もしない。
        var stop = false
        synchronized(this) {
            if (dead || entries[e.id] !== e) return
            e.errors = if (ok) 0 else e.errors + 1
            if (e.errors >= MAX_CONSECUTIVE_ERRORS) {
                entries.remove(e.id)
                stop = true
            }
        }
        if (stop) {
            onAutoCancel("timer.every #${e.id} cancelled after $MAX_CONSECUTIVE_ERRORS consecutive errors")
            return
        }
        arm(e, e.everyMs)
    }

    fun cancel(id: Int): Boolean = synchronized(this) {
        val e = entries.remove(id) ?: return false
        e.future?.cancel(false)
        true
    }

    /** 全タイマーを止め、以後の登録も拒否する。 */
    fun cancelAll() = synchronized(this) {
        dead = true
        entries.values.forEach { it.future?.cancel(false) }
        entries.clear()
    }

    companion object {
        const val MIN_AFTER_SEC = 0.1
        const val MIN_EVERY_SEC = 1.0
        const val MAX_SEC = 24 * 3600.0
        const val MAX_TIMERS = 32
        const val MAX_CONSECUTIVE_ERRORS = 5

        /** 秒数をミリ秒へ。下限・上限(24h)に丸める(エラーにはしない)。 */
        fun clampMs(sec: Double, repeat: Boolean, minAfter: Double = MIN_AFTER_SEC, minEvery: Double = MIN_EVERY_SEC): Long =
            (sec.coerceIn(if (repeat) minEvery else minAfter, MAX_SEC) * 1000.0).toLong()
    }
}
