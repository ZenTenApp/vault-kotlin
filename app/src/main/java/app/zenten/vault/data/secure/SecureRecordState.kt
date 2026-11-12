package app.zenten.vault.data.secure

/**
 * The result of looking up an encrypted record, with "absent" and "unreadable" kept apart.
 *
 * This distinction is the whole point of the type. A plain `null` cannot say whether nothing was
 * ever stored or whether something was stored and has become impossible to decrypt, and those two
 * states need completely different treatment: the first is a fresh install, the second is data the
 * user still owns that needs a recovery path.
 *
 * The scenario that forces the distinction is a device change. The ciphertext lives in ordinary
 * app storage and has been copied to the new device — most often by a device-to-device transfer,
 * which Android 12+ does not universally disable from `android:allowBackup="false"` — but the
 * Android Keystore key that wraps it is hardware-bound and never migrates. The new device therefore
 * holds records that are present, intact, and permanently unreadable. `res/xml/data_extraction_rules.xml`
 * now excludes every domain so the system does not create that state, and this type is the fallback
 * for when an exclusion is not honoured or a record is damaged: it lets the app say what happened
 * instead of presenting an empty vault.
 */
sealed interface SecureRecordState<out T> {
    /** Nothing is stored under this key. A fresh install looks like this. */
    data object Absent : SecureRecordState<Nothing>

    /** The record was decrypted and parsed, so it can be used. */
    data class Readable<T>(val record: T) : SecureRecordState<T>

    /**
     * Ciphertext is stored but could not be turned back into a record.
     *
     * The stored value is left untouched on disk: this state is reported so a caller can offer
     * recovery or reset, never so that the app can overwrite it as if it were empty.
     */
    data class Unreadable(val reason: UnreadableReason) : SecureRecordState<Nothing>
}

/** Why a stored record could not be read. */
enum class UnreadableReason {
    /**
     * The Keystore key that wraps stored records does not exist on this device.
     *
     * Expected after a device-to-device transfer, a backup-tool restore, or vendor-level key loss,
     * because Keystore keys are never migrated. Recovery must come from an external copy of the
     * VaultKey, never from re-deriving the local key: the ciphertext was sealed with the old key and
     * is unrecoverable on this device without it. The VaultKey passphrase is no help either — it is
     * wrapped *inside* this layer, so it cannot be used to open anything until the layer is readable.
     */
    KeystoreKeyMissing,

    /**
     * The wrapping key exists but the payload did not decrypt or decode.
     *
     * Covers a truncated or tampered value, a payload written by an incompatible build, and a
     * GCM authentication failure. Unlike [KeystoreKeyMissing], the local key is intact, so this
     * points at the stored bytes rather than the device change.
     */
    PayloadInvalid,
}
