package com.cardplayer.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.cardplayer.R
import com.cardplayer.data.AppSettings
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Optional password in front of the grown-up settings. Stored as a salted SHA-256, never in plain text. */
object SettingsLock {
    fun isLocked(s: AppSettings): Boolean = s.settingsPasswordHash != null && s.settingsPasswordSalt != null

    fun newSalt(): String = Base64.getEncoder().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })

    fun hash(password: String, salt: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest((salt + password).toByteArray()))

    fun check(s: AppSettings, password: String): Boolean {
        val salt = s.settingsPasswordSalt ?: return true
        val expected = s.settingsPasswordHash ?: return true
        return MessageDigest.isEqual(hash(password, salt).toByteArray(), expected.toByteArray())
    }

    fun withPassword(s: AppSettings, password: String?): AppSettings =
        if (password.isNullOrEmpty()) {
            s.copy(settingsPasswordHash = null, settingsPasswordSalt = null)
        } else {
            val salt = newSalt()
            s.copy(settingsPasswordHash = hash(password, salt), settingsPasswordSalt = salt)
        }
}

/** Number-pad keyboard; the Done key submits. */
private fun pinKeyboard(onDone: () -> Unit = {}) = Pair(
    KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
    KeyboardActions(onDone = { onDone() })
)

/** Strips anything that isn't a digit (pasted text, hardware keyboards). */
private fun digitsOnly(s: String) = s.filter { it.isDigit() }

const val MIN_PIN_LENGTH = 4

/**
 * Asks for the settings PIN; [onUnlock] runs only when it matches. Locks set before the PIN switch
 * may still be letter passwords, so a link swaps in the full keyboard for those.
 */
@Composable
fun PasswordPromptDialog(settings: AppSettings, onUnlock: () -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var letters by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val submit: () -> Unit = { if (SettingsLock.check(settings, text)) onUnlock() else wrong = true }
    val (pinOptions, pinActions) = pinKeyboard(submit)
    LaunchedEffect(letters) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lock_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = if (letters) it else digitsOnly(it); wrong = false },
                    label = { Text(stringResource(R.string.lock_password)) },
                    singleLine = true,
                    isError = wrong,
                    supportingText = if (wrong) ({ Text(stringResource(R.string.lock_wrong_password)) }) else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = if (letters) KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done) else pinOptions,
                    keyboardActions = pinActions,
                    modifier = Modifier.focusRequester(focus)
                )
                if (!letters) {
                    TextButton(onClick = { letters = true }) {
                        Text(stringResource(R.string.lock_use_letters), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = submit) { Text(stringResource(R.string.common_open)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

/** Choose a new settings PIN (typed twice, digits only). Too short is not accepted; use "Remove" to clear it. */
@Composable
fun SetPasswordDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    val mismatch = b.isNotEmpty() && a != b
    val valid = a.length >= MIN_PIN_LENGTH && a == b
    val focus = remember { FocusRequester() }
    val (nextOptions, _) = pinKeyboard()
    val (doneOptions, doneActions) = pinKeyboard { if (valid) onSave(a) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lock_set_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = a, onValueChange = { a = digitsOnly(it) },
                    label = { Text(stringResource(R.string.lock_new_password)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = nextOptions.copy(imeAction = ImeAction.Next),
                    modifier = Modifier.focusRequester(focus)
                )
                OutlinedTextField(
                    value = b, onValueChange = { b = digitsOnly(it) },
                    label = { Text(stringResource(R.string.lock_type_again)) }, singleLine = true,
                    isError = mismatch,
                    supportingText = if (mismatch) ({ Text(stringResource(R.string.lock_mismatch)) }) else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = doneOptions,
                    keyboardActions = doneActions
                )
                Text(
                    stringResource(R.string.lock_forgot_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onSave(a) }) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}
