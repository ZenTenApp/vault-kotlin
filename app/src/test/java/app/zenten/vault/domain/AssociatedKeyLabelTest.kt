package app.zenten.vault.domain

import app.zenten.vault.crypto.NostrKeyDerivation
import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.data.model.Key
import app.zenten.vault.data.secure.SecureStorageService
import app.zenten.vault.network.nostr.NostrEvent
import app.zenten.vault.network.nostr.NostrEventCodec
import app.zenten.vault.network.nostr.NostrRelayGateway
import app.zenten.vault.network.nostr.PublishResult
import io.mockk.every
import io.mockk.mockk
import java.nio.ByteBuffer
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Covers where an associated key's name lives in its backup records.
 *
 * Names are no longer published as kind-0 profiles, so the kind-37802 `label` tag is the only
 * thing that carries them across a device change.
 */
class AssociatedKeyLabelTest {
    private val clock = Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC)
    private val vaultKey = Key(
        createdAt = 1L,
        zenTenKey = SshKeyCodec.normalizeToSingleLineBase64(
            SshKeyCodec.generateEncrypted(1L, PASSPHRASE).privateKeyPem,
        ),
    )
    // The identities used to sign records are derived from the key's own seed, exactly as the
    // backup sources do; neither is stored on the key.
    private val seed: ByteArray get() = SshKeyCodec.parse(vaultKey.zenTenKey, PASSPHRASE).seed
    private val keyIdentity: NostrKeyDerivation.NostrKeys get() = NostrKeyDerivation.fromKeySeed(seed)
    private val vaultKeyBackupIdentity: NostrKeyDerivation.NostrKeys get() = NostrKeyDerivation.fromVaultKeyBackupSeed(seed)

    @Test
    fun associated_key_event_carries_the_label_tag_before_the_d_tag() {
        val event = BackupService(RecordingGateway(), clock).buildBackupEvent(
            vaultKey,
            key(10L).copy(keyName = "  Work key  "),
            "10",
            BackupService.ASSOCIATED_KEY_BACKUP_KIND,
            keyIdentity,
        )

        assertEquals(
            listOf(listOf(BackupService.KEY_LABEL_TAG, "Work key"), listOf("d", "10")),
            event.tags,
        )
    }

    @Test
    fun nameless_key_event_has_only_the_d_tag() {
        val event = BackupService(RecordingGateway(), clock).buildBackupEvent(
            vaultKey,
            key(10L).copy(keyName = "   "),
            "10",
            BackupService.ASSOCIATED_KEY_BACKUP_KIND,
            keyIdentity,
        )

        assertEquals(listOf(listOf("d", "10")), event.tags)
    }

    @Test
    fun vault_key_event_never_carries_a_label() {
        val event = BackupService(RecordingGateway(), clock).buildBackupEvent(
            vaultKey,
            vaultKey.copy(keyName = "Not published"),
            BackupService.VAULT_KEY_D_TAG,
            BackupService.VAULT_KEY_BACKUP_KIND,
            vaultKeyBackupIdentity,
        )

        assertEquals(listOf(listOf("d", BackupService.VAULT_KEY_D_TAG)), event.tags)
    }

    @Test
    fun recovery_restores_the_key_name_from_the_label_tag() = runTest {
        val event = BackupService(RecordingGateway(), clock).buildBackupEvent(
            vaultKey,
            key(10L).copy(keyName = "Work key"),
            "10",
            BackupService.ASSOCIATED_KEY_BACKUP_KIND,
            keyIdentity,
        )

        val restored = requireNotNull(recoveryService().decodeAssociatedKey(event, vaultKey, PASSPHRASE))

        assertEquals("Work key", restored.keyName)
        assertEquals(10L, restored.createdAt)
    }

    @Test
    fun recovery_accepts_an_event_without_a_label() = runTest {
        val event = BackupService(RecordingGateway(), clock).buildBackupEvent(
            vaultKey,
            key(10L),
            "10",
            BackupService.ASSOCIATED_KEY_BACKUP_KIND,
            keyIdentity,
        )

        val restored = requireNotNull(recoveryService().decodeAssociatedKey(event, vaultKey, PASSPHRASE))

        assertNull(restored.keyName)
    }

    @Test
    fun recovery_skips_a_backup_record_whose_work_factor_is_outside_the_accepted_band() = runTest {
        // A record written by an older build (or by a hostile peer) can carry any declared work
        // factor. Intake routes it through the same container reader the rest of the app uses, so
        // a 1-round key is rejected here rather than being restored and later failing to unlock.
        val weakKey = withDeclaredRounds(
            SshKeyCodec.normalizeToSingleLineBase64(
                SshKeyCodec.generateEncrypted(10L, PASSPHRASE, rounds = SshKeyCodec.MIN_BCRYPT_PBKDF_ROUNDS).privateKeyPem,
            ),
            rounds = 1,
        )
        // The event is signed directly: building one keeps raw ciphertext, so this is the only way
        // to publish a payload that a reader must reject.
        val event = NostrEventCodec.sign(
            privateKey = keyIdentity.privateKey,
            publicKey = keyIdentity.publicKey,
            kind = BackupService.ASSOCIATED_KEY_BACKUP_KIND,
            tags = listOf(listOf("d", "10")),
            content = weakKey,
            createdAtSeconds = clock.instant().epochSecond,
        )

        assertNull(recoveryService().decodeAssociatedKey(event, vaultKey, PASSPHRASE))
        assertThrows(IllegalArgumentException::class.java) {
            keyFromSshKey(10L, weakKey)
        }
    }

    private fun recoveryService(): RecoveryService = RecoveryService(
        RecordingGateway(),
        mockk<SecureStorageService> { every { getKeys() } returns emptyList() },
    )

    /**
     * Rewrites the bcrypt work factor in an OpenSSH container's plaintext KDF options.
     *
     * The field sits immediately after the `[salt length][salt]` block, which follows the magic,
     * cipher name, and KDF name. Walking the length prefixes keeps this correct if the layout
     * above it ever changes.
     */
    private fun withDeclaredRounds(key: String, rounds: Int): String {
        val bytes = Base64.getDecoder().decode(key)
        var offset = "openssh-key-v1\u0000".length
        repeat(2) { offset += 4 + ByteBuffer.wrap(bytes, offset, 4).int } // cipher name, KDF name
        val optionsStart = offset + 4
        val roundsOffset = optionsStart + 4 + ByteBuffer.wrap(bytes, optionsStart, 4).int // skip the salt
        return Base64.getEncoder().encodeToString(bytes.copyOf().also { ByteBuffer.wrap(it).putInt(roundsOffset, rounds) })
    }

    private fun key(createdAt: Long): Key = Key(
        createdAt = createdAt,
        // Each key needs its own key material so decryption and derivation succeed.
        zenTenKey = SshKeyCodec.normalizeToSingleLineBase64(
            SshKeyCodec.generateEncrypted(createdAt, PASSPHRASE).privateKeyPem,
        ),
    )

    private class RecordingGateway : NostrRelayGateway {
        override suspend fun publish(event: NostrEvent) = PublishResult(event.id, emptySet(), emptySet(), 1)

        override suspend fun fetchByAuthorAndKind(authorPubkey: String, kind: Int): List<NostrEvent> = emptyList()
    }

    private companion object {
        val PASSPHRASE = "pass".toCharArray()
    }
}