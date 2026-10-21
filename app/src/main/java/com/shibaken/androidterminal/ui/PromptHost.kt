package com.shibaken.androidterminal.ui

import com.shibaken.androidterminal.R
import androidx.compose.ui.res.stringResource
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.shibaken.androidterminal.session.PromptRequest
import com.shibaken.androidterminal.session.Prompter

/** Shows whatever [Prompter] is currently asking (host key, password, biometric). */
@Composable
fun PromptHost(activity: FragmentActivity) {
    val req by Prompter.current.collectAsState()
    when (val r = req) {
        is PromptRequest.HostKey -> HostKeyDialog(r)
        is PromptRequest.Secret -> SecretDialog(r)
        is PromptRequest.Biometric -> LaunchedEffect(r) { showBiometric(activity, r) }
        null -> {}
    }
}

@Composable
private fun HostKeyDialog(r: PromptRequest.HostKey) {
    AlertDialog(
        onDismissRequest = { r.answer.complete(false) },
        title = { Text(r.title) },
        text = {
            Column {
                if (r.previousFingerprint != null) {
                    Text(
                        stringResource(R.string.term_hostkey_changed_warning),
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.term_hostkey_previous, r.previousFingerprint.orEmpty()), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                }
                Text("${r.host} (${r.keyType})")
                Spacer(Modifier.height(4.dp))
                Text(r.fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.term_hostkey_verify_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { r.answer.complete(true) }) {
                Text(stringResource(if (r.previousFingerprint != null) R.string.term_hostkey_replace_connect else R.string.term_hostkey_trust_connect))
            }
        },
        dismissButton = { TextButton(onClick = { r.answer.complete(false) }) { Text(stringResource(R.string.term_cancel)) } },
    )
}

@Composable
private fun SecretDialog(r: PromptRequest.Secret) {
    var value by remember(r) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { r.answer.complete(null) },
        title = { Text(r.title) },
        text = {
            Column {
                Text(r.message)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(r.label) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = { TextButton(onClick = { r.answer.complete(value) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = { r.answer.complete(null) }) { Text(stringResource(R.string.term_cancel)) } },
    )
}

private fun showBiometric(activity: FragmentActivity, r: PromptRequest.Biometric) {
    // WEAK | DEVICE_CREDENTIAL is the combination supported on every API level.
    val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    if (BiometricManager.from(activity).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
        // No lock screen configured: nothing to check against.
        r.answer.complete(true)
        return
    }
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                r.answer.complete(true)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                r.answer.complete(false)
            }
        },
    )
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(r.title)
            .setSubtitle(r.subtitle)
            .setAllowedAuthenticators(authenticators)
            .build()
    )
}
