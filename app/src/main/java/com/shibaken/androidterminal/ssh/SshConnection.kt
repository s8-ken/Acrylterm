package com.shibaken.androidterminal.ssh

import com.shibaken.androidterminal.App
import com.shibaken.androidterminal.data.AuthMethod
import com.shibaken.androidterminal.data.HostProfile
import com.shibaken.androidterminal.data.KnownHost
import com.shibaken.androidterminal.keys.SshKeys
import com.shibaken.androidterminal.session.Prompter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.connection.channel.direct.PTYMode
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.PasswordResponseProvider
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.IOException
import java.security.PublicKey
import java.util.concurrent.TimeUnit

class SshCancelledException(message: String) : IOException(message)

/** An open, authenticated SSH connection for one host profile. */
class SshConnection(private val app: App, val profile: HostProfile) {

    private val config = DefaultConfig().apply {
        keepAliveProvider = KeepAliveProvider.KEEP_ALIVE
    }
    val client = SSHClient(config)
    private var forwardPort: Int? = null

    val isConnected: Boolean get() = client.isConnected && client.isAuthenticated

    /** Connects and authenticates. [status] receives human-readable progress. */
    suspend fun connect(status: (String) -> Unit) = withContext(Dispatchers.IO) {
        client.addHostKeyVerifier(Verifier())
        client.connectTimeout = 20_000
        // Leave time for the user to answer the host-key / passphrase dialogs during kex & auth.
        client.transport.timeoutMs = 120_000

        val (host, port) = if (profile.useTailscale) {
            status("Tailscale を準備中…")
            app.tailscale.awaitRunning()
            val p = app.tailscale.forward(profile.host, profile.port)
            forwardPort = p
            "127.0.0.1" to p
        } else {
            profile.host to profile.port
        }

        status("${profile.host}:${profile.port} に接続中…")
        // sshj starts the keepalive thread inside connect(), only if the interval is already set.
        client.connection.keepAlive.keepAliveInterval = 15
        client.connect(host, port)
        // The loopback listener is only needed for the initial connect.
        forwardPort?.let { app.tailscale.stopForward(it); forwardPort = null }

        status("認証中…")
        authenticate()
        client.transport.timeoutMs = 30_000
        app.hosts.markConnected(profile.id)
    }

    private suspend fun authenticate() {
        val user = profile.username
        when (profile.authMethod) {
            AuthMethod.KEY -> {
                val keyId = profile.keyId ?: throw IOException("このホストに SSH 鍵が設定されていません")
                val entry = app.keys.get(keyId) ?: throw IOException("設定された SSH 鍵が見つかりません")
                if (entry.requireBiometric) {
                    val ok = Prompter.biometric("SSH 鍵を使用", "「${entry.name}」で ${profile.displayName} に接続します")
                    if (!ok) throw SshCancelledException("生体認証がキャンセルされました")
                }
                var passphrase: CharArray? = null
                if (entry.hasPassphrase && !entry.passphraseSaved) {
                    val p = Prompter.secret("鍵のパスフレーズ", "「${entry.name}」のパスフレーズを入力してください", "パスフレーズ")
                        ?: throw SshCancelledException("キャンセルされました")
                    passphrase = p.toCharArray()
                }
                val provider = app.keys.keyProvider(keyId, passphrase)
                withContext(Dispatchers.IO) { client.authPublickey(user, provider) }
            }
            AuthMethod.PASSWORD -> {
                val pw = Prompter.secret("パスワード", "${profile.displayName} のパスワード", "パスワード")
                    ?: throw SshCancelledException("キャンセルされました")
                withContext(Dispatchers.IO) {
                    try {
                        client.authPassword(user, pw.toCharArray())
                    } catch (e: UserAuthException) {
                        // macOS/PAM setups often only offer keyboard-interactive.
                        client.auth(
                            user,
                            AuthKeyboardInteractive(PasswordResponseProvider(PasswordUtils.createOneOff(pw.toCharArray()))),
                        )
                    }
                }
            }
        }
    }

    private fun newPtySession(cols: Int, rows: Int, widthPx: Int, heightPx: Int): Session {
        val session = client.startSession()
        try {
            session.setEnvVar("LANG", "ja_JP.UTF-8")
        } catch (_: Exception) {
            // sshd may not AcceptEnv LANG; not fatal.
        }
        session.allocatePTY("xterm-256color", cols, rows, widthPx, heightPx, emptyMap<PTYMode, Int>())
        return session
    }

    /** Starts an interactive shell with a PTY. Blocking. */
    fun openShell(cols: Int, rows: Int, widthPx: Int, heightPx: Int): PtyChannel {
        val session = newPtySession(cols, rows, widthPx, heightPx)
        val shell = session.startShell()
        return PtyChannel(session, shell.inputStream, shell.outputStream, cols, rows) { c, r, w, h ->
            shell.changeWindowDimensions(c, r, w, h)
        }
    }

    /**
     * Runs [command] with a PTY through the user's login shell (see [RemoteCommand.viaLoginShell]).
     * Blocking. window-change works because sshj's exec() returns the same SessionChannel,
     * which implements Session.Shell.changeWindowDimensions (checked with javap on sshj 0.41.1).
     */
    fun openPtyExec(command: String, cols: Int, rows: Int, widthPx: Int, heightPx: Int): PtyChannel {
        val session = newPtySession(cols, rows, widthPx, heightPx)
        try {
            val cmd = session.exec(RemoteCommand.viaLoginShell(command))
            val dims = cmd as Session.Shell
            return PtyChannel(session, cmd.inputStream, cmd.outputStream, cols, rows) { c, r, w, h ->
                dims.changeWindowDimensions(c, r, w, h)
            }
        } catch (e: Exception) {
            runCatching { session.close() }
            throw e
        }
    }

    /** Runs a non-interactive command and returns stdout (trimmed of a trailing newline). */
    suspend fun exec(command: String, timeoutSec: Long = 15): String = withContext(Dispatchers.IO) {
        client.startSession().use { s ->
            val cmd = s.exec(command)
            val out = cmd.inputStream.bufferedReader().readText()
            cmd.join(timeoutSec, TimeUnit.SECONDS)
            out.trimEnd('\n', '\r')
        }
    }

    /**
     * Runs a non-interactive command and returns everything it prints (stdout; add `2>&1` for stderr)
     * once it closes its output. A watchdog closes the channel after [timeoutMs], returning what was read so far.
     */
    suspend fun execAll(command: String, timeoutMs: Long): String = withContext(Dispatchers.IO) {
        val s = client.startSession()
        val sb = StringBuilder()
        val watchdog = launch { delay(timeoutMs); runCatching { s.close() } }
        try {
            val cmd = s.exec(command)
            val r = cmd.inputStream.bufferedReader()
            val buf = CharArray(1024)
            try {
                while (true) {
                    val n = r.read(buf)
                    if (n < 0) break
                    sb.append(buf, 0, n)
                }
            } catch (e: IOException) {
                if (!watchdog.isCompleted) throw e // closed by the watchdog: return the partial output
            }
        } finally {
            watchdog.cancel()
            runCatching { s.close() }
        }
        sb.toString()
    }

    /** Opens a channel to a Unix socket on the server. Blocking; call from an IO thread. */
    fun openStreamLocal(socketPath: String): DirectStreamLocalChannel {
        val ch = DirectStreamLocalChannel(client.connection, socketPath)
        ch.open()
        return ch
    }

    fun close() {
        forwardPort?.let { app.tailscale.stopForward(it) }
        try {
            client.disconnect()
        } catch (_: Exception) {
        }
    }

    /** Trust-on-first-use host key verification, keyed by the profile's logical host. */
    private inner class Verifier : HostKeyVerifier {
        override fun verify(hostname: String?, port: Int, key: PublicKey): Boolean {
            val hostKey = profile.knownHostKey
            val type = KeyType.fromKey(key).toString()
            val fp = SshKeys.fingerprint(key)
            val known = app.knownHosts.find(hostKey, type)
            if (known != null && known.fingerprint == fp) return true
            val previous = known?.fingerprint
                ?: app.knownHosts.hosts.value.firstOrNull { it.host == hostKey }?.fingerprint
            val accepted = runBlocking { Prompter.confirmHostKey(hostKey, type, fp, previous) }
            if (accepted) app.knownHosts.add(KnownHost(hostKey, type, fp))
            return accepted
        }

        override fun findExistingAlgorithms(hostname: String?, port: Int): List<String> =
            app.knownHosts.typesFor(profile.knownHostKey)
    }
}
