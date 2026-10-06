package app.zenten.vault.domain

import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.kv.CachedAccountProfile
import app.zenten.vault.data.model.Account
import app.zenten.vault.data.model.NostrKeyPair
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AccountProfileRepositoryTest {
    private val account = Account(10L, "key", "pass", nostr = NostrKeyPair("00".repeat(32), "01".repeat(32)))

    @Test
    fun emits_cached_profile_then_persists_each_newer_relay_result() = runTest {
        val storage = mockk<AppStorageService>()
        val service = mockk<ProfileService>()
        coEvery { storage.getCachedAccountProfiles() } returns listOf(CachedAccountProfile(10L, "Cached", 100L))
        coEvery { storage.upsertCachedAccountProfiles(any()) } returns mockk(relaxed = true)
        coEvery { service.streamAssociatedAccountProfiles(listOf(account)) } returns flow {
            emit(mapOf(10L to AccountProfile("First relay", 200L)))
            emit(mapOf(10L to AccountProfile("Second relay", 300L)))
        }
        val repository = DefaultAccountProfileRepository(storage, service)

        repository.loadCachedProfiles()
        assertEquals("Cached", repository.profiles.value[10L]?.displayName)
        repository.refresh(listOf(account))

        assertEquals("Second relay", repository.profiles.value[10L]?.displayName)
        coVerify(exactly = 2) { storage.upsertCachedAccountProfiles(any()) }
    }

    @Test
    fun detail_refresh_reuses_an_in_flight_list_attempt() = runTest {
        val storage = mockk<AppStorageService>()
        val service = mockk<ProfileService>()
        coEvery { storage.getCachedAccountProfiles() } returns emptyList()
        coEvery { service.streamAssociatedAccountProfiles(listOf(account)) } returns flow { emit(emptyMap()) }
        val repository = DefaultAccountProfileRepository(storage, service)

        val listRefresh = async { repository.refresh(listOf(account)) }
        val detailRefresh = async { repository.refreshIfNeeded(account) }
        listRefresh.await()
        detailRefresh.await()

        coVerify(exactly = 1) { service.streamAssociatedAccountProfiles(listOf(account)) }
    }

    @Test
    fun forced_list_refresh_retries_a_previous_failed_attempt() = runTest {
        val storage = mockk<AppStorageService>()
        val service = mockk<ProfileService>()
        coEvery { storage.getCachedAccountProfiles() } returns emptyList()
        coEvery { service.streamAssociatedAccountProfiles(listOf(account)) } returns flow { emit(emptyMap()) }
        val repository = DefaultAccountProfileRepository(storage, service)

        repository.refresh(listOf(account))
        repository.refresh(listOf(account))

        coVerify(exactly = 2) { service.streamAssociatedAccountProfiles(listOf(account)) }
    }
}
