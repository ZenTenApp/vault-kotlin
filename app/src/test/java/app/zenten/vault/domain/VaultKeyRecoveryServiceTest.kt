package app.zenten.vault.domain

import app.zenten.vault.crypto.NostrKeyDerivation
import app.zenten.vault.crypto.Seedify
import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.crypto.toPersistedKeyPair
import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.model.Account
import app.zenten.vault.data.secure.SecureStorageService
import app.zenten.vault.network.nostr.NostrRelayGateway
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VaultKeyRecoveryServiceTest {
    private val passphrase = "correct horse battery staple"
    private val clock = Clock.fixed(Instant.parse("2024-01-02T03:04:05Z"), ZoneOffset.UTC)

    @Test
    fun nostr_relay_record_is_recovered_from_the_secret_combinations_and_passphrase() = runTest {
        val fixture = Fixture()
        val generated = SshKeyCodec.generateEncrypted(clock.millis(), passphrase.toCharArray())
        val normalized = SshKeyCodec.normalizeToSingleLineBase64(generated.privateKeyPem)
        val seed = SshKeyCodec.parse(normalized, passphrase.toCharArray()).seed
        val braveMnemonic = Seedify.toBraveAccountMnemonic(seed)
        val braveNostr = NostrKeyDerivation.derive(braveMnemonic).toPersistedKeyPair()
        val indices = Seedify.toBraveSyncWordIndices(seed)

        // Publish the Vault Key relay record exactly as the backup path would, under the
        // Brave-derived identity that the 24 words can reconstruct.
        val publisher = Account(1, normalized, passphrase, nostr = braveNostr)
        val event = BackupService(fixture.gateway, clock).buildBackupEvent(
            publisher,
            publisher,
            BackupService.VAULT_KEY_D_TAG,
            BackupService.VAULT_KEY_BACKUP_KIND,
        )
        coEvery {
            fixture.gateway.fetchAuthenticatedByRelay(any(), braveNostr.pubkey, any(), any())
        } returns Result.success(listOf(event))
        every { fixture.repository.sources } returns flowOf(listOf(relaySource))

        val stored = slot<Account>()
        every { fixture.secureStorage.addVaultKey(capture(stored)) } just runs

        val result = fixture.service.recover(indices, passphrase)

        assertTrue(result.isSuccess)
        assertEquals(normalized, stored.captured.zenTenKey)
        assertEquals(passphrase, stored.captured.zenTenPass)
        coVerify(exactly = 1) { fixture.appStorage.setSetupComplete() }
        verify(exactly = 1) { fixture.scheduler.scheduleConsolidation() }
    }

    @Test
    fun unreachable_nostr_source_reports_connectivity_instead_of_a_missing_record() = runTest {
        val fixture = Fixture()
        val seed = SshKeyCodec.parse(
            SshKeyCodec.generateEncrypted(clock.millis(), passphrase.toCharArray()).privateKeyPem,
            passphrase.toCharArray(),
        ).seed
        coEvery { fixture.gateway.fetchAuthenticatedByRelay(any(), any(), any(), any()) } returns
            Result.failure(IOException("relay unreachable"))
        every { fixture.repository.sources } returns flowOf(listOf(relaySource))

        val result = fixture.service.recover(Seedify.toBraveSyncWordIndices(seed), passphrase)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
        verify(exactly = 0) { fixture.secureStorage.addVaultKey(any()) }
    }

    @Test
    fun valid_relay_record_with_wrong_passphrase_does_not_store_a_vault_key() = runTest {
        val fixture = Fixture()
        val generated = SshKeyCodec.generateEncrypted(clock.millis(), passphrase.toCharArray())
        val normalized = SshKeyCodec.normalizeToSingleLineBase64(generated.privateKeyPem)
        val seed = SshKeyCodec.parse(normalized, passphrase.toCharArray()).seed
        val braveNostr = NostrKeyDerivation.derive(Seedify.toBraveAccountMnemonic(seed)).toPersistedKeyPair()
        val publisher = Account(1, normalized, passphrase, nostr = braveNostr)
        val event = BackupService(fixture.gateway, clock).buildBackupEvent(
            publisher,
            publisher,
            BackupService.VAULT_KEY_D_TAG,
            BackupService.VAULT_KEY_BACKUP_KIND,
        )
        coEvery { fixture.gateway.fetchAuthenticatedByRelay(any(), any(), any(), any()) } returns
            Result.success(listOf(event))
        every { fixture.repository.sources } returns flowOf(listOf(relaySource))

        val result = fixture.service.recover(Seedify.toBraveSyncWordIndices(seed), "wrong passphrase")

        assertTrue(result.isFailure)
        verify(exactly = 0) { fixture.secureStorage.addVaultKey(any()) }
    }

    private inner class Fixture {
        val repository: BackupSourceRepository = mockk()
        val secureStorage: SecureStorageService = mockk()
        val appStorage: AppStorageService = mockk()
        val scheduler: BackupWorkScheduler = mockk()
        val gateway: NostrRelayGateway = mockk()

        val service = VaultKeyRecoveryService(
            sourceRepository = repository,
            secureStorage = secureStorage,
            appStorage = appStorage,
            workScheduler = scheduler,
            relayGateway = gateway,
            clock = clock,
        )

        init {
            coJustRun { appStorage.setSetupComplete() }
            every { scheduler.scheduleConsolidation() } just runs
        }
    }

    private companion object {
        val relaySource = BackupSourceConfig(
            id = "test-relay",
            label = "test relay",
            type = BackupSourceType.NOSTR_RELAY,
            endpoint = "wss://relay.example",
            order = 1,
            authentication = SourceAuthentication.NIP42_OPTIONAL,
        )
    }
}
