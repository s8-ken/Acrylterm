package com.shibaken.androidterminal.ext

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExtTemplatesTest {
    private val templates = listOf(
        ExtTemplates.Template("b.lua", "B"),
        ExtTemplates.Template("a.lua", "A"),
        ExtTemplates.Template("c.lua", "C"),
    )
    private val base = listOf(ExtScript("mine.lua", "M"), ExtScript("host:x.lua", "X"))

    @Test fun emptySetKeepsBaseOrder() {
        assertEquals(base, ExtTemplates.merge(templates, emptySet(), base))
    }

    @Test fun enabledTemplatesComeFirstSortedWithPrefix() {
        val r = ExtTemplates.merge(templates, setOf("c.lua", "a.lua"), base)
        assertEquals(listOf("template:a.lua", "template:c.lua", "mine.lua", "host:x.lua"), r.map { it.name })
        assertEquals("A", r[0].source)
    }

    @Test fun unknownNamesAreIgnored() {
        val r = ExtTemplates.merge(templates, setOf("gone.lua", "b.lua"), base)
        assertEquals(listOf("template:b.lua", "mine.lua", "host:x.lua"), r.map { it.name })
    }

    @Test fun copyNameAvoidsCollisions() {
        assertEquals("git.lua", ExtTemplates.copyName("git.lua", listOf("x.lua")))
        assertEquals("git-2.lua", ExtTemplates.copyName("git.lua", listOf("git.lua")))
        assertEquals("git-3.lua", ExtTemplates.copyName("git.lua", listOf("git.lua", "git-2.lua")))
        assertTrue(ScriptStore.isValidName(ExtTemplates.copyName("git.lua", listOf("git.lua"))))
    }

    // 同梱の全テンプレートが実エンジンで読み込め、要約ヘッダを持つこと。
    @Test fun bundledTemplatesLoadAndHaveSummary() {
        val dir = listOf("src/main/assets/ext/templates", "app/src/main/assets/ext/templates").map(::File).first { it.isDirectory }
        val files = dir.listFiles { f -> f.name.endsWith(".lua") }!!.sortedBy { it.name }
        assertEquals(6, files.size)
        for (f in files) {
            assertTrue(f.name, ScriptStore.isValidName(f.name))
            val src = f.readText()
            assertNotNull("summary: ${f.name}", ExtSummary.parse(src).summary)
            val host = FakeHost()
            val engine = ExtEngine(host, LuaRuntime(maxInstructions = 200_000, maxWallMillis = 5_000))
            try {
                val r = runBlocking { engine.reload(listOf(ExtScript(ExtTemplates.loadName(f.name), src))) }
                assertTrue("${f.name}: ${r.errors}", r.ok)
            } finally {
                engine.close()
            }
        }
    }
}
