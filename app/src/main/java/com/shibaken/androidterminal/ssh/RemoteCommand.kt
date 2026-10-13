package com.shibaken.androidterminal.ssh

/** Helpers for building remote shell command lines. Pure functions (unit-tested). */
object RemoteCommand {
    /** POSIX single-quote escaping: abc'd -> 'abc'\''d'. Also safe in fish. */
    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * Runs [cmd] through the user's login shell, with the usual user bin dirs prepended to PATH.
     * A login shell alone is not enough: zsh -l skips ~/.zshrc, where ~/.local/bin is often added.
     * (macOS path_helper keeps the extra entries; a quoted fish $PATH also joins with ':'.)
     */
    fun viaLoginShell(cmd: String): String = "exec env PATH=\"$EXTRA_PATH:\$PATH\" \"\$SHELL\" -lc " + quote(cmd)

    private const val EXTRA_PATH = "\$HOME/.local/bin:\$HOME/.cargo/bin:/opt/homebrew/bin:/usr/local/bin"

    /**
     * Attach command for one pane. Options must come after the terminal id
     * (`terminal attach --takeover <id>` fails with "unknown option").
     */
    fun herdrAttach(session: String, terminalId: String): String =
        "env HERDR_SESSION=${quote(session)} herdr terminal attach ${quote(terminalId)} --takeover"

    /** herdr session names are used in file paths and env vars: keep a conservative alphabet. */
    fun sanitizeSessionName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "")

    /** Parses "herdr 0.8.0" / "0.9.3" into (major, minor, patch), or null. */
    fun parseVersion(text: String): Triple<Int, Int, Int>? {
        val m = Regex("""(\d+)\.(\d+)\.(\d+)""").find(text) ?: return null
        return Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }

    /** True when `herdr terminal attach` is available (>= 0.7.2). */
    fun supportsTerminalAttach(versionText: String): Boolean {
        val (x, y, z) = parseVersion(versionText) ?: return false
        return x > 0 || y > 7 || (y == 7 && z >= 2)
    }

    /** True when [versionText] is at least [major].[minor].0. */
    fun versionAtLeast(versionText: String, major: Int, minor: Int): Boolean {
        val (x, y, _) = parseVersion(versionText) ?: return false
        return x > major || (x == major && y >= minor)
    }

    private val STATUS_RUNNING = Regex("""(?m)^\s*status:\s*running\b""")

    /** Parses `herdr status server` output (0.8 and 0.9: `status: running` / `status: not running`). */
    fun isServerRunning(statusOutput: String): Boolean = STATUS_RUNNING.containsMatchIn(statusOutput)
}
