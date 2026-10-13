package com.shibaken.androidterminal.ext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScriptStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun nameValidation() {
        assertTrue(ScriptStore.isValidName("a.lua"))
        assertTrue(ScriptStore.isValidName("my-script_1.lua"))
        for (bad in listOf("a.txt", "../a.lua", "x/a.lua", "x\\a.lua", ".hidden.lua", "a..lua", "", ".lua", "a b.lua")) {
            assertFalse(bad, ScriptStore.isValidName(bad))
        }
        val s = ScriptStore(tmp.root)
        var threw = false
        try { s.writeGlobal("../evil.lua", "x") } catch (_: IllegalArgumentException) { threw = true }
        assertTrue(threw)
        threw = false
        try { s.listHost("../x") } catch (_: IllegalArgumentException) { threw = true }
        assertTrue(threw)
    }

    @Test fun globalCrudAndOrder() {
        val s = ScriptStore(tmp.root)
        s.writeGlobal("b.lua", "B")
        s.writeGlobal("a.lua", "A")
        assertEquals(listOf("a.lua", "b.lua"), s.listGlobal())
        assertEquals("A", s.readGlobal("a.lua"))
        s.writeGlobal("a.lua", "A2")
        assertEquals("A2", s.readGlobal("a.lua"))
        assertTrue(s.deleteGlobal("b.lua"))
        assertNull(s.readGlobal("b.lua"))
    }

    @Test fun hostReplaceAndLoadOrder() {
        val s = ScriptStore(tmp.root)
        s.writeGlobal("z.lua", "G")
        s.replaceHost("h1", listOf(ExtScript("b.lua", "HB"), ExtScript("a.lua", "HA"), ExtScript("../x.lua", "bad")))
        assertEquals(listOf("a.lua", "b.lua"), s.listHost("h1"))
        assertEquals(listOf("z.lua", "host:a.lua", "host:b.lua"), s.loadOrder("h1", true).map { it.name })
        assertEquals(listOf("z.lua"), s.loadOrder("h1", false).map { it.name })
        s.replaceHost("h1", listOf(ExtScript("c.lua", "HC")))
        assertEquals(listOf("c.lua"), s.listHost("h1"))
        assertEquals(listOf("z.lua"), s.loadOrder("other", true).map { it.name })
    }
}
