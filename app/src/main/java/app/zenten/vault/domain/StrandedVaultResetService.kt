package app.zenten.vault.domain

import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.secure.SecureStorageService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sets aside a vault whose records cannot be decrypted on this device.
 *
 * This exists for one state: ciphertext in secure storage cannot be opened here because the Android
 * Keystore key that wraps it is not on this device (Keystore keys are hardware-bound and are never
 * transferred), or because the stored bytes are damaged. The user's records are present, and no
 * passphrase will ever open them here.
 *
 * App data is normally never transferred at all — `res/xml/data_extraction_rules.xml` excludes every
 * domain from both cloud backup and device-to-device transfer — so this is the fallback for when an
 * exclusion is not honoured, a transfer is partial, or a record is corrupt. It is not the intended
 * path; a new device is meant to start empty and recover the VaultKey from its backup.
 *
 * The action deliberately does **not** promise recovery and does **not** delete the user's data.
 * Ciphertext is moved into quarantine, where it stays as a retained copy; nothing on this device can
 * read it again, so this is retention, not a rescue. The durable flags that would otherwise strand
 * the launch router are cleared so the user can reach onboarding and recover the VaultKey from a
 * remote backup. Recovering the key itself is the separate, existing **Recover VaultKey** flow;
 * this class only clears the way to it.
 *
 * The ordering matters, and it is flags first, then storage. Both steps are retryable and neither
 * deletes anything, so the only thing ordering decides is what the user sees if the process dies
 * between them:
 *
 * - Flags cleared, storage not yet moved: the gate still finds the unreadable record and keeps
 *   showing this screen, so the user simply retries. Nothing is orphaned and no routing flag is
 *   inconsistent with it.
 * - Storage moved, flags not yet cleared (the opposite order): the gate no longer sees an
 *   unreadable record, so it reports a fresh install while `setup_complete` still says otherwise.
 *   The router then sends the user to an empty account list — the silent-loss presentation this
 *   whole feature exists to prevent.
 *
 * The gate outranks the router, so clearing flags early cannot expose a half-reset device; the
 * router is only reached once the gate reports onboarding.
 */
@Singleton
class StrandedVaultResetService @Inject constructor(
    private val secureStorage: SecureStorageService,
    private val appStorage: AppStorageService,
) {
    /**
     * Clears the launch-routing flags and quarantines the unreadable records.
     *
     * Returns the number of records set aside. Zero is a valid outcome: it means every stored
     * record turned out to be readable, so nothing was taken out of service.
     */
    suspend fun reset(): Int {
        appStorage.clearStateForStrandedVaultReset()
        return secureStorage.quarantineUnreadableRecords()
    }
}
