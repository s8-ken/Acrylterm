package com.shibaken.androidterminal.ext

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class FakeHost : ExtHost {
    val sent = CopyOnWriteArrayList<Pair<String, String>>()
    val keysSent = CopyOnWriteArrayList<Pair<String, List<String>>>()
    val logs = CopyOnWriteArrayList<String>()
    val toasts = CopyOnWriteArrayList<String>()
    val notes = CopyOnWriteArrayList<Triple<String, String, String?>>()
    val execs = CopyOnWriteArrayList<String>()
    var paneList = listOf(ExtPane("p1", "main", "claude", "idle", "/tmp"), ExtPane("p2", "b", null, "unknown", null))
    var focused: String? = "p1"
    override fun panes() = paneList
    override fun focusedPaneId() = focused
    override fun sendText(paneId: String, text: String) { sent += paneId to text }
    override fun sendKeys(paneId: String, keys: List<String>) { keysSent += paneId to keys }
    override fun readPane(paneId: String, lines: Int) = "screen:$paneId:$lines"
    override fun notify(title: String, body: String, paneId: String?) { notes += Triple(title, body, paneId) }
    override fun toast(text: String) { toasts += text }
    override fun exec(command: String, timeoutSec: Int): String {
        execs += command
        if (command == "fail") error("boom")
        return "out:$command"
    }
    override fun log(line: String) { logs += line }
}

class ExtEngineTest {
    private lateinit var host: FakeHost
    private lateinit var runtime: LuaRuntime
    private lateinit var engine: ExtEngine

    @Before fun setUp() {
        host = FakeHost()
        runtime = LuaRuntime(maxInstructions = 200_000, maxWallMillis = 5_000)
        engine = ExtEngine(host, runtime)
    }

    @After fun tearDown() { engine.close() }

    private fun load(src: String, name: String = "t.lua") = runBlocking { engine.reload(listOf(ExtScript(name, src))) }
    private fun idle() = runBlocking { withContext(runtime.dispatcher) {} }

    @Test fun sandboxHidesDangerousGlobals() {
        val r = load(
            """
            local bad = {}
            for _, n in ipairs({"io","require","package","luajava","dofile","loadfile","debug","coroutine","collectgarbage"}) do
              if _G[n] ~= nil then bad[#bad+1] = n end
            end
            if os.execute or os.exit or os.getenv or os.remove or os.rename or os.tmpname then bad[#bad+1] = "os.*" end
            if string.dump then bad[#bad+1] = "string.dump" end
            if not (os.time and os.clock and os.date) then bad[#bad+1] = "os.time missing" end
            if getmetatable("") ~= nil then bad[#bad+1] = "strmeta" end
            log("bad=" .. table.concat(bad, ","))
            """
        )
        assertTrue(r.errors.toString(), r.ok)
        assertEquals("bad=", host.logs.single())
    }

    @Test fun loadOnlyAcceptsStrings() {
        val r = load(
            """
            local f = load("return 1+1")
            log(f())
            local f2, err = load(function() return nil end)
            log(tostring(f2), (err or ""):sub(1, 4))
            local f3 = load("x = ", "=c")
            log(tostring(f3))
            """
        )
        assertTrue(r.errors.toString(), r.ok)
        assertEquals(listOf("2", "nil\tload", "nil"), host.logs.toList())
    }

    @Test fun runawayLoopTerminates() {
        val t0 = System.currentTimeMillis()
        val r = load("while true do end", "loop.lua")
        assertFalse(r.ok)
        assertTrue(r.errors.toString(), r.errors[0].contains("limit exceeded"))
        assertTrue(System.currentTimeMillis() - t0 < 4_000)
    }

    @Test fun runawayInsidePcallTerminates() {
        val r = load("while true do pcall(function() while true do end end) end")
        assertFalse(r.ok)
    }

    @Test fun errorHasScriptNameAndLine() {
        val r = load("local a = 1\nerror('bad thing')", "x.lua")
        assertFalse(r.ok)
        assertTrue(r.errors[0], r.errors[0].contains("x.lua:2") && r.errors[0].contains("bad thing"))
        val r2 = load("local = ", "syn.lua")
        assertTrue(r2.errors[0], r2.errors[0].startsWith("syn.lua"))
    }

    @Test fun registrationsAreCollected() {
        val r = load(
            """
            term.on_output("BUILD (%a+)", function() end)
            term.on_output("fail(ed)?", function() end)
            on("agent_status", function() end)
            on("connected", function() end)
            command("hello", function(ctx) end)
            keys.add("gst", "git status\r")
            keys.add("esc", {keys={"esc"}})
            keys.add("fn", function(p) end)
            quick_reply.rule("Proceed%?", {"Yes", {label="No", send="n\r"}})
            """.replace("%a", "\\w").replace("%?", "\\?")
        )
        assertTrue(r.errors.toString(), r.ok)
        val reg = engine.registry.value
        assertEquals(listOf("hello"), reg.commands.map { it.name })
        assertEquals(listOf("gst", "esc", "fn"), reg.keys.map { it.label })
        assertEquals(2, reg.outputTriggerPatterns.size)
        assertTrue(reg.hasOutputTriggers)
        assertEquals(listOf("agent_status", "connected"), reg.eventNames)
        val rule = reg.quickReplyRules.single()
        assertEquals(listOf(ExtChoice("Yes", "Yes\r"), ExtChoice("No", "n\r")), rule.choices)
    }

    @Test fun invalidRegexIsLuaError() {
        val r = load("term.on_output('(unclosed', function() end)")
        assertFalse(r.ok)
        assertTrue(r.errors[0], r.errors[0].contains("invalid regex"))
        assertTrue(load("on('nope', function() end)").errors[0].contains("unknown event"))
    }

    @Test fun outputTriggerMatchesAndPassesGroups() {
        load(
            """
            term.on_output("BUILD (\\w+) in (\\d+)s", function(pane, m)
              log(pane.id, m[0], m[1], m[2])
            end)
            """
        ).also { assertTrue(it.errors.toString(), it.ok) }
        engine.dispatchOutput("p2", listOf("nothing here", "BUILD FAILED in 12s"))
        idle()
        assertEquals(listOf("p2\tBUILD FAILED in 12s\tFAILED\t12"), host.logs.toList())
    }

    @Test fun commandInvocationSendsText() {
        load(
            """
            command("go", function(ctx)
              ctx.pane:send("ls\r")
              ctx.pane:send_keys({"enter"})
              log(ctx.pane:read(5), ctx.pane.agent, #panes())
              log(ssh.exec("uname"))
            end)
            """
        ).also { assertTrue(it.errors.toString(), it.ok) }
        engine.runCommand("go")
        idle()
        assertEquals(listOf("p1" to "ls\r"), host.sent.toList())
        assertEquals(listOf("p1" to listOf("enter")), host.keysSent.toList())
        assertEquals(listOf("screen:p1:5\tclaude\t2", "out:uname"), host.logs.toList())
    }

    @Test fun callbackErrorsAreReportedNotThrown() {
        load("command('x', function() error('oops') end) command('y', function() ssh.exec('fail') end)")
        engine.runCommand("x")
        engine.runCommand("y")
        idle()
        assertEquals(2, host.toasts.size)
        assertTrue(host.logs[0], host.logs[0].contains("oops"))
        assertTrue(host.logs[1], host.logs[1].contains("boom"))
    }

    @Test fun keysAndEvents() {
        load(
            """
            keys.add("a", "x")
            keys.add("b", {send="y", keys={"enter"}})
            keys.add("c", function(p) p:send("z") end)
            on("agent_status", function(p, s, prev) log(p.id, s, prev) end)
            on("connected", function() notify("t", "b") end)
            """
        )
        for (i in 0..2) engine.pressKey(i, "p2")
        engine.dispatchAgentStatus(ExtPane("p2", "b", "codex", "blocked", null), "blocked", "working")
        engine.dispatchConnected()
        idle()
        assertEquals(listOf("p2" to "x", "p2" to "y", "p2" to "z"), host.sent.toList())
        assertEquals(listOf("p2" to listOf("enter")), host.keysSent.toList())
        assertEquals(listOf("p2\tblocked\tworking"), host.logs.toList())
        assertEquals(listOf(Triple("t", "b", "p1")), host.notes.toList())
    }

    @Test fun quickReplyFirstRuleWins() {
        load(
            """
            quick_reply.rule("Continue\\?", {"A"})
            quick_reply.rule("Continue", {"B"})
            """
        )
        assertEquals("A", runBlocking { engine.matchQuickReply("foo\nContinue? (y/n)") }!![0].label)
        assertEquals("B", runBlocking { engine.matchQuickReply("Continue now") }!![0].label)
        assertNull(runBlocking { engine.matchQuickReply("nothing") })
    }

    @Test fun failedReloadKeepsPreviousRegistry() {
        load("command('one', function() end)").also { assertTrue(it.ok) }
        val bad = load("command('two', function() end)\nerror('x')")
        assertFalse(bad.ok)
        assertEquals(listOf("one"), engine.registry.value.commands.map { it.name })
        assertEquals(listOf("one"), bad.registry.commands.map { it.name })
        // old command still runs
        host.logs.clear()
        val ok = load("command('three', function() log('3') end)")
        assertTrue(ok.ok)
        assertEquals(listOf("three"), engine.registry.value.commands.map { it.name })
        engine.runCommand("three"); idle()
        assertEquals(listOf("3"), host.logs.toList())
    }

    @Test fun multipleScriptsAllErrorsReported() {
        val r = runBlocking {
            engine.reload(listOf(ExtScript("a.lua", "error('A')"), ExtScript("b.lua", "x = 1"), ExtScript("c.lua", "error('C')")))
        }
        assertEquals(2, r.errors.size)
        assertNotNull(r.errors.firstOrNull { it.startsWith("a.lua") })
    }

    @Test fun stringRepIsBounded() {
        assertFalse(load("local s = string.rep('x', 100000000)").ok)
        assertFalse(load("local s = ('x'):rep(100000000)").ok)
    }

    @Test fun patternMatchingRejectsHugeSubjects() {
        val r = load("local s = ('a'):rep(100000) log(pcall(string.find, s, '(.-)b'))")
        assertTrue(r.errors.toString(), r.ok)
        assertTrue(host.logs[0], host.logs[0].startsWith("false") && host.logs[0].contains("subject too long"))
        host.logs.clear()
        assertTrue(load("log(('abc'):find('b'), ('x-y'):gsub('-', '+'))").ok)
    }

    @Test fun catastrophicRegexTimesOutAsNoMatch() {
        val lr = load("term.on_output('(?:x|x?){30}z', function() log('hit') end) quick_reply.rule('(?:x|x?){30}z', {'x'})")
        assertTrue(lr.errors.toString(), lr.ok)
        val subject = "x".repeat(30) + "!z"
        val t0 = System.currentTimeMillis()
        engine.dispatchOutput("p1", listOf(subject)); idle()
        assertNull(runBlocking { engine.matchQuickReply(subject) })
        assertTrue(System.currentTimeMillis() - t0 < 3_000)
        assertTrue(host.logs.toString(), host.logs.count { it.startsWith("regex timed out") } == 1)
        assertFalse(host.logs.contains("hit"))
    }

    @Test fun memoryDoublingErrorsWithoutKillingRuntime() {
        val r = load("local s = 'x' for i = 1, 40 do s = s .. s end")
        assertFalse(r.ok)
        assertTrue(r.errors.toString(), r.errors[0].contains("string too long"))
        assertTrue(load("log('alive')").ok)
    }

    @Test fun memoryHookAbortsAllocationLoop() {
        System.gc()
        val rt = LuaRuntime(maxInstructions = 50_000_000, memoryLimitBytes = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() } + 48L * 1024 * 1024)
        val e = ExtEngine(host, rt)
        try {
            val r = runBlocking { e.reload(listOf(ExtScript("m.lua", "local t = {} for i = 1, 1e8 do t[i] = ('x'):rep(100000) .. i end"))) }
            assertFalse(r.ok)
            assertTrue(r.errors.toString(), r.errors[0].contains("memory limit"))
        } finally { e.close() }
    }

    @Test fun stringLibraryIsIsolatedBetweenRuntimes() {
        val host2 = FakeHost()
        val rt2 = LuaRuntime()
        val e2 = ExtEngine(host2, rt2)
        try {
            load("command('u', function() log(('x'):upper()) end)")
            runBlocking { e2.reload(listOf(ExtScript("h.lua", "string.upper = function() return 'HACK' end command('u', function() log(('x'):upper()) end)"))) }
            e2.runCommand("u"); engine.runCommand("u")
            idle(); runBlocking { withContext(rt2.dispatcher) {} }
            assertEquals(listOf("HACK"), host2.logs.toList())
            assertEquals(listOf("X"), host.logs.toList())
        } finally { e2.close() }
    }

    @Test fun concatAndFormatAndTableConcatAreCapped() {
        assertTrue(load("local s = 'x' for i = 1, 5 do s = s .. s end log(#s)").ok)
        assertFalse(load("local a = ('x'):rep(600000) local b = a .. a").ok)
        assertTrue(load("local a = ('x'):rep(600000) local ok, e = pcall(function() return a .. a .. 'y' end) log(ok, e)").ok)
        assertTrue(host.logs.last(), host.logs.last().startsWith("false") && host.logs.last().contains("string too long"))
        // frames stay consistent after an error inside nested calls
        assertTrue(load("local a = ('x'):rep(600000) pcall(function() return a .. a end) local t = 'ok' .. 1 log(t)").ok)
        assertFalse(load("return string.format('%99999d', 1)").ok)
        assertTrue(load("log(string.format('%5.2f|%-10s|%%', 1.5, 'a'))").ok)
        assertFalse(load("local a = ('x'):rep(600000) local t = {a, a} return table.concat(t)").ok)
        assertTrue(load("log(table.concat({'a','b'}, ','))").ok)
    }

    @Test fun lazyPatternsOnLargeSubjectsRejected() {
        assertFalse(load("return (('a'):rep(20000)):find('(.-)b')").ok)
        assertTrue(load("return (('a'):rep(20000)):find('b')").ok)
        val t0 = System.currentTimeMillis()
        load("return (('a'):rep(32000)):find('a+b')")
        assertTrue(System.currentTimeMillis() - t0 < 5_000)
    }

    // ---- timer ----

    private fun timerEngine(): ExtEngine {
        engine.close()
        runtime = LuaRuntime(maxInstructions = 200_000, maxWallMillis = 5_000)
        engine = ExtEngine(host, runtime, minAfterSec = 0.02, minEverySec = 0.05)
        return engine
    }

    private fun waitFor(ms: Long = 1500, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(5) }
        return cond()
    }

    private fun count(tag: String) = host.logs.count { it == tag }

    @Test fun timerAfterFiresOnce() {
        timerEngine()
        assertTrue(load("timer.after(0.03, function() log('A') end)").ok)
        assertTrue(waitFor { count("A") == 1 })
        Thread.sleep(150)
        assertEquals(1, count("A"))
    }

    @Test fun timerEveryRepeatsAndCancelFromOutside() {
        timerEngine()
        assertTrue(load("id = timer.every(0.05, function() log('E') end)\ncommand('stop', function() log(tostring(timer.cancel(id)), tostring(timer.cancel(id))) end)").ok)
        assertTrue(waitFor { count("E") >= 3 })
        engine.runCommand("stop"); idle()
        assertTrue(host.logs.toString(), host.logs.contains("true\tfalse"))
        val n = count("E")
        Thread.sleep(200)
        assertEquals(n, count("E"))
    }

    @Test fun timerCancelFromInsideOwnCallback() {
        timerEngine()
        assertTrue(load("n = 0\nid = timer.every(0.05, function() n = n + 1; log('C'); log(tostring(timer.cancel(id))) end)").ok)
        assertTrue(waitFor { host.logs.contains("true") })
        Thread.sleep(250)
        assertEquals(1, count("C"))
    }

    @Test fun timerLimit32() {
        timerEngine()
        val r = load("for i = 1, 32 do timer.after(100, function() end) end\nlocal ok, e = pcall(timer.after, 100, function() end)\nlog(tostring(ok), tostring(e))")
        assertTrue(r.errors.toString(), r.ok)
        assertTrue(host.logs.toString(), host.logs.single().contains("too many timers"))
    }

    @Test fun timerArgChecksAndClamping() {
        timerEngine()
        val r = load(
            """
            for _, f in ipairs({
              function() timer.after("x", function() end) end,
              function() timer.after(0/0, function() end) end,
              function() timer.every(1, 5) end,
              function() timer.cancel("a") end,
            }) do log(tostring(pcall(f))) end
            """
        )
        assertTrue(r.errors.toString(), r.ok)
        assertEquals(listOf("false", "false", "false", "false"), host.logs.toList())
        assertEquals(100L, TimerSet.clampMs(0.0, false))
        assertEquals(1000L, TimerSet.clampMs(0.5, true))
        assertEquals(1500L, TimerSet.clampMs(1.5, true))
        assertEquals(86_400_000L, TimerSet.clampMs(1e12, true))
        assertEquals(86_400_000L, TimerSet.clampMs(Double.POSITIVE_INFINITY, false))
    }

    @Test fun reloadCancelsOldTimers() {
        timerEngine()
        assertTrue(load("timer.every(0.05, function() log('OLD') end)").ok)
        assertTrue(waitFor { count("OLD") >= 1 })
        assertTrue(load("-- none").ok)
        idle()
        val n = count("OLD")
        Thread.sleep(250)
        assertEquals(n, count("OLD"))
    }

    @Test fun failedReloadKeepsOldTimersAndDropsNewOnes() {
        timerEngine()
        assertTrue(load("timer.every(0.05, function() log('OLD') end)").ok)
        val r = load("timer.every(0.05, function() log('NEW') end)\nerror('bad')")
        assertFalse(r.ok)
        val n = count("OLD")
        assertTrue(waitFor { count("OLD") >= n + 2 })
        assertEquals(0, count("NEW"))
    }

    @Test fun everyTimerAutoCancelsAfterFiveConsecutiveErrors() {
        timerEngine()
        assertTrue(load("timer.every(0.05, function() error('x') end)").ok)
        assertTrue(waitFor { host.logs.any { it.contains("cancelled after 5") } })
        assertEquals(5, host.logs.count { it.startsWith("error in timer.every") })
        Thread.sleep(200)
        assertEquals(5, host.logs.count { it.startsWith("error in timer.every") })
    }

    @Test fun everyTimerKeepsRunningAfterIsolatedError() {
        timerEngine()
        assertTrue(load("n = 0\ntimer.every(0.05, function() n = n + 1; if n % 2 == 1 then error('x') end; log('OK') end)").ok)
        assertTrue(waitFor { count("OK") >= 3 })
        assertFalse(host.logs.any { it.contains("cancelled after") })
    }

    @Test fun closeCancelsTimers() {
        timerEngine()
        assertTrue(load("timer.every(0.05, function() log('T') end)").ok)
        assertTrue(waitFor { count("T") >= 1 })
        engine.close()
        Thread.sleep(100)
        val n = count("T")
        Thread.sleep(200)
        assertEquals(n, count("T"))
    }
}
