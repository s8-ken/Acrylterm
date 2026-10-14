package com.shibaken.androidterminal

import com.shibaken.androidterminal.ssh.RemoteCommand
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCommandTest {
    @Test fun missingWhenNoVersion() {
        assertTrue(RemoteCommand.isHerdrMissing(""))
        assertTrue(RemoteCommand.isHerdrMissing("  \n"))
        assertTrue(RemoteCommand.isHerdrMissing("sh: herdr: command not found"))
    }

    @Test fun presentWhenVersionParses() {
        assertFalse(RemoteCommand.isHerdrMissing("herdr 0.9.3"))
        assertFalse(RemoteCommand.isHerdrMissing("0.6.0\n"))
    }

    @Test fun invokesHerdr() {
        assertTrue(RemoteCommand.invokesHerdr("herdr"))
        assertTrue(RemoteCommand.invokesHerdr("  herdr --foo"))
        assertTrue(RemoteCommand.invokesHerdr("/usr/local/bin/herdr"))
        assertTrue(RemoteCommand.invokesHerdr("~/.local/bin/herdr attach"))
        assertTrue(RemoteCommand.invokesHerdr("herdr\tx"))
    }

    @Test fun doesNotInvokeHerdr() {
        assertFalse(RemoteCommand.invokesHerdr(""))
        assertFalse(RemoteCommand.invokesHerdr("   "))
        assertFalse(RemoteCommand.invokesHerdr("tmux a"))
        assertFalse(RemoteCommand.invokesHerdr("herdrx"))
        assertFalse(RemoteCommand.invokesHerdr("echo herdr"))
        assertFalse(RemoteCommand.invokesHerdr("my-herdr"))
    }
}
