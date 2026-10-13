package com.shibaken.androidterminal.ssh

/** Pure helpers for starting `mosh-server` over SSH and reading its handshake line. */
object MoshBootstrap {
    data class Info(val port: Int, val key: String, val pid: Int?)

    /** Where to dial and whether mosh-server should bind to the SSH server address (`-s`). */
    data class Target(val host: String, val bindToSshAddress: Boolean)

    /** 3rd field of `$SSH_CONNECTION` ("clientIp clientPort serverIp serverPort"), or null. */
    fun parseServerIp(sshConnection: String): String? =
        sshConnection.trim().split(Regex("\\s+")).getOrNull(2)?.takeIf { it.isNotBlank() }

    fun isLoopback(ip: String): Boolean {
        val x = ip.trim().trim('[', ']').lowercase()
        return x.startsWith("127.") || x == "::1" || x == "localhost" || x == "0:0:0:0:0:0:0:1"
    }

    fun stripBrackets(host: String): String = host.trim().removePrefix("[").removeSuffix("]")

    /** Non-loopback server address from SSH_CONNECTION: use `-s` and dial it; otherwise no `-s` and dial the profile host. */
    fun chooseTarget(sshConnection: String, profileHost: String): Target {
        val ip = parseServerIp(sshConnection)
        return if (ip != null && !isLoopback(ip)) Target(stripBrackets(ip), true) else Target(stripBrackets(profileHost), false)
    }

    /** mosh-server command that runs the herdr attach for one pane. Pass through [RemoteCommand.viaLoginShell]. */
    fun serverCommand(session: String, terminalId: String, bindToSshAddress: Boolean = true, locale: String = LOCALES[0]): String =
        "env MOSH_SERVER_NETWORK_TMOUT=$NETWORK_TMOUT_SEC mosh-server new ${if (bindToSshAddress) "-s " else ""}-c 256 -l LANG=$locale -- " +
            RemoteCommand.herdrAttach(session, terminalId) + " 2>&1"

    /** Locales to try in order (macOS older than recent releases lacks C.UTF-8). */
    val LOCALES = listOf("en_US.UTF-8", "C.UTF-8")
    const val NETWORK_TMOUT_SEC = 3600

    fun isLocaleError(output: String): Boolean = Regex("locale", RegexOption.IGNORE_CASE).containsMatchIn(output)

    /** Kills [pid] only while it is still a mosh-server. */
    fun killCommand(pid: Int): String = "ps -p $pid -o comm= | grep -q mosh-server && kill $pid; true"

    private val CONNECT = Regex("""MOSH CONNECT (\d+) (\S+)""")
    private val PID = Regex("""detached, pid = (\d+)""")

    /** Parses mosh-server's output (stdout+stderr); null when there is no `MOSH CONNECT` line. */
    fun parse(output: String): Info? {
        val m = CONNECT.find(output) ?: return null
        val port = m.groupValues[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val pid = PID.find(output)?.groupValues?.get(1)?.toIntOrNull()
        return Info(port, m.groupValues[2], pid)
    }
}
