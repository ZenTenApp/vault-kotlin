package app.zenten.vault.domain

import app.zenten.vault.crypto.NostrKeyDerivation
import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.crypto.toPersistedKeyPair
import app.zenten.vault.data.model.Account
import app.zenten.vault.data.secure.SecureStorageService
import app.zenten.vault.network.nostr.NostrEvent
import app.zenten.vault.network.nostr.NostrRelayGateway
import app.zenten.vault.network.nostr.PublishResult
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Covers where an associated account's name lives in its backup records.
 *
 * Names are no longer published as kind-0 profiles, so the kind-37802 `label` tag is the only
 * thing that carries them across a device change.
 */
class AssociatedAccountLabelTest {
    private val clock = Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC)
    private val vaultKey = Account(
        createdAt = 1L,
        zenTenKey = SshKeyCodec.normalizeToSingleLineBase64(
            SshKeyCodec.generateEncrypted(1L, PASSPHRASE).privateKeyPem,
        ),
        nostr = NostrKeyDerivation.derive(MNEMONIC).toPersistedKeyPair(),
    )

    @Test
    fun associated_account_event_carries_the_label_tag_before_the_d_tag() {
        val event = BackupService(RecordingGateway(), clock).buildBackupEvent(
            vaultKey,
            account(10L).copy(accountName = "  Work account  "),
            "10",
            BackupService.ASSOCIATED_ACCOUNT_BACKUP_KIND,
        )

        assertEquals(
            listOf(listOf(BackupService.ACCOUNT_LABEL_TAG, "Work account"), listOf("d", "10")),
            event.tags,
        )
    }

    @Test
    fun nameless_account_event_has_only_the_d_tag() {
        val event = BackupService(RecordingGateway(), clock).buildBackupEvent(
            vaultKey,
            account(10L).copy(accountName = "   "),
            "10",
            BackupService.ASSOCIATED_ACCOUNT_BACKUP_KIND,
        )

        assertEquals(listOf(listOf("d", "10")), event.tags)
    }

    @Test
    fun vault_key_event_never_carries_a_label() {
        val event = BackupService(RecordingGateway(), clock).buildBackupEvent(
            vaultKey,
            vaultKey.copy(accountName = "Not published"),
            BackupService.VAULT_KEY_D_TAG,
            BackupService.VAULT_KEY_BACKUP_KIND,
        )

        assertEquals(listOf(listOf("d", BackupService.VAULT_KEY_D_TAG)), event.tags)
    }

    @Test
    fun recovery_restores_the_account_name_from_the_label_tag() = runTest {
        val event = BackupService(RecordingGateway(), clock).buildBackupEvent(
            vaultKey,
            account(10L).copy(accountName = "Work account"),
            "10",
            BackupService.ASSOCIATED_ACCOUNT_BACKUP_KIND,
        )

        val restored = requireNotNull(recoveryService().decodeAssociatedAccount(event, vaultKey, PASSPHRASE))

        assertEquals("Work account", restored.accountName)
        assertEquals(10L, restored.createdAt)
    }

    @Test
    fun recovery_accepts_an_event_without_a_label() = runTest {
        val event = BackupService(RecordingGateway(), clock).buildBackupEvent(
            vaultKey,
            account(10L),
            "10",
            BackupService.ASSOCIATED_ACCOUNT_BACKUP_KIND,
        )

        val restored = requireNotNull(recoveryService().decodeAssociatedAccount(event, vaultKey, PASSPHRASE))

        assertNull(restored.accountName)
    }

    private fun recoveryService(): RecoveryService = RecoveryService(
        RecordingGateway(),
        mockk<SecureStorageService> { every { getAccounts() } returns emptyList() },
    )

    private fun account(createdAt: Long): Account = Account(
        createdAt = createdAt,
        // Each account needs its own key material so decryption and derivation succeed.
        zenTenKey = SshKeyCodec.normalizeToSingleLineBase64(
            SshKeyCodec.generateEncrypted(createdAt, PASSPHRASE).privateKeyPem,
        ),
        nostr = NostrKeyDerivation.derive(MNEMONIC).toPersistedKeyPair(),
    )

    private class RecordingGateway : NostrRelayGateway {
        override suspend fun publish(event: NostrEvent) = PublishResult(event.id, emptySet(), emptySet(), 1)

        override suspend fun fetchByAuthorAndKind(authorPubkey: String, kind: Int): List<NostrEvent> = emptyList()
    }

    private companion object {
        const val MNEMONIC =
            "alter gap broom kitten orient over settle work honey rule coach system wage effort mask void solid devote divert quarter quote broccoli jaguar lady"
        val PASSPHRASE = "pass".toCharArray()
    }
}