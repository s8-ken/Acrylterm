package com.shibaken.androidterminal.ext

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.luaj.vm2.LuaError
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.jse.JsePlatform

/** 自前のパターンマッチャが LuaJ 標準の StringLib と同じ結果を返すことの確認。 */
class LuaPatternsTest {
    private val runtime = LuaRuntime(maxInstructions = 5_000_000, maxWallMillis = 5_000)

    @After fun tearDown() { runtime.close() }

    private fun render(v: Varargs): String = (1..v.narg()).joinToString("|") { v.arg(it).tojstring() }

    private val cases = listOf(
        "return string.find('hello world', 'wor')",
        "return string.find('hello world', 'o', 6)",
        "return string.find('hello world', 'o', -3)",
        "return string.find('hello', 'l+')",
        "return string.find('a.b', '.', 1, true)",
        "return string.find('a.b', '.', 2, true)",
        "return string.find('hello', '(h)(e)')",
        "return string.find('hello', '()ll()')",
        "return string.find('hello', '^h')",
        "return string.find('hello', '^e')",
        "return string.find('hello', 'o$')",
        "return string.find('hello', 'x')",
        "return string.find('', '')",
        "return string.find('abc', '', 10)",
        "return string.match('key=value', '(%w+)=(%w+)')",
        "return string.match('  trim  ', '^%s*(.-)%s*$')",
        "return string.match('f(a(b)c)d', '%b()')",
        "return string.match('abcabc', '(abc)%1')",
        "return string.match('2024-05-06', '(%d+)-(%d+)-(%d+)')",
        "return string.match('abc', '[a-b]+')",
        "return string.match('abc', '[^a]+')",
        "return string.match('x', 'a?x')",
        "return string.match('aaa', 'a-')",
        "return string.match('aaa', 'a-$')",
        "return string.gsub('hello world', 'o', '0')",
        "return string.gsub('hello world', '(%w+)', '<%1>')",
        "return string.gsub('hello world', '%w+', '%0 %0', 1)",
        "return string.gsub('hello world', '(%w+)%s*(%w+)', '%2 %1')",
        "return string.gsub('abc', '', '-')",
        "return string.gsub('abc', '^a', 'X')",
        "return string.gsub('\$name \$x', '%\$(%w+)', {name='N'})",
        "return string.gsub('abc', '%w', function(c) if c == 'b' then return false end return string.upper(c) end)",
        "return string.gsub('abc def', '%w+', function(w) return #w end)",
        "return string.gsub('a,b,c', ',', ';', 0)",
        "local t = {} for w in string.gmatch('one two three', '%a+') do t[#t+1] = w end return table.concat(t, ',')",
        "local t = {} for k, v in string.gmatch('a=1, b=2', '(%w+)=(%w+)') do t[#t+1] = k .. v end return table.concat(t, ',')",
        "return pcall(string.find, 'a', '(')",
        "return pcall(string.find, 'a', '%')",
        "return pcall(string.find, 'a', '[a')",
        "return pcall(string.match, 'a', '%1')",
        "return pcall(string.gsub, 'a', 'a', true)",
    )

    @Test fun matchesStockLuaJ() {
        // (%f は LuaJ 標準が壊れているため frontierAtEndOfSubject で別途確認する)
        // 標準側を先に全部評価する(StringLib のロードは static な string メタテーブルを上書きするため)
        val stock = cases.map { src ->
            try { render(JsePlatform.standardGlobals().load(src).invoke()) } catch (e: Exception) { "ERR" }
        }
        val ours = runBlocking {
            withContext(runtime.dispatcher) {
                val g = runtime.newGlobals()
                runtime.activate(g)
                cases.map { src ->
                    try { render(runtime.guarded { runtime.evaluate(g, "p.lua", src) }) } catch (e: LuaError) { "ERR" }
                }
            }
        }
        for (i in cases.indices) {
            // pcall の失敗メッセージは位置情報が異なり得るので、成否だけ比べる
            val pc = cases[i].contains("pcall")
            val a = if (pc) stock[i].substringBefore("|") else stock[i]
            val b = if (pc) ours[i].substringBefore("|") else ours[i]
            assertEquals(cases[i], a, b)
        }
    }

    @Test fun frontierAtEndOfSubject() {
        val r = runBlocking {
            withContext(runtime.dispatcher) {
                val g = runtime.newGlobals()
                runtime.activate(g)
                render(runtime.guarded { runtime.evaluate(g, "f.lua", "return string.match('THE (quick) fox', '%f[%a]%a+%f[%A]', 12), string.match('THE (quick) fox', '%f[%a]%a+%f[%A]'), string.find('ab', '%f[%z]')") })
            }
        }
        assertEquals("fox|THE|3|2", r)
    }

    @Test fun methodSyntaxUsesPatterns() {
        val r = runBlocking {
            withContext(runtime.dispatcher) {
                val g = runtime.newGlobals()
                runtime.activate(g)
                render(runtime.guarded { runtime.evaluate(g, "m.lua", "return ('hello world'):gsub('o', '0'), ('abc'):find('b', 1, true)") })
            }
        }
        assertEquals("hell0 w0rld|2|2", r)
    }

    @Test fun runawayPatternHitsDeadline() {
        val rt = LuaRuntime(maxWallMillis = 300)
        try {
            val t0 = System.currentTimeMillis()
            val err = runBlocking {
                withContext(rt.dispatcher) {
                    val g = rt.newGlobals()
                    rt.activate(g)
                    try {
                        rt.guarded { rt.evaluate(g, "r.lua", "return string.find(string.rep('a', 30000), 'a+b')") }
                        null
                    } catch (e: LuaError) { e.message }
                }
            }
            assertTrue("expected a time-limit error, got $err", err?.contains("time limit exceeded") == true)
            assertTrue(System.currentTimeMillis() - t0 < 3_000)
        } finally { rt.close() }
    }

    private fun evalErr(src: String): String? = runBlocking {
        withContext(runtime.dispatcher) {
            val g = runtime.newGlobals()
            runtime.activate(g)
            try { runtime.guarded { runtime.evaluate(g, "e.lua", src) }; null } catch (e: LuaError) { e.message }
        }
    }

    @Test fun overlongPatternsRejected() {
        val e = evalErr("return string.find('abc', string.rep('a', 5000))")
        assertTrue(e.toString(), e?.contains("pattern too long") == true)
        val t0 = System.currentTimeMillis()
        val e2 = evalErr("return string.find(string.rep('a', 30000), '[' .. string.rep('a', 900000) .. ']+b')")
        assertTrue(e2.toString(), e2?.contains("pattern too long") == true)
        assertTrue(System.currentTimeMillis() - t0 < 1_000)
        for (fn in listOf("match", "gmatch", "gsub")) {
            val r = evalErr("return string.$fn('abc', string.rep('a', 5000), 'x')")
            assertTrue(r.toString(), r?.contains("pattern too long") == true)
        }
    }

    @Test fun stackOverflowBecomesLuaError() {
        val string = org.luaj.vm2.LuaTable()
        LuaPatterns.install(string) {}
        val subject = org.luaj.vm2.LuaValue.valueOf("a".repeat(3000))
        val pat = org.luaj.vm2.LuaValue.valueOf("a?".repeat(3000))
        for (name in listOf("find", "match", "gsub", "gmatch")) {
            var result: Throwable? = null
            val th = Thread(null, {
                result = try {
                    val f = string.get(name)
                    val r = if (name == "gsub") f.invoke(org.luaj.vm2.LuaValue.varargsOf(subject, pat, org.luaj.vm2.LuaValue.valueOf("x")))
                            else f.invoke(org.luaj.vm2.LuaValue.varargsOf(subject, pat))
                    if (name == "gmatch") r.arg1().invoke()
                    null
                } catch (t: Throwable) { t }
            }, "small-stack", 48 * 1024L)
            th.start(); th.join()
            val t = result
            assertTrue("$name: $t", t is LuaError && t.message!!.contains("pattern too complex"))
        }
    }
}
