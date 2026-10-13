package com.shibaken.androidterminal

import com.shibaken.androidterminal.ssh.MoshBootstrap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoshBootstrapTest {
    @Test fun parsesConnectAndPid() {
        val out = "\n\nMOSH CONNECT 60001 AbCdEf0123456789abcdef\n\nmosh-server (mosh 1.4.0)\n[mosh-server detached, pid = 4242]\n"
        assertEquals(MoshBootstrap.Info(60001, "AbCdEf0123456789abcdef", 4242), MoshBootstrap.parse(out))
    }

    @Test fun pidOptionalAndFailures() {
        assertEquals(MoshBootstrap.Info(60002, "k", null), MoshBootstrap.parse("MOSH CONNECT 60002 k"))
        assertNull(MoshBootstrap.parse("zsh: command not found: mosh-server"))
        assertNull(MoshBootstrap.parse("MOSH CONNECT 99999 k"))
    }

    @Test fun commandConstruction() {
        val c = MoshBootstrap.serverCommand("android", "t'1")
        assertTrue(c.startsWith("env MOSH_SERVER_NETWORK_TMOUT=3600 mosh-server new -s -c 256 -l LANG=en_US.UTF-8 -- env HERDR_SESSION='android' herdr terminal attach "))
        assertTrue(c.contains("'t'\\''1' --takeover"))
        assertTrue(c.endsWith(" 2>&1"))
    }

    @Test fun targetSelection() {
        assertEquals(MoshBootstrap.Target("100.64.0.5", true), MoshBootstrap.chooseTarget("100.64.0.9 5555 100.64.0.5 22", "mac-mini"))
        assertEquals(MoshBootstrap.Target("mac-mini", false), MoshBootstrap.chooseTarget("127.0.0.1 5555 127.0.0.1 22", "mac-mini"))
        assertEquals(MoshBootstrap.Target("::1", false), MoshBootstrap.chooseTarget("::1 5 ::1 22", "[::1]"))
        assertEquals(MoshBootstrap.Target("example.org", false), MoshBootstrap.chooseTarget("", "example.org"))
        assertEquals("fe80::1", MoshBootstrap.parseServerIp("a 1 fe80::1 22"))
    }

    @Test fun noBindFlagAndKill() {
        assertTrue(!MoshBootstrap.serverCommand("s", "t", false).contains(" -s "))
        assertTrue(MoshBootstrap.serverCommand("s", "t", true, "C.UTF-8").contains("new -s -c 256 -l LANG=C.UTF-8 --"))
        assertEquals("ps -p 42 -o comm= | grep -q mosh-server && kill 42; true", MoshBootstrap.killCommand(42))
        assertTrue(MoshBootstrap.isLocaleError("mosh-server needs a UTF-8 native locale to run"))
    }
}
