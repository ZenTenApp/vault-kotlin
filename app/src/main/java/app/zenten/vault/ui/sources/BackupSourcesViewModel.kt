package app.zenten.vault.ui.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zenten.vault.domain.BackupSourceConfig
import app.zenten.vault.domain.BackupSourceRepository
import app.zenten.vault.domain.BackupSourceType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

internal const val MAX_CUSTOM_NOSTR_SOURCES = 3

data class BackupSourcesState(
    val sources: List<BackupSourceConfig> = emptyList(),
    val loading: Boolean = true,
    val customNostrCount: Int = 0,
) {
    val canAddNostr: Boolean get() = customNostrCount < MAX_CUSTOM_NOSTR_SOURCES
}

@HiltViewModel
class BackupSourcesViewModel @Inject constructor(repository: BackupSourceRepository) : ViewModel() {
    val state = repository.sources.map { sources ->
        BackupSourcesState(
            sources = sources,
            loading = false,
            customNostrCount = customNostrSourceCount(sources),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupSourcesState())
}

internal fun customNostrSourceCount(sources: List<BackupSourceConfig>): Int =
    sources.count { !it.isDefault && it.type == BackupSourceType.NOSTR_RELAY }
