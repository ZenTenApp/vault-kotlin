package app.zenten.vault.ui.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.zenten.vault.crypto.SshKeyCodec

/**
 * A paste-step error held as a string resource plus the container's declared bcrypt round count.
 *
 * Kept non-composable so a screen's clipboard handler can set it outside composition, and resolved
 * only where it is rendered. The declared count is public header metadata, never key material.
 *
 * Messages with a [declaredRounds] argument also receive the accepted band, substituted from
 * [SshKeyCodec] here rather than written into each string, so the wording cannot drift from the
 * range the parser actually enforces.
 */
data class PasteError(
    @StringRes val messageRes: Int,
    val declaredRounds: Long? = null,
) {
    @Composable
    fun render(): String = when (declaredRounds) {
        null -> stringResource(messageRes)
        else -> stringResource(
            messageRes,
            declaredRounds,
            SshKeyCodec.MIN_BCRYPT_PBKDF_ROUNDS,
            SshKeyCodec.MAX_BCRYPT_PBKDF_ROUNDS,
        )
    }
}
