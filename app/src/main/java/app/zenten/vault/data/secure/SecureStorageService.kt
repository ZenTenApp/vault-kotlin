package app.zenten.vault.data.secure

import android.content.Context
import androidx.core.content.edit
import app.zenten.vault.crypto.KeyPresence
import app.zenten.vault.crypto.KeystoreCryptoService
import app.zenten.vault.data.model.Account
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Keystore-backed encrypted persistence for every sensitive account record.
 *
 * Each preference value is JSON-serialized, encrypted with [KeystoreCryptoService] (AES-256-GCM
 * using an Android Keystore key), then stored as Base64 text. Preference keys and the file name
 * remain plaintext but contain only non-secret record identifiers.
 *
 * There is deliberately **no separate index/registry of accounts**. The account records are
 * themselves the index: each is stored under `accounts_<createdAt>` and also carries `createdAt`
 * inside its authenticated ciphertext. Enumerating therefore means scanning that key prefix.
 *
 * Removing the registry is a correctness property, not just a simplification. An index has to be
 * kept in sync with the records it lists, and that read-modify-write pair cannot be atomic in
 * `SharedPreferences`. Two concurrent writers could each write a distinct valid record while only
 * one ID survived in the index, silently orphaning the loser — a credential the app could no
 * longer list, back up, or export. With every mutation reduced to a single preference edit, there
 * is no multi-step update left to lose, so no caller has to remember to hold a lock.
 *
 * There is intentionally no migration code for the former `accounts_registry` preference. The app
 * has not been released, so every install is a development install and no stored registry exists
 * in the field. Account records were always stored under `accounts_<createdAt>`, so they remain
 * readable as-is. A stale `accounts_registry` value on a development device is inert: it is not an
 * `accounts_<id>` key, so enumeration ignores it, and [clearAll] removes it.
 *
 * This store is device-local by design and is never migrated. Stopping migration takes two layers,
 * and neither is redundant: `res/xml/data_extraction_rules.xml` asks the system not to copy these
 * files off the device, while [SecureRecordState] and [unreadableRecords] handle the case where a
 * copy arrives anyway — an OEM that ignores the exclusion, a partial transfer, or damaged bytes.
 * The XML is a declaration the OS may not honour; this class can only observe the result.
 */
class SecureStorageService(
    context: Context,
    private val crypto: KeystoreCryptoService = KeystoreCryptoService(),
    // Unknown keys are ignored so a record written by an older build still decodes after a field
    // is removed from the model. Without this, such a record silently disappears from the list.
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val _accounts = MutableStateFlow(readAccounts())
    val accountUpdates: StateFlow<List<Account>> = _accounts.asStateFlow()

    /**
     * Decrypted VaultKey record, used by the launch gate to know whether to ask for a passphrase.
     * Holds only the passphrase-encrypted key, never the passphrase itself.
     *
     * `null` here means "no usable VaultKey", which conflates a fresh install with an unreadable
     * vault. Anything that needs to tell those apart must use [vaultKeyState], not this flow.
     */
    private val _vaultKey = MutableStateFlow(read(VAULT_KEY))
    val vaultKeyUpdates: StateFlow<Account?> = _vaultKey.asStateFlow()

    /**
     * VaultKey presence and readability, including the case a `null` record cannot express.
     *
     * This is the signal the launch gate needs before it can distinguish a fresh install from a
     * vault whose wrapping key is not on this device. Observing it rather than reading once keeps a
     * completed onboarding or recovery visible without a restart.
     */
    private val _vaultKeyState = MutableStateFlow(readState(VAULT_KEY))
    val vaultKeyState: StateFlow<SecureRecordState<Account>> = _vaultKeyState.asStateFlow()

    /**
     * The worst unreadability reason among stored records, or `null` when all are readable.
     *
     * Exposed as a flow rather than a one-shot read because [quarantineUnreadableRecords] changes
     * the answer, and the launch gate must leave the recovery screen without the Activity being
     * recreated. Republished by every mutation path through [publishAccounts] and [publishVaultKey].
     *
     * The launch gate needs this in addition to [vaultKeyState] because a transfer can be partial:
     * `setup_complete` and the account records travel while the VaultKey record does not. Testing
     * only the VaultKey would report that case as a fresh install, and onboarding over records that
     * are still on disk is the silent-loss path this state exists to prevent.
     *
     * Quarantined entries are excluded: they have already been set aside deliberately, and counting
     * them would keep the app on the recovery screen after the user cleared it.
     */
    private val _unreadableRecords = MutableStateFlow(scanForUnreadableRecords())
    val unreadableRecords: StateFlow<UnreadableReason?> = _unreadableRecords.asStateFlow()

    fun addVaultKey(account: Account) {
        write(VAULT_KEY, account)
        publishVaultKey()
    }

    fun getVaultKey(): Account? = read(VAULT_KEY)

    /**
     * Reads the VaultKey together with the reason it is unavailable, when it is.
     *
     * Prefer this over [getVaultKey] anywhere the difference between "no vault" and "vault that
     * cannot be opened" changes what the user is shown.
     */
    fun getVaultKeyState(): SecureRecordState<Account> = readState(VAULT_KEY)

    fun removeVaultKey() {
        preferences.edit { remove(VAULT_KEY) }
        publishVaultKey()
    }

    /**
     * Adds or replaces an account in a single preference edit.
     *
     * One edit is atomic and process-safe, so concurrent saves never discard each other. Replacing
     * an existing ID is idempotent because the key is derived solely from `createdAt`.
     */
    fun addAccount(account: Account) {
        write(accountKey(account.createdAt), account)
        publishAccounts()
    }

    /** Returns the record only when it decrypts and still claims the ID that was asked for. */
    fun getAccountById(createdAt: Long): Account? = read(accountKey(createdAt))
        ?.takeIf { it.createdAt == createdAt }

    /** Returns all readable records, ordered by ascending account ID (oldest first). */
    fun getAccounts(): List<Account> = readAccounts()

    /** Removes the record in a single preference edit; removing an absent ID is a no-op. */
    fun removeAccount(createdAt: Long) {
        preferences.edit { remove(accountKey(createdAt)) }
        publishAccounts()
    }

    fun clearAll() {
        preferences.edit { clear() }
        publishAccounts()
        publishVaultKey()
    }

    /**
     * Reports whether any quarantined (unreadable) record is still held on this device.
     *
     * Quarantined records are ciphertext the app could not decrypt, parked by a reset so the app
     * is not the thing that destroyed the user's last local copy. Their presence is what tells the
     * recovery screen that reset discarded data rather than being a no-op.
     *
     * This is not a promise of recovery. The bytes need the original device's Keystore key, so no
     * build can open them here; this is a record that the data exists.
     */
    fun hasQuarantinedRecords(): Boolean =
        preferences.all.keys.any { it.startsWith(QUARANTINE_KEY_PREFIX) }

    /**
     * Reports whether any stored record exists that this device cannot read.
     *
     * One-shot read of the same scan [unreadableRecords] publishes. Prefer the flow anywhere the
     * answer has to change without recreating the caller.
     */
    fun hasUnreadableRecords(): Boolean = scanForUnreadableRecords() != null

    /**
     * Worst unreadability reason across every live record, or `null` when the store is healthy.
     *
     * "Worst" means the most permanent cause wins, because that is the one that decides the
     * advice. [UnreadableReason.KeystoreKeyMissing] outranks [UnreadableReason.PayloadInvalid]
     * outranks [UnreadableReason.KeystoreUnavailable]: a record that can never be opened here
     * matters more than one that might be repaired or re-checked.
     */
    private fun scanForUnreadableRecords(): UnreadableReason? = preferences.all.keys
        .asSequence()
        .filter { it == VAULT_KEY || accountIdFromKey(it) != null }
        .mapNotNull { key -> (readState(key) as? SecureRecordState.Unreadable)?.reason }
        .maxByOrNull(::severity)

    /** Orders reasons from least to most consequential for the advice the user receives. */
    private fun severity(reason: UnreadableReason): Int = when (reason) {
        UnreadableReason.KeystoreUnavailable -> 0
        UnreadableReason.PayloadInvalid -> 1
        UnreadableReason.KeystoreKeyMissing -> 2
    }

    /**
     * Re-reads the VaultKey and re-scans record health, republishing both.
     *
     * Needed because [readState] answers from the Keystore at the moment it runs: a scan that hit
     * [UnreadableReason.KeystoreUnavailable] is only a statement about that attempt. A caller that
     * offers the user a retry calls this so a transient Keystore failure can clear without an
     * Activity restart.
     */
    fun refreshRecordHealth() {
        publishVaultKey()
    }

    private fun publishRecordHealth() {
        _unreadableRecords.value = scanForUnreadableRecords()
    }

    /**
     * Moves every currently-unreadable record into quarantine and returns how many were moved.
     *
     * This is the only destructive-looking operation the app offers on unreadable data, and it is
     * deliberately non-destructive: the ciphertext is re-keyed under [QUARANTINE_KEY_PREFIX] rather
     * than deleted, so the user's bytes are not thrown away by a recovery action. It is honest to
     * call this retention rather than rescue — the payload was sealed with a Keystore key from a
     * device that is gone, so neither a future build nor a recovered passphrase can open it here.
     * Keeping it costs nothing and avoids the app being the thing that destroyed the last copy.
     *
     * A record whose cause is [UnreadableReason.KeystoreUnavailable] is left in place. That answer
     * means the Keystore could not be queried, so the record may well be readable on the next
     * launch; setting it aside could park a perfectly healthy vault. Refusing here is what keeps
     * the recovery action safe to offer on an uncertain state.
     *
     * Refusing also matters for the count: returning 0 tells the caller nothing was set aside,
     * which is accurate because nothing was.
     *
     * Readable records are never quarantined. A record that decrypts is never touched by this
     * operation, so a partial-failure state cannot lose it.
     *
     * Quarantine is a single generation: any records parked by a previous reset are dropped in the
     * same edit, so repeated resets cannot accumulate unreadable ciphertext without bound.
     */
    fun quarantineUnreadableRecords(): Int {
        val unreadable = preferences.all.mapNotNull { (key, value) ->
            when {
                key != VAULT_KEY && accountIdFromKey(key) == null -> null
                // A non-String value would abort the whole edit and leave a half-applied
                // quarantine, so it is skipped rather than cast blindly.
                value !is String -> null
                else -> {
                    val reason = (readState(key) as? SecureRecordState.Unreadable)?.reason
                    // `KeystoreUnavailable` means "could not check", not "cannot read". Parking
                    // the record would be acting on an unknown, so it is left alone.
                    if (reason == null || reason == UnreadableReason.KeystoreUnavailable) null
                    else key to value
                }
            }
        }

        if (unreadable.isEmpty()) return 0

        // Snapshot the previous generation's names before the edit: the editor does not expose the
        // key set, and iterating it while deleting would be unsafe in any case.
        val previousGeneration = preferences.all.keys
            .filter { it.startsWith(QUARANTINE_KEY_PREFIX) }

        preferences.edit {
            // One generation only: dropping the previous set keeps the retained copy bounded by
            // the number of live records instead of growing with every reset.
            previousGeneration.forEach { remove(it) }
            unreadable.forEach { (key, value) ->
                remove(key)
                putString("$QUARANTINE_KEY_PREFIX$key", value)
            }
        }
        publishAccounts()
        publishVaultKey()
        return unreadable.size
    }

    private fun write(key: String, account: Account) =
        preferences.edit { putString(key, crypto.encrypt(json.encodeToString(account))) }

    private fun read(key: String): Account? = preferences.getString(key, null)
        ?.let(crypto::decrypt)
        ?.let { encoded -> runCatching { json.decodeFromString<Account>(encoded) }.getOrNull() }

    /**
     * Reads a record while preserving *why* it is unavailable.
     *
     * The order of the checks carries the meaning. A missing preference is [Absent] regardless of
     * the Keystore, because nothing was ever stored. Only once ciphertext is known to exist does
     * the wrapping key's presence become the explanation; with the key present, any remaining
     * failure is attributed to the stored bytes ([UnreadableReason.PayloadInvalid]).
     *
     * A Keystore that cannot be queried is reported as
     * [UnreadableReason.KeystoreUnavailable], not as a missing key. `decrypt` returns `null` for
     * both, so only the presence check can separate them, and claiming the key is gone would offer
     * a permanent-recovery action for a state that may be transient.
     */
    private fun readState(key: String): SecureRecordState<Account> {
        val stored = preferences.getString(key, null) ?: return SecureRecordState.Absent

        val decrypted = crypto.decrypt(stored)
            ?: return SecureRecordState.Unreadable(
                when (crypto.keyPresence()) {
                    KeyPresence.Present -> UnreadableReason.PayloadInvalid
                    KeyPresence.Missing -> UnreadableReason.KeystoreKeyMissing
                    KeyPresence.Unavailable -> UnreadableReason.KeystoreUnavailable
                },
            )

        val account = runCatching { json.decodeFromString<Account>(decrypted) }.getOrNull()
            ?: return SecureRecordState.Unreadable(UnreadableReason.PayloadInvalid)

        return SecureRecordState.Readable(account)
    }

    /** Re-publishes both VaultKey projections so a `null` record and its state never disagree. */
    private fun publishVaultKey() {
        val state = readState(VAULT_KEY)
        _vaultKeyState.value = state
        _vaultKey.value = (state as? SecureRecordState.Readable)?.record
        publishRecordHealth()
    }

    /**
     * Rebuilds the account list from the encrypted records themselves.
     *
     * The order is explicit rather than incidental: without a registry there is no stored
     * insertion order to fall back on, so ascending `createdAt` is the canonical order. Every
     * caller either re-sorts or treats the list as a set, so this is not user-visible.
     *
     * A record that fails to decrypt, fails to parse, or whose authenticated `createdAt` disagrees
     * with its preference key is skipped rather than trusted. It is left on disk untouched so a
     * later recovery path can still inspect it; [readState] is what reports why a single named
     * record is unreadable.
     */
    private fun readAccounts(): List<Account> = preferences.all.keys
        .mapNotNull(::accountIdFromKey)
        .mapNotNull(::getAccountById)
        .sortedBy { it.createdAt }

    private fun publishAccounts() {
        _accounts.value = readAccounts()
        publishRecordHealth()
    }

    /**
     * Extracts the account ID from an `accounts_<id>` preference key.
     *
     * Parsing the numeric suffix rejects any other preference whose name merely starts with the
     * prefix, so unrelated state can never be mistaken for a record.
     */
    private fun accountIdFromKey(key: String): Long? = key
        .takeIf { it.startsWith(ACCOUNT_KEY_PREFIX) }
        ?.removePrefix(ACCOUNT_KEY_PREFIX)
        ?.takeIf { suffix -> suffix.isNotEmpty() && suffix.all(Char::isDigit) }
        ?.toLongOrNull()

    private fun accountKey(createdAt: Long) = "$ACCOUNT_KEY_PREFIX$createdAt"

    private companion object {
        const val FILE_NAME = "vault_secure_accounts_v2"
        const val VAULT_KEY = "vault_key"
        const val ACCOUNT_KEY_PREFIX = "accounts_"

        /**
         * Prefix that parks ciphertext the app could not decrypt.
         *
         * Namespaced separately from `accounts_` on purpose: [accountIdFromKey] rejects anything
         * that is not a pure numeric suffix, so a quarantined record can never be enumerated as a
         * live account even if this class gains a looser key scan later.
         */
        const val QUARANTINE_KEY_PREFIX = "quarantined_"
    }
}
