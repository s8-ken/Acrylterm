package com.shibaken.androidterminal.ssh

import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.connection.Connection
import net.schmizz.sshj.connection.channel.direct.AbstractDirectChannel

/**
 * OpenSSH's "direct-streamlocal@openssh.com" channel: connects to a Unix domain socket on the
 * server (the equivalent of `ssh -L localport:/path/to/socket`). Used to reach herdr's
 * socket API (~/.config/herdr/herdr.sock) without running any helper on the Mac.
 *
 * Request payload (PROTOCOL in OpenSSH): string socket path, string reserved, uint32 reserved.
 * The server must not have `AllowStreamLocalForwarding no` (the default is yes).
 */
class DirectStreamLocalChannel(
    conn: Connection,
    private val socketPath: String,
) : AbstractDirectChannel(conn, "direct-streamlocal@openssh.com") {

    override fun buildOpenReq(): SSHPacket =
        super.buildOpenReq()
            .putString(socketPath)
            .putString("")
            .putUInt32(0)
}
