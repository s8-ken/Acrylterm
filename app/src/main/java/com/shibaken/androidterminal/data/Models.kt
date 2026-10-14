package com.shibaken.androidterminal.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class AuthMethod { PASSWORD, KEY }

@Serializable
data class HostProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val authMethod: AuthMethod = AuthMethod.KEY,
    val keyId: String? = null,
    /** Connect through the embedded Tailscale node instead of the device network. */
    val useTailscale: Boolean = true,
    /** Talk to herdr's socket API over this connection and show the herdr drawer. */
    val herdrEnabled: Boolean = true,
    /** Typed into the shell right after it starts. Empty = plain shell. */
    val startupCommand: String = "herdr",
    /** Named herdr session for the phone (`HERDR_SESSION`); blank is treated as "android" in panes mode. */
    val herdrSession: String = "android",
    /** Show each pane over Mosh (UDP, survives network changes) when possible; falls back to SSH. */
    val useMosh: Boolean = false,
    /** Run Lua extension scripts in this host's sessions. */
    val extEnabled: Boolean = false,
    /** Also load scripts from the host's config directory (acrylterm under .config; a legacy androidterminal dir is moved there) on connect. */
    val extLoadFromHost: Boolean = false,
    /** Headless AI CLI on the host that writes extensions: gets the prompt on stdin, must print the result on stdout (e.g. `claude -p`, `codex exec -`). */
    val extAgentCommand: String = "claude -p",
    /** Extensions switched off for this host only, by load name ("foo.lua" = global, "host:foo.lua" = host script). */
    val extDisabled: List<String> = emptyList(),
    val fontSizeSp: Float = 12f,
    val lastConnectedAt: Long = 0L,
) {
    val displayName: String get() = name.ifBlank { "$username@$host" }

    /** Key used for known_hosts: the logical host, not 127.0.0.1 of a Tailscale forward. */
    val knownHostKey: String get() = if (port == 22) host.lowercase() else "[${host.lowercase()}]:$port"
}

@Serializable
enum class KeyType(val label: String) {
    ED25519("Ed25519"),
    ECDSA_P256("ECDSA (P-256)"),
    RSA_4096("RSA 4096"),
    IMPORTED("インポート"),
}

@Serializable
data class SshKeyEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: KeyType,
    /** OpenSSH public key line, e.g. "ssh-ed25519 AAAA... comment". May be empty for some imports. */
    val publicKey: String,
    val fingerprint: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** The private key text itself is protected by an extra passphrase (asked on each use unless saved). */
    val hasPassphrase: Boolean = false,
    /** Passphrase saved (encrypted with the Android Keystore). */
    val passphraseSaved: Boolean = false,
    /** Require fingerprint/face unlock before the key can be used. */
    val requireBiometric: Boolean = false,
)

@Serializable
data class KnownHost(
    val host: String,
    val keyType: String,
    /** SHA256 fingerprint in OpenSSH format ("SHA256:..."). */
    val fingerprint: String,
    val addedAt: Long = System.currentTimeMillis(),
)
