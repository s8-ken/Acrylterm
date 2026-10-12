package com.shibaken.androidterminal.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Questions that background connection code needs the user to answer. */
sealed class PromptRequest {
    abstract val title: String

    data class HostKey(
        override val title: String,
        val host: String,
        val keyType: String,
        val fingerprint: String,
        /** Non-null when the host was known with a different key (possible MITM). */
        val previousFingerprint: String?,
        val answer: CompletableDeferred<Boolean>,
    ) : PromptRequest()

    data class Secret(
        override val title: String,
        val message: String,
        val label: String,
        val answer: CompletableDeferred<String?>,
    ) : PromptRequest()

    data class Biometric(
        override val title: String,
        val subtitle: String,
        val answer: CompletableDeferred<Boolean>,
    ) : PromptRequest()
}

/** One prompt at a time; the UI observes [current] and completes the deferred. */
object Prompter {
    private val mutex = Mutex()
    private val _current = MutableStateFlow<PromptRequest?>(null)
    val current: StateFlow<PromptRequest?> = _current.asStateFlow()

    private suspend fun <T> ask(build: (CompletableDeferred<T>) -> PromptRequest, deferred: CompletableDeferred<T>): T =
        mutex.withLock {
            _current.value = build(deferred)
            try {
                deferred.await()
            } finally {
                _current.value = null
            }
        }

    suspend fun confirmHostKey(host: String, keyType: String, fingerprint: String, previous: String?): Boolean {
        val d = CompletableDeferred<Boolean>()
        return ask({
            PromptRequest.HostKey(
                if (previous == null) "未知のホスト" else "ホスト鍵が変わっています",
                host, keyType, fingerprint, previous, it,
            )
        }, d)
    }

    suspend fun secret(title: String, message: String, label: String): String? {
        val d = CompletableDeferred<String?>()
        return ask({ PromptRequest.Secret(title, message, label, it) }, d)
    }

    suspend fun biometric(title: String, subtitle: String): Boolean {
        val d = CompletableDeferred<Boolean>()
        return ask({ PromptRequest.Biometric(title, subtitle, it) }, d)
    }
}
