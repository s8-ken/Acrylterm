package com.shibaken.androidterminal.keys

import com.shibaken.androidterminal.R
import com.shibaken.androidterminal.App
import android.util.Base64
import com.shibaken.androidterminal.data.KeyType
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.Factory
import net.schmizz.sshj.common.KeyType as SshjKeyType
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom

/** A freshly generated key: private key in PEM text (unencrypted), public key line, fingerprint. */
data class GeneratedKey(val privatePem: String, val publicLine: String, val fingerprint: String)

object SshKeys {

    fun generate(type: KeyType, comment: String): GeneratedKey = when (type) {
        KeyType.ED25519 -> generateEd25519(comment)
        KeyType.RSA_4096 -> generateRsa(comment)
        else -> throw IllegalArgumentException(App.str(R.string.core_key_unsupported_generate, type.label))
    }

    private fun generateEd25519(comment: String): GeneratedKey {
        val gen = Ed25519KeyPairGenerator()
        gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val kp = gen.generateKeyPair()
        val priv = kp.private as Ed25519PrivateKeyParameters
        val pub = kp.public as Ed25519PublicKeyParameters
        val pem = pem("OPENSSH PRIVATE KEY", OpenSSHPrivateKeyUtil.encodePrivateKey(priv))
        val blob = OpenSSHPublicKeyUtil.encodePublicKey(pub)
        return GeneratedKey(pem, publicLine("ssh-ed25519", blob, comment), fingerprint(blob))
    }

    private fun generateRsa(comment: String): GeneratedKey {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(4096, SecureRandom())
        val kp = gen.generateKeyPair()
        // PKCS#8 "PRIVATE KEY" PEM; sshj reads it through its PKCS8 key provider.
        val pem = pem("PRIVATE KEY", kp.private.encoded)
        val blob = publicBlob(kp.public)
        return GeneratedKey(pem, publicLine("ssh-rsa", blob, comment), fingerprint(blob))
    }

    /** Parse any private key format sshj understands (OpenSSH v1, PEM/PKCS#1/PKCS#8, PuTTY). */
    fun loadKeyProvider(privateKeyText: String, passphrase: CharArray?): KeyProvider {
        val format = KeyProviderUtil.detectKeyFileFormat(privateKeyText, false)
        val fkp: FileKeyProvider = Factory.Named.Util.create(
            DefaultConfig().fileKeyProviderFactories, format.toString()
        ) ?: throw IllegalArgumentException(App.str(R.string.core_key_unsupported_format, format.toString()))
        fkp.init(privateKeyText, null, passphrase?.let { passwordFinder(it) })
        return fkp
    }

    fun passwordFinder(passphrase: CharArray): PasswordFinder = object : PasswordFinder {
        override fun reqPassword(resource: Resource<*>?): CharArray = passphrase.copyOf()
        override fun shouldRetry(resource: Resource<*>?): Boolean = false
    }

    /** Returns (type label from the key, public line, fingerprint) for an imported key. */
    fun describe(provider: KeyProvider, comment: String): Triple<String, String, String> {
        val pub = provider.public
        val type = SshjKeyType.fromKey(pub).toString()
        val blob = publicBlob(pub)
        return Triple(type, publicLine(type, blob, comment), fingerprint(blob))
    }

    fun publicBlob(key: PublicKey): ByteArray = Buffer.PlainBuffer().putPublicKey(key).compactData

    fun fingerprint(blob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        return "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun fingerprint(key: PublicKey): String = fingerprint(publicBlob(key))

    private fun publicLine(type: String, blob: ByteArray, comment: String): String =
        listOf(type, Base64.encodeToString(blob, Base64.NO_WRAP), comment.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    private fun pem(label: String, der: ByteArray): String {
        val b64 = Base64.encodeToString(der, Base64.NO_WRAP)
        val body = b64.chunked(70).joinToString("\n")
        return "-----BEGIN $label-----\n$body\n-----END $label-----\n"
    }
}
