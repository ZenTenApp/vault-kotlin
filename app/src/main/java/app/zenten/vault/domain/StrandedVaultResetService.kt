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
 * The ordering matters. Storage is set aside first, then the flags are cleared. If the process dies
 * between the two, the vault is already out of the way and a later launch re-runs the same steps
 * harmlessly. Clearing flags first would make the router treat the device as a fresh install while
 * an unreadable vault was still the first thing the gate saw, which is the inconsistent state this
 * class exists to remove.
 */
@Singleton
class StrandedVaultResetService @Inject constructor(
    private val secureStorage: SecureStorageService,
    private val appStorage: AppStorageService,
) {
    /**
     * Quarantines unreadable records and clears the launch-routing flags.
     *
     * Returns the number of records set aside. Zero is a valid outcome: it means every stored
     * record turned out to be readable, so nothing was taken out of service.
     */
    suspend fun reset(): Int {
        val quarantined = secureStorage.quarantineUnreadableRecords()
        appStorage.clearStateForStrandedVaultReset()
        return quarantined
    }
}
