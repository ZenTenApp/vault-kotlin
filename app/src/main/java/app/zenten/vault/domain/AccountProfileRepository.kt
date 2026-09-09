package app.zenten.vault.domain

import app.zenten.vault.data.kv.AppStorageService
import app.zenten.vault.data.kv.CachedAccountProfile
import app.zenten.vault.data.model.Account
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface AccountProfileRepository {
    val profiles: StateFlow<Map<Long, AccountProfile>>
    suspend fun loadCachedProfiles()
    suspend fun refresh(accounts: List<Account>, force: Boolean = true)
    suspend fun refreshIfNeeded(account: Account)
}

@Singleton
class DefaultAccountProfileRepository @Inject constructor(
    private val appStorage: AppStorageService,
    private val profileService: ProfileService,
) : AccountProfileRepository {
    private val _profiles = MutableStateFlow<Map<Long, AccountProfile>>(emptyMap())
    override val profiles: StateFlow<Map<Long, AccountProfile>> = _profiles.asStateFlow()
    private val cacheMutex = Mutex()
    private val refreshMutex = Mutex()
    private var cacheLoaded = false
    private val attemptedAccountIds = mutableSetOf<Long>()
    private var activeRefresh: CompletableDeferred<Unit>? = null
    private var activeAccountIds = emptySet<Long>()

    override suspend fun loadCachedProfiles() = cacheMutex.withLock {
        if (cacheLoaded) return@withLock
        _profiles.value = appStorage.getCachedAccountProfiles().associate { cached ->
            cached.accountId to AccountProfile(cached.displayName, cached.createdAtSeconds ?: 0L)
        }
        cacheLoaded = true
    }

    override suspend fun refresh(accounts: List<Account>, force: Boolean) = refreshCoalesced(accounts, force)

    override suspend fun refreshIfNeeded(account: Account) = refreshCoalesced(listOf(account), force = false)

    private suspend fun refreshCoalesced(accounts: List<Account>, force: Boolean) {
        loadCachedProfiles()
        val requestedIds = accounts.mapTo(mutableSetOf()) { it.createdAt }
        if (requestedIds.isEmpty()) return

        while (true) {
            var ownsRefresh = false
            val active = refreshMutex.withLock {
                activeRefresh?.also { running ->
                    if (requestedIds.all(activeAccountIds::contains)) return@withLock running
                } ?: run {
                    val accountsToRefresh = if (force) accounts else accounts.filter { it.createdAt !in attemptedAccountIds }
                    if (accountsToRefresh.isEmpty()) return
                    ownsRefresh = true
                    activeAccountIds = accountsToRefresh.mapTo(mutableSetOf()) { it.createdAt }
                    CompletableDeferred<Unit>().also { activeRefresh = it }
                }
            }
            if (!ownsRefresh) {
                active.await()
                if (requestedIds.all(attemptedAccountIds::contains)) return
                continue
            }

            var attemptFinished = false
            try {
                fetchAndCache(accounts.filter { it.createdAt in activeAccountIds })
                attemptFinished = true
            } finally {
                refreshMutex.withLock {
                    if (attemptFinished) attemptedAccountIds += activeAccountIds
                    activeAccountIds = emptySet()
                    activeRefresh = null
                    active.complete(Unit)
                }
            }
            return
        }
    }

    private suspend fun fetchAndCache(accounts: List<Account>) {
        try {
            profileService.streamAssociatedAccountProfiles(accounts).collect { fetched ->
                val newer = fetched.filter { (accountId, profile) ->
                    profile.createdAtSeconds > (_profiles.value[accountId]?.createdAtSeconds ?: Long.MIN_VALUE)
                }
                if (newer.isNotEmpty()) {
                    appStorage.upsertCachedAccountProfiles(newer.map { (accountId, profile) ->
                        CachedAccountProfile(accountId, profile.displayName, profile.createdAtSeconds)
                    })
                    _profiles.update { it + newer }
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            // Relay failures leave cached values intact; later foreground refreshes retry.
        }
    }
}
