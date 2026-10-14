package com.shibaken.androidterminal.ext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtSyncTest {
    @Test fun parseBundle_splitsFilesAndKeepsContentExact() {
        val out = "\n--@@FILE:n1 b.lua\nlog('b')\n\n--@@FILE:n1 a.lua\nlog('a')"
        val r = ExtSync.parseBundle(out, "n1")
        assertEquals(listOf("a.lua", "b.lua"), r.map { it.name })
        assertEquals("log('a')", r[0].source)
        assertEquals("log('b')\n", r[1].source)
    }

    @Test fun parseBundle_ignoresSpoofedMarkerInContent() {
        val out = "\n--@@FILE:n1 a.lua\nx\n--@@FILE evil.lua\ny\n--@@FILE:zz evil2.lua\nz"
        val r = ExtSync.parseBundle(out, "n1")
        assertEquals(listOf("a.lua"), r.map { it.name })
    }

    @Test fun parseBundle_emptyAndInvalidNames() {
        assertTrue(ExtSync.parseBundle("", "n1").isEmpty())
        assertTrue(ExtSync.parseBundle("\n--@@FILE:n1 ../x.lua\nfoo", "n1").isEmpty())
    }

    @Test fun writeCommands_chunks() {
        val cmds = ExtSync.writeCommands("log.txt", "x".repeat(100), chunkBytes = 40)
        assertEquals(3, cmds.size)
        assertTrue(cmds[0].contains("> \"\$d/.app/log.txt\""))
        assertTrue(cmds[1].contains(">> "))
    }

    @Test fun commandsAreShWrapped() {
        assertTrue(ExtSync.fetchCommand("n1").startsWith("sh -c '"))
        assertTrue(ExtSync.CKSUM.contains("cksum"))
    }

    @Test fun writeScriptCommands_mkdirAndMv() {
        val cmds = ExtSync.writeScriptCommands("a.lua", "x".repeat(100), chunkBytes = 40)
        assertEquals(3, cmds.size)
        assertTrue(cmds.all { it.startsWith("sh -c '") && it.contains("mkdir -p") })
        assertTrue(cmds[0].contains("> \"\$d/.app/a.lua.part\""))
        assertTrue(cmds[1].contains(">> "))
        assertTrue(cmds.dropLast(1).none { it.contains("mv -f") })
        assertTrue(cmds.last().contains("mv -f \"\$d/.app/a.lua.part\" \"\$d/a.lua\""))
    }

    @Test fun writeScriptCommands_rejectsInvalidNames() {
        for (n in listOf("../x.lua", "a b.lua", "a.txt", "", "a/b.lua", ".lua")) {
            val r = runCatching { ExtSync.writeScriptCommands(n, "x") }
            assertTrue("should reject $n", r.isFailure)
        }
    }

    @Test fun generatePrompt_newAndModify() {
        val p = ExtSync.generatePrompt("k.lua", "add a key", "API-DOC", null)
        assertTrue(p.contains("k.lua") && p.contains("API-DOC") && p.contains("add a key"))
        assertTrue(p.contains("Output ONLY the complete Lua source code"))
        assertTrue(!p.contains("Modify this script"))
        val m = ExtSync.generatePrompt("k.lua", "fix", "API-DOC", "log('old')")
        assertTrue(m.contains("Modify this script per the request") && m.contains("log('old')"))
    }

    @Test fun generateCommand_wrapsAndEncodesPrompt() {
        val prompt = "it's \"quoted\" \$HOME\n日本語"
        val cmd = ExtSync.generateCommand("claude -p", prompt, "n9")
        assertTrue(cmd.startsWith("sh -c '"))
        assertTrue(cmd.contains("cd \"\$HOME\""))
        assertTrue(cmd.contains("| claude -p 2>"))
        assertTrue(cmd.contains("--@@EXIT:n9 "))
        val b64 = java.util.Base64.getEncoder().encodeToString(prompt.toByteArray(Charsets.UTF_8))
        assertTrue(cmd.contains(b64))
        assertTrue(runCatching { ExtSync.generateCommand("  ", prompt, "n9") }.isFailure)
    }

    @Test fun parseGenerateOutput_ok() {
        val r = ExtSync.parseGenerateOutput("log('x')\n\n--@@EXIT:n1 0\n", "n1")
        assertEquals(0, r.exitCode)
        assertEquals("log('x')\n", r.stdout)
        assertEquals("", r.stderr)
    }

    @Test fun parseGenerateOutput_failureWithStderr() {
        val r = ExtSync.parseGenerateOutput("partial\n--@@EXIT:n1 127\nclaude: not found\n", "n1")
        assertEquals(127, r.exitCode)
        assertEquals("partial", r.stdout)
        assertEquals("claude: not found", r.stderr)
    }

    @Test fun parseGenerateOutput_missingOrSpoofedMarker() {
        assertEquals(null, ExtSync.parseGenerateOutput("some output", "n1").exitCode)
        assertEquals(null, ExtSync.parseGenerateOutput("x\n--@@EXIT:other 0\n", "n1").exitCode)
    }

    @Test fun extractLua_variants() {
        assertEquals("log('a')\n", ExtSync.extractLua("  log('a')\n\n\n"))
        assertEquals("log('a')\n", ExtSync.extractLua("Here:\n```lua\nlog('a')\n```\nDone"))
        assertEquals("x = 1\n", ExtSync.extractLua("```\nx = 1\n```"))
        assertEquals("long = 1\nlong = 2\n", ExtSync.extractLua("```lua\na\n```\n```lua\nlong = 1\nlong = 2\n```"))
        assertEquals("", ExtSync.extractLua("  \n "))
    }
}
