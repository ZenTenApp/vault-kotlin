package app.zenten.vault.ui.onboarding

import app.zenten.vault.R
import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.ui.components.PasteError
import app.zenten.vault.ui.keys.LocalKeyBackupShare

/**
 * Outcome of checking VaultKey text that came from outside the app (clipboard or QR scan).
 *
 * The error carries a string resource id rather than text because the callers are not
 * composables and cannot resolve strings themselves.
 */
internal sealed interface ImportedKeyValidation {
    data class Valid(val normalizedKey: String) : ImportedKeyValidation
    data class Invalid(val error: PasteError) : ImportedKeyValidation
}

/**
 * Validation shared by "Paste VaultKey" and "Login with QR Code", so both entry points
 * accept and reject exactly the same text. Nothing here touches the clipboard or camera.
 */
internal object ImportedKeyValidator {
    fun validate(rawText: String): ImportedKeyValidation {
        if (rawText.isBlank()) {
            return ImportedKeyValidation.Invalid(PasteError(R.string.ssh_setup_clipboard_empty_error))
        }
        // A notes export of an associated key is a valid encrypted key too, so the structural
        // check below would accept it as the VaultKey. Its metadata label gives it away, so
        // refuse it up front.
        if (LocalKeyBackupShare.isAssociatedKeyExport(rawText)) {
            return ImportedKeyValidation.Invalid(PasteError(R.string.ssh_setup_clipboard_associated_key_error))
        }
        return runCatching { SshKeyCodec.validateAndNormalizeEncryptedPrivateKey(rawText) }.fold(
            onSuccess = { ImportedKeyValidation.Valid(it) },
            onFailure = { error ->
                // A well-formed key with an out-of-band work factor is explained on its own
                // terms: the generic "unsupported key" wording would suggest the input was
                // malformed, when the real problem is the key's declared KDF cost.
                ImportedKeyValidation.Invalid(
                    when (error) {
                        is SshKeyCodec.UnsupportedKdfRoundsException ->
                            PasteError(R.string.ssh_setup_clipboard_kdf_rounds_error, error.declaredRounds)
                        else -> PasteError(R.string.ssh_setup_clipboard_invalid_error)
                    },
                )
            },
        )
    }
}
