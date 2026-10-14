package com.shibaken.androidterminal.ext

import org.junit.Assert.assertEquals
import org.junit.Test

class ExtAgentsTest {
    @Test fun kindOf() {
        assertEquals(ExtAgents.Kind.CLAUDE, ExtAgents.kindOf("claude -p"))
        assertEquals(ExtAgents.Kind.CLAUDE, ExtAgents.kindOf(" claude -p "))
        assertEquals(ExtAgents.Kind.CODEX, ExtAgents.kindOf("codex exec --skip-git-repo-check -"))
        assertEquals(ExtAgents.Kind.CUSTOM, ExtAgents.kindOf("claude -p --model x"))
        assertEquals(ExtAgents.Kind.CUSTOM, ExtAgents.kindOf(""))
    }
}
