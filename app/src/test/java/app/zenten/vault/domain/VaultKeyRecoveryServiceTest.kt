package app.zenten.vault.domain

import app.zenten.vault.crypto.NostrKeyDerivation
import app.zenten.vault.crypto.Seedify
import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.crypto.toPersistedKeyPair
import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.model.Account
import app.zenten.vault.data.secure.SecureStorageService
import app.zenten.vault.network.nostr.NostrEvent
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
        assertNostrRelayRecoverySucceeds(clock)
    }

    @Test
    fun nostr_relay_record_is_recovered_after_the_brave_wordlist_horizon() = runTest {
        // Day 4096 is past the 2,048-word list. The 25th word must wrap instead of throwing so
        // that relay recovery still runs; this service has no Brave source configured, so a
        // throw here would previously have blocked the working Nostr path too.
        assertNostrRelayRecoverySucceeds(Clock.fixed(Instant.parse("2033-07-27T00:00:00Z"), ZoneOffset.UTC))
    }

    private suspend fun assertNostrRelayRecoverySucceeds(serviceClock: Clock) {
        val fixture = Fixture(serviceClock)
        val generated = SshKeyCodec.generateEncrypted(clock.millis(), passphrase.toCharArray())
        val normalized = SshKeyCodec.normalizeToSingleLineBase64(generated.privateKeyPem)
        val seed = SshKeyCodec.parse(normalized, passphrase.toCharArray()).seed
        val vaultKeyBackupMnemonic = Seedify.toVaultKeyBackupMnemonic(seed)
        val vaultKeyBackupNostr = NostrKeyDerivation.derive(vaultKeyBackupMnemonic).toPersistedKeyPair()
        val indices = Seedify.toVaultKeyBackupWordIndices(seed)

        // Publish the Vault Key relay record exactly as the backup path would, under the
        // Vault Key backup (`vkbckp`)-derived identity that the 24 words can reconstruct.
        val publisher = Account(1, normalized, nostr = vaultKeyBackupNostr)
        val event = BackupService(fixture.gateway, clock).buildBackupEvent(
            publisher,
            publisher,
            BackupService.VAULT_KEY_D_TAG,
            BackupService.VAULT_KEY_BACKUP_KIND,
        )
        coEvery {
            fixture.gateway.fetchAuthenticatedByRelay(any(), vaultKeyBackupNostr.pubkey, any(), any())
        } returns Result.success(listOf(event))
        every { fixture.repository.sources } returns flowOf(listOf(relaySource))

        val stored = slot<Account>()
        every { fixture.secureStorage.addVaultKey(capture(stored)) } just runs

        val result = fixture.service.recover(indices, passphrase)

        assertTrue(result.isSuccess)
        assertEquals(normalized, stored.captured.zenTenKey)
        // Recovery records the validated passphrase in the session so the follow-up backup runs.
        assertTrue(fixture.session.isUnlocked.value)
        coVerify(exactly = 1) { fixture.appStorage.setSetupComplete() }
        // The copy was just read back from a source, so the completion quorum check is satisfied.
        coVerify(exactly = 1) { fixture.appStorage.setVaultKeyQuorumReached(true) }
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

        val result = fixture.service.recover(Seedify.toVaultKeyBackupWordIndices(seed), passphrase)

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
        val vaultKeyBackupNostr = NostrKeyDerivation.derive(Seedify.toVaultKeyBackupMnemonic(seed)).toPersistedKeyPair()
        val publisher = Account(1, normalized, nostr = vaultKeyBackupNostr)
        val event = BackupService(fixture.gateway, clock).buildBackupEvent(
            publisher,
            publisher,
            BackupService.VAULT_KEY_D_TAG,
            BackupService.VAULT_KEY_BACKUP_KIND,
        )
        coEvery { fixture.gateway.fetchAuthenticatedByRelay(any(), any(), any(), any()) } returns
            Result.success(listOf(event))
        every { fixture.repository.sources } returns flowOf(listOf(relaySource))

        val result = fixture.service.recover(Seedify.toVaultKeyBackupWordIndices(seed), "wrong passphrase")

        assertTrue(result.isFailure)
        verify(exactly = 0) { fixture.secureStorage.addVaultKey(any()) }
    }

    // --- H4: bind the recovered key to the supplied 24 words ---

    /** Recovery must reject a copy that decrypts but belongs to a different key's recovery words. */
    @Test
    fun key_that_decrypts_but_belongs_to_other_words_is_not_stored() = runTest {
        val fixture = Fixture()
        val wanted = fixture.generateKey()
        val other = fixture.generateKey()
        // Publish the *other* key's material under the wanted key's recovery identity, same passphrase.
        coEvery { fixture.gateway.fetchAuthenticatedByRelay(any(), any(), any(), any()) } returns
            Result.success(listOf(fixture.eventFor(wanted.words, other)))
        every { fixture.repository.sources } returns flowOf(listOf(relaySource))

        val result = fixture.service.recover(wanted.indices, passphrase)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("does not belong"))
        verify(exactly = 0) { fixture.secureStorage.addVaultKey(any()) }
        coVerify(exactly = 0) { fixture.appStorage.setSetupComplete() }
        assertTrue(!fixture.session.isUnlocked.value)
    }

    /** A corrupt newest copy must not mask an older valid copy on the same source. */
    @Test
    fun corrupt_newest_copy_does_not_block_older_valid_copy() = runTest {
        val fixture = Fixture()
        val key = fixture.generateKey()
        val valid = fixture.eventFor(key.words, key, atSeconds = 1_000)
        // Corrupt copy: not a NIP-44 payload, so decoding skips it without invalidating its signature.
        val corrupt = valid.copy(content = "not-a-nip44-payload")
        coEvery { fixture.gateway.fetchAuthenticatedByRelay(any(), any(), any(), any()) } returns
            Result.success(listOf(corrupt, valid))
        every { fixture.repository.sources } returns flowOf(listOf(relaySource))
        every { fixture.secureStorage.addVaultKey(any()) } just runs

        val result = fixture.service.recover(key.indices, passphrase)

        assertTrue(result.isSuccess)
        verify(exactly = 1) { fixture.secureStorage.addVaultKey(any()) }
    }

    /** A copy for another key must not block a later valid copy from the same source. */
    @Test
    fun mismatched_newest_copy_does_not_block_older_valid_copy() = runTest {
        val fixture = Fixture()
        val key = fixture.generateKey()
        val other = fixture.generateKey()
        val valid = fixture.eventFor(key.words, key, atSeconds = 1_000)
        // The other key's material, signed under the wanted key's identity, as the newest copy.
        val misbound = fixture.eventFor(key.words, other, atSeconds = 2_000)
        coEvery { fixture.gateway.fetchAuthenticatedByRelay(any(), any(), any(), any()) } returns
            Result.success(listOf(misbound, valid))
        every { fixture.repository.sources } returns flowOf(listOf(relaySource))
        every { fixture.secureStorage.addVaultKey(any()) } just runs

        val result = fixture.service.recover(key.indices, passphrase)

        assertTrue(result.isSuccess)
        verify(exactly = 1) { fixture.secureStorage.addVaultKey(any()) }
    }

    /** A custom source the user did not mark trusted for restore cannot supply the Vault Key. */
    @Test
    fun untrusted_source_is_not_queried_for_recovery() = runTest {
        val fixture = Fixture()
        val key = fixture.generateKey()
        coEvery { fixture.gateway.fetchAuthenticatedByRelay(any(), any(), any(), any()) } returns
            Result.success(listOf(fixture.eventFor(key.words, key)))
        every { fixture.repository.sources } returns
            flowOf(listOf(relaySource.copy(trustedForRestore = false)))

        val result = fixture.service.recover(key.indices, passphrase)

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { fixture.gateway.fetchAuthenticatedByRelay(any(), any(), any(), any()) }
        verify(exactly = 0) { fixture.secureStorage.addVaultKey(any()) }
    }

    private class GeneratedKey(
        val normalized: String,
        val seed: ByteArray,
        val words: String,
        val indices: List<Int>,
    )

    private inner class Fixture(serviceClock: Clock = clock) {
        fun generateKey(): GeneratedKey {
            val generated = SshKeyCodec.generateEncrypted(clock.millis(), passphrase.toCharArray())
            val normalized = SshKeyCodec.normalizeToSingleLineBase64(generated.privateKeyPem)
            val seed = SshKeyCodec.parse(normalized, passphrase.toCharArray()).seed
            return GeneratedKey(
                normalized = normalized,
                seed = seed,
                words = Seedify.toVaultKeyBackupMnemonic(seed),
                indices = Seedify.toVaultKeyBackupWordIndices(seed),
            )
        }

        /**
         * Builds a relay event as the backup path would: [key]'s material signed and NIP-44
         * encrypted under [words]'s backup identity. Passing a key whose words differ from [words]
         * reproduces a misbound copy. [atSeconds] must be set when signing, because changing
         * `createdAt` afterwards invalidates the signature.
         */
        fun eventFor(words: String, key: GeneratedKey, atSeconds: Long = clock.instant().epochSecond): NostrEvent {
            val nostr = NostrKeyDerivation.derive(words).toPersistedKeyPair()
            val publisher = Account(1, key.normalized, nostr = nostr)
            val eventClock = Clock.fixed(Instant.ofEpochSecond(atSeconds), ZoneOffset.UTC)
            return BackupService(gateway, eventClock).buildBackupEvent(
                publisher,
                publisher,
                BackupService.VAULT_KEY_D_TAG,
                BackupService.VAULT_KEY_BACKUP_KIND,
            )
        }

        val repository: BackupSourceRepository = mockk()
        val secureStorage: SecureStorageService = mockk()
        val appStorage: AppStorageService = mockk()
        val scheduler: BackupWorkScheduler = mockk()
        val gateway: NostrRelayGateway = mockk()
        val session = VaultKeySession(secureStorage)

        val service = VaultKeyRecoveryService(
            sourceRepository = repository,
            secureStorage = secureStorage,
            appStorage = appStorage,
            workScheduler = scheduler,
            relayGateway = gateway,
            vaultKeySession = session,
            clock = serviceClock,
        )

        init {
            coJustRun { appStorage.setSetupComplete() }
            coJustRun { appStorage.setVaultKeyQuorumReached(any()) }
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
            // Recovery only reads sources the user marked trusted; defaults all set this.
            trustedForRestore = true,
            authentication = SourceAuthentication.NIP42_OPTIONAL,
        )
    }
}
