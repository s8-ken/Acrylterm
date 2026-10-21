package com.shibaken.androidterminal.keys

import com.shibaken.androidterminal.R
import com.shibaken.androidterminal.App
import android.content.Context
import android.os.Build
import com.shibaken.androidterminal.data.JsonListStore
import com.shibaken.androidterminal.data.KeyType
import com.shibaken.androidterminal.data.SshKeyEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import java.io.File

/**
 * SSH key storage. Metadata lives in keys.json; each private key (and an optional saved
 * passphrase) is stored as a separate file encrypted with [KeystoreCipher].
 */
class KeyRepository(private val context: Context) {
    private val store = JsonListStore(context, "keys.json", SshKeyEntry.serializer())
    val keys: StateFlow<List<SshKeyEntry>> = store.items

    private val dir = File(context.filesDir, "keys").apply { mkdirs() }
    private fun keyFile(id: String) = File(dir, "$id.key.enc")
    private fun passFile(id: String) = File(dir, "$id.pass.enc")

    fun get(id: String): SshKeyEntry? = keys.value.firstOrNull { it.id == id }

    private fun defaultComment(name: String): String =
        "${name.ifBlank { "key" }.replace(' ', '-')}@android-${Build.MODEL.replace(' ', '-')}"

    suspend fun generate(name: String, type: KeyType): SshKeyEntry = withContext(Dispatchers.Default) {
        val generated = SshKeys.generate(type, defaultComment(name))
        val entry = SshKeyEntry(
            name = name.ifBlank { type.label },
            type = type,
            publicKey = generated.publicLine,
            fingerprint = generated.fingerprint,
        )
        writeEncrypted(keyFile(entry.id), generated.privatePem.toByteArray())
        store.update { it + entry }
        entry
    }

    /**
     * Imports a private key. [passphrase] is needed for encrypted keys; it is kept (encrypted)
     * only when [savePassphrase] is true, otherwise it is asked for on every connection.
     */
    suspend fun import(
        name: String,
        privateKeyText: String,
        passphrase: CharArray?,
        savePassphrase: Boolean,
    ): SshKeyEntry = withContext(Dispatchers.Default) {
        val text = privateKeyText.trim() + "\n"
        val provider = try {
            SshKeys.loadKeyProvider(text, passphrase?.takeIf { it.isNotEmpty() })
                .also { it.public } // forces parsing/decryption
        } catch (e: Exception) {
            throw IllegalArgumentException(
                if (passphrase == null || passphrase.isEmpty()) App.str(R.string.core_key_load_failed_passphrase, e.message.toString())
                else App.str(R.string.core_key_load_failed_check, e.message.toString()),
                e,
            )
        }
        val (_, publicLine, fp) = SshKeys.describe(provider, name)
        val hasPass = passphrase != null && passphrase.isNotEmpty()
        val entry = SshKeyEntry(
            name = name.ifBlank { App.str(R.string.core_key_imported_name) },
            type = KeyType.IMPORTED,
            publicKey = publicLine,
            fingerprint = fp,
            hasPassphrase = hasPass,
            passphraseSaved = hasPass && savePassphrase,
        )
        writeEncrypted(keyFile(entry.id), text.toByteArray())
        if (entry.passphraseSaved) writeEncrypted(passFile(entry.id), String(passphrase!!).toByteArray())
        store.update { it + entry }
        entry
    }

    fun rename(id: String, name: String) = store.update { list ->
        list.map { if (it.id == id) it.copy(name = name) else it }
    }

    fun setRequireBiometric(id: String, require: Boolean) = store.update { list ->
        list.map { if (it.id == id) it.copy(requireBiometric = require) else it }
    }

    fun delete(id: String) {
        keyFile(id).delete()
        passFile(id).delete()
        store.update { list -> list.filterNot { it.id == id } }
    }

    fun savedPassphrase(id: String): CharArray? {
        val f = passFile(id)
        if (!f.exists()) return null
        return String(KeystoreCipher.decrypt(f.readBytes())).toCharArray()
    }

    /** Loads the key for authentication. [passphrase] overrides the saved one. */
    suspend fun keyProvider(id: String, passphrase: CharArray?): KeyProvider = withContext(Dispatchers.Default) {
        val f = keyFile(id)
        require(f.exists()) { App.str(R.string.core_key_file_missing) }
        val text = String(KeystoreCipher.decrypt(f.readBytes()))
        val pass = passphrase ?: savedPassphrase(id)
        SshKeys.loadKeyProvider(text, pass).also { it.public }
    }

    private fun writeEncrypted(file: File, plain: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(KeystoreCipher.encrypt(plain))
        tmp.renameTo(file)
        plain.fill(0)
    }
}
