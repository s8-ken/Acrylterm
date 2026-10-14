package com.shibaken.androidterminal.ext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtSummaryTest {
    @Test fun header_parsed() {
        val s = ExtSummary.parse("\n\n--[[acrylterm\nsummary: git status を送る\nusage:\n- gst キーを押す\n- /x を実行\n]]\nlog('a')")
        assertEquals("git status を送る", s.summary)
        assertEquals(listOf("gst キーを押す", "/x を実行"), s.usage)
    }

    @Test fun legacy_header_parsed() {
        val s = ExtSummary.parse("--[[veildeck\nsummary: old\n]]")
        assertEquals("old", s.summary)
    }

    @Test fun header_absent_or_not_first() {
        assertNull(ExtSummary.parse("log('x')").summary)
        val s = ExtSummary.parse("log('x')\n--[[acrylterm\nsummary: late\n]]")
        assertNull(s.summary)
        assertTrue(s.usage.isEmpty())
    }

    @Test fun header_malformed_is_lenient() {
        val unclosed = ExtSummary.parse("--[[acrylterm\nsummary: oops\nkeys.add(\"a\", f)")
        assertNull(unclosed.summary)
        assertTrue(unclosed.keys.isEmpty()) // unclosed block comment swallows the rest, as Lua would
        val noSummary = ExtSummary.parse("--[[acrylterm\nusage:\n* one\n]]")
        assertNull(noSummary.summary)
        assertEquals(listOf("one"), noSummary.usage)
    }

    @Test fun static_extraction() {
        val src = """
            keys.add("gst", function() end)
            keys.add('clr', function() end)
            command("deploy", function() end)
            term.on_output("error", function() end)
            on("agent_status", function() end)
            on("connected", function() end)
            quick_reply.rule("x", {})
            timer.after(5, f)
            timer.every(10, f)
        """.trimIndent()
        val s = ExtSummary.parse(src)
        assertEquals(listOf("gst", "clr"), s.keys)
        assertEquals(listOf("deploy"), s.commands)
        assertEquals(1, s.triggers)
        assertEquals(listOf("agent_status", "connected"), s.events)
        assertEquals(1, s.quickReplies)
        assertEquals(2, s.timers)
    }

    @Test fun commented_out_code_ignored() {
        val src = "-- keys.add(\"no\", f)\nkeys.add(\"yes\", f) -- timer.after(1, f)\n--[[ command(\"x\", f) ]]\nlocal s = \"a -- b\"; keys.add(\"z\", f)"
        val s = ExtSummary.parse(src)
        assertEquals(listOf("yes", "z"), s.keys)
        assertTrue(s.commands.isEmpty())
        assertEquals(0, s.timers)
    }

    @Test fun lookalike_names_not_matched() {
        val src = "mykeys.add(\"no\", f)\nxcommand(\"no\", f)\nobj:command(\"no\", f)\nkeys.add(\"yes\", f)\ncommand(\"cmd\", f)"
        val s = ExtSummary.parse(src)
        assertEquals(listOf("yes"), s.keys)
        assertEquals(listOf("cmd"), s.commands)
    }
}

class ExtSummaryTagsTest {
    @Test fun mixed_script_counts() {
        val s = ExtSummary.parse("""
            keys.add("a", function() end)
            keys.add("b", function() end)
            command("c", function() end)
            term.on_output("err", function() end)
            quick_reply.rule("x", {})
        """.trimIndent())
        assertEquals(3, s.macroCount)
        assertTrue(s.hasAutomation)
        assertTrue(s.hasQuickReplies)
    }

    @Test fun comment_only_mentions_ignored() {
        val s = ExtSummary.parse("""
            -- keys.add("a", f)
            --[[ command("c", f)
            term.on_output("x", f)
            quick_reply.rule("y", {}) ]]
            local x = 1
        """.trimIndent())
        assertEquals(0, s.macroCount)
        assertFalse(s.hasAutomation)
        assertFalse(s.hasQuickReplies)
    }

    @Test fun button_is_not_on_event() {
        val s = ExtSummary.parse("""
            local function button(x) end
            button("go")
            obj:on("evt", f)
            function on(z) end
        """.trimIndent())
        assertFalse(s.hasAutomation)
    }

    @Test fun on_event_and_timers_are_automation() {
        assertTrue(ExtSummary.parse("on(\"connected\", f)").hasAutomation)
        assertTrue(ExtSummary.parse("timer.every(5, f)").hasAutomation)
        assertTrue(ExtSummary.parse("timer.after(5, f)").hasAutomation)
        assertEquals(1, ExtSummary.parse("command('x', f)").macroCount)
    }
}
