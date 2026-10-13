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
}
