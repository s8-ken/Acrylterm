package com.shibaken.androidterminal.ext

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputWatcherTest {
    @Test fun firstSnapshotIsBaseline() {
        val w = OutputWatcher()
        assertEquals(emptyList<String>(), w.update(listOf("a", "b")))
    }

    @Test fun returnsChangedNonBlankLines() {
        val w = OutputWatcher()
        w.update(listOf("a   ", "b", ""))
        assertEquals(listOf("B", "c"), w.update(listOf("a", "B", "c")))
        assertEquals(emptyList<String>(), w.update(listOf("a", "B", "c   ")))
        assertEquals(emptyList<String>(), w.update(listOf("a", "B", "")))
    }

    @Test fun growingScreenAndReset() {
        val w = OutputWatcher()
        w.update(listOf("a"))
        assertEquals(listOf("b"), w.update(listOf("a", "b")))
        w.reset()
        assertEquals(emptyList<String>(), w.update(listOf("x")))
    }
}
