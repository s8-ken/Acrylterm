package com.shibaken.androidterminal.ext

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import org.luaj.vm2.Globals
import org.luaj.vm2.Lua
import org.luaj.vm2.LuaClosure
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaFunction
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.Bit32Lib
import org.luaj.vm2.lib.BaseLib
import org.luaj.vm2.lib.DebugLib
import org.luaj.vm2.lib.MathLib
import org.luaj.vm2.lib.OsLib
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.TableLib
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.LoadState
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Lua 実行環境。専用の単一スレッド([dispatcher])で全ての Lua 呼び出しを直列化する
 * (LuaJ はスレッド安全でないため)。サンドボックス化した [Globals] を生成する。
 *
 * 暴走対策: トップレベル呼び出し([guarded])ごとに命令数([maxInstructions])と
 * 実時間([maxWallMillis]、[blocking] 中は除外)を数え、超えたら以後の全命令でエラーを投げる
 * (pcall で握りつぶしても止まる)。
 */
class LuaRuntime(
    private val maxInstructions: Long = 5_000_000L,
    private val maxWallMillis: Long = 10_000L,
    threadName: String = "ext-lua",
    /** ヒープ使用量(used)がこの値を超えたら実行を中断する。既定は maxMemory の 60%。テストで差し替え可。 */
    private val memoryLimitBytes: Long = (Runtime.getRuntime().maxMemory() * 0.6).toLong(),
) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, threadName).apply { isDaemon = true } }
    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    // 以下はすべて Lua スレッド上でのみ触る
    private var count = 0L
    private var deadline = Long.MAX_VALUE
    private var aborted: String? = null
    private var lastGc = System.nanoTime() - 2_000_000_000L
    private val frames = ArrayList<Pair<LuaClosure, Array<LuaValue?>>>()

    /** Lua スレッドで [block] を実行する(fire-and-forget)。破棄後は黙って捨てる。 */
    fun post(block: () -> Unit) {
        try {
            executor.execute {
                try {
                    block()
                } catch (_: Throwable) {
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    /** 予算(命令数・時間)をリセットして [block] を実行する。Lua スレッド上で呼ぶこと。 */
    fun <T> guarded(block: () -> T): T {
        val savedCount = count
        val savedDeadline = deadline
        val savedAborted = aborted
        count = 0
        aborted = null
        frames.clear()
        deadline = System.nanoTime() + maxWallMillis * 1_000_000L
        try {
            return block()
        } finally {
            count = savedCount
            deadline = savedDeadline
            aborted = savedAborted
        }
    }

    /** ホストのブロッキング呼び出し(exec 等)に使う。その所要時間は実時間制限から除外する。 */
    fun <T> blocking(block: () -> T): T {
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            if (deadline != Long.MAX_VALUE) deadline += System.nanoTime() - t0
        }
    }

    fun close() {
        executor.shutdownNow()
    }

    private fun used(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    /** 安価な使用量チェック。超過時は 1 度だけ GC してから再判定する(未回収のゴミによる誤検知対策)。 */
    private fun overMemory(): Boolean {
        if (used() <= memoryLimitBytes) return false
        val now = System.nanoTime()
        if (now - lastGc > 1_000_000_000L) { // GC は最大 1 秒に 1 回
            lastGc = now
            System.gc()
            return used() > memoryLimitBytes
        }
        return true
    }

    /** この Lua スレッドで以後 `("x"):upper()` 等が参照する string ライブラリを [g] のものにする。 */
    fun activate(g: Globals) {
        currentString.set(stringTables[g])
    }

    private val stringTables = java.util.WeakHashMap<Globals, LuaTable>()

    private inner class LimitLib : DebugLib() {
        override fun onCall(c: LuaClosure, varargs: Varargs, stack: Array<LuaValue?>) {
            frames.add(c to stack)
            super.onCall(c, varargs, stack)
        }

        override fun onReturn() {
            if (frames.isNotEmpty()) frames.removeAt(frames.size - 1)
            super.onReturn()
        }

        /** OP_CONCAT の実行前にオペランド長を合算し、上限超過なら確保前にエラーにする。 */
        private fun checkConcat(pc: Int) {
            val (c, stack) = frames.lastOrNull() ?: return
            val code = c.p.code
            if (pc !in code.indices) return
            val i = code[pc]
            if (Lua.GET_OPCODE(i) != Lua.OP_CONCAT) return
            var total = 0L
            for (r in Lua.GETARG_B(i)..Lua.GETARG_C(i)) {
                val x = stack.getOrNull(r) ?: continue
                if (x.type() == LuaValue.TSTRING) total += x.checkstring().length() else if (x.isnumber()) total += 32
                if (total > MAX_STRING) throw LuaError("string too long (max $MAX_STRING bytes)")
            }
        }

        override fun onInstruction(pc: Int, v: Varargs, top: Int) {
            checkConcat(pc)
            aborted?.let { throw LuaError(it) }
            if ((++count and 15L) == 0L && overMemory()) {
                aborted = "memory limit exceeded"
                throw LuaError(aborted!!)
            }
            if (count > maxInstructions) {
                aborted = "instruction limit exceeded ($maxInstructions)"
                throw LuaError(aborted!!)
            }
            if (count % 1000L == 0L && System.nanoTime() > deadline) {
                aborted = "time limit exceeded (${maxWallMillis}ms)"
                throw LuaError(aborted!!)
            }
        }
    }

    /**
     * サンドボックス化した Globals を作る。含むのは base(dofile/loadfile なし、load は文字列のみ)、
     * table、string(dump なし)、math、bit32、os(time/clock/date のみ)。
     * io / package / require / luajava / debug / coroutine は含まない。
     */
    fun newGlobals(): Globals {
        val g = Globals()
        stubPackage(g) // 各 lib が package.loaded を参照するため一時的に置く(最後に消す)
        g.load(BaseLib())
        g.load(TableLib())
        synchronized(LuaRuntime::class.java) {
            g.load(StringLib())
            // StringLib は static な LuaString.s_metatable を上書きするので、スレッドごとの
            // string テーブルへ解決する共有メタテーブルに差し替える(ランタイム間の分離)。
            org.luaj.vm2.LuaString.s_metatable = sharedStringMeta
        }
        stringTables[g] = g.get("string").checktable()
        currentString.set(stringTables[g])
        g.load(Bit32Lib())
        g.load(MathLib())
        LoadState.install(g)
        LuaC.install(g)
        // 命令数フック。DebugLib を load すると g.debuglib が設定され VM が onInstruction を呼ぶ。
        // 公開されるグローバル "debug" はすぐ消すのでスクリプトからは到達できない。
        g.load(LimitLib())
        g.set("debug", LuaValue.NIL)

        // os は time/clock/date だけ
        val tmp = Globals()
        stubPackage(tmp)
        tmp.load(OsLib())
        val fullOs = tmp.get("os")
        val os = LuaTable()
        for (k in arrayOf("time", "clock", "date")) os.set(k, fullOs.get(k))
        g.set("os", os)

        for (k in arrayOf("dofile", "loadfile", "collectgarbage", "package", "require")) g.set(k, LuaValue.NIL)
        val string = g.get("string")
        string.set("dump", LuaValue.NIL)
        // LuaJ のパターンマッチはネイティブ実行で命令数フックが効かないため、長い対象を拒否する
        for (name in arrayOf("find", "match", "gmatch", "gsub")) {
            val orig = string.get(name)
            string.set(name, object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val subj = args.arg(1)
                    if (subj.isstring()) {
                        val n = subj.checkstring().length()
                        if (n > MAX_PATTERN_SUBJECT) throw LuaError("string.$name: subject too long (max $MAX_PATTERN_SUBJECT bytes)")
                        val pat = args.arg(2)
                        if (n > MAX_PATTERN_SUBJECT_LAZY && pat.isstring() && pat.tojstring().let { '-' in it || '*' in it }) {
                            throw LuaError("string.$name: subject too long for a pattern with '-' or '*' (max $MAX_PATTERN_SUBJECT_LAZY bytes)")
                        }
                    }
                    return orig.invoke(args)
                }
            })
        }
        val rep = string.get("rep")
        string.set("rep", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val n = args.arg(2).optlong(0)
                val len = args.arg(1).checkstring().length().toLong()
                if (n * (len + args.arg(3).optstring(LuaValue.valueOf("")).length()) > MAX_STRING) {
                    throw LuaError("string.rep: result too large")
                }
                return rep.invoke(args)
            }
        })
        // string.format: 幅・精度が大きいと巨大な文字列を作るので 1000 超は拒否
        val fmt = string.get("format")
        string.set("format", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val f = args.arg(1)
                if (f.isstring()) checkFormatWidths(f.tojstring())
                val r = fmt.invoke(args)
                if (r.arg1().isstring() && r.arg1().checkstring().length() > MAX_STRING) throw LuaError("string too long")
                return r
            }
        })
        // table.concat: 結果長を事前に見積もって上限超過なら確保前にエラー
        val tbl = g.get("table")
        val tconcat = tbl.get("concat")
        tbl.set("concat", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val t = args.arg(1)
                if (t.istable()) {
                    val sep = args.arg(2).let { if (it.isstring()) it.checkstring().length() else 0 }
                    val i = args.arg(3).optint(1)
                    val j = if (args.arg(4).isnil()) t.length() else args.arg(4).checkint()
                    var total = 0L
                    var k = i
                    while (k <= j) {
                        val x = t.rawget(k)
                        total += (if (x.type() == LuaValue.TSTRING) x.checkstring().length() else 32) + sep
                        if (total > MAX_STRING) throw LuaError("table.concat: string too long (max $MAX_STRING bytes)")
                        k++
                    }
                }
                return tconcat.invoke(args)
            }
        })
        // 文字列のメタテーブル(全 Globals で共有される static)を書き換えられないよう隠す
        val getmt = g.get("getmetatable")
        g.set("getmetatable", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs =
                if (args.arg(1).type() == LuaValue.TSTRING) LuaValue.NIL else getmt.invoke(args)
        })
        g.set("load", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val chunk = args.arg(1)
                if (!chunk.isstring()) return LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("load: only string chunks are allowed"))
                val mode = args.arg(3)
                if (!mode.isnil() && mode.tojstring().contains('b') && !mode.tojstring().contains('t')) {
                    return LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("load: binary chunks are not allowed"))
                }
                val name = if (args.arg(2).isstring()) args.arg(2).tojstring() else "=(load)"
                val env = args.arg(4)
                return try {
                    g.load(chunk.tojstring(), name, if (env.istable()) env.checktable() else g)
                } catch (e: LuaError) {
                    LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf(formatError(e)))
                }
            }
        })
        return g
    }

    private fun stubPackage(g: Globals) {
        val pkg = LuaTable()
        pkg.set("loaded", LuaTable())
        g.set("package", pkg)
    }

    /**
     * 実行専用: 文字列の `chunk` を [name] で評価する。構文エラーは [LuaError]。
     * Lua スレッド上で [guarded] の内側から呼ぶこと。
     */
    fun evaluate(g: Globals, name: String, source: String): Varargs {
        val f: LuaFunction = g.load(source, "=$name") as LuaFunction
        return f.invoke()
    }

    companion object {
        const val MAX_STRING = 1_000_000L

        private fun checkFormatWidths(f: String) {
            var i = 0
            while (i < f.length) {
                if (f[i] != '%') { i++; continue }
                i++
                if (i < f.length && f[i] == '%') { i++; continue }
                while (i < f.length && f[i] in "-+ #0") i++
                var run = 0
                while (i < f.length && (f[i].isDigit() || f[i] == '.')) {
                    if (f[i] == '.') run = 0 else {
                        run = run * 10 + (f[i] - '0')
                        if (run > 1000) throw LuaError("string.format: width/precision too large (max 1000)")
                    }
                    i++
                }
            }
        }
        const val MAX_PATTERN_SUBJECT = 32 * 1024
        const val MAX_PATTERN_SUBJECT_LAZY = 16 * 1024

        private val currentString = ThreadLocal<LuaTable?>()

        private val sharedStringMeta: LuaTable = LuaTable().also { m ->
            m.set(LuaValue.INDEX, object : org.luaj.vm2.lib.TwoArgFunction() {
                override fun call(self: LuaValue, key: LuaValue): LuaValue = currentString.get()?.get(key) ?: LuaValue.NIL
            })
        }

        /** エラーを「script:line: message」形式の 1 本の文字列にする。 */
        fun formatError(t: Throwable): String = when (t) {
            is LuaError -> (t.message ?: "lua error").trim()
            is StackOverflowError -> "stack overflow"
            is OutOfMemoryError -> "memory limit exceeded"
            else -> "${t.javaClass.simpleName}: ${t.message ?: ""}".trim()
        }
    }
}
