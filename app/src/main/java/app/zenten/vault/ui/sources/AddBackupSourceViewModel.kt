package app.zenten.vault.ui.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zenten.vault.domain.BackupSourceRepository
import app.zenten.vault.domain.BackupSourceType
import app.zenten.vault.domain.normalizeSourceEndpoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.URI
import javax.inject.Inject

data class AddBackupSourceState(
    val displayName: String = "",
    val endpoint: String = "",
    val countsTowardQuorum: Boolean = false,
    val useForRecovery: Boolean = false,
    val advancedExpanded: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class AddBackupSourceViewModel @Inject constructor(
    private val repository: BackupSourceRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(AddBackupSourceState())
    val state: StateFlow<AddBackupSourceState> = _state.asStateFlow()

    fun setDisplayName(value: String) = update { copy(displayName = value) }
    fun setEndpoint(value: String) = update { copy(endpoint = value) }
    fun setCountsTowardQuorum(value: Boolean) = update { copy(countsTowardQuorum = value) }
    fun setUseForRecovery(value: Boolean) = update { copy(useForRecovery = value) }
    fun setAdvancedExpanded(value: Boolean) = update { copy(advancedExpanded = value) }
    fun messageShown() = update { copy(message = null) }

    fun save() {
        val submitted = _state.value
        if (submitted.saving || submitted.saved) return
        if (submitted.endpoint.isBlank()) {
            _state.value = submitted.copy(message = "Enter a relay URL")
            return
        }
        _state.value = submitted.copy(saving = true, message = null)
        viewModelScope.launch {
            runCatching {
                val current = repository.current()
                require(customNostrSourceCount(current) < MAX_CUSTOM_NOSTR_SOURCES) {
                    "You can add up to 3 custom relays"
                }
                val normalized = normalizeSourceEndpoint(BackupSourceType.NOSTR_RELAY, submitted.endpoint)
                repository.addCustom(
                    label = customSourceLabel(submitted.displayName, normalized),
                    type = BackupSourceType.NOSTR_RELAY,
                    endpoint = normalized,
                    enabled = true,
                    countsTowardQuorum = submitted.countsTowardQuorum,
                    restoreEnabled = submitted.useForRecovery,
                    trustedForRestore = submitted.useForRecovery,
                )
            }.onSuccess {
                _state.value = submitted.copy(saving = false, saved = true, message = null)
            }.onFailure { error ->
                _state.value = submitted.copy(saving = false, message = sourceErrorMessage(error))
            }
        }
    }

    private fun update(transform: AddBackupSourceState.() -> AddBackupSourceState) {
        _state.value = _state.value.transform()
    }
}

internal fun customSourceLabel(displayName: String, normalizedEndpoint: String): String =
    displayName.trim().ifBlank { requireNotNull(URI(normalizedEndpoint).host) }

internal fun sourceErrorMessage(error: Throwable): String {
    val message = error.message.orEmpty()
    return when {
        message == "You can add up to 3 custom relays" -> message
        message == "Backup source already exists" -> "That backup source already exists"
        message.contains("must use wss", ignoreCase = true) ||
            message.contains("include a scheme", ignoreCase = true) -> "Nostr relay URLs must use wss://"
        message.contains("credentials, query, or fragment", ignoreCase = true) ->
            "Relay URLs cannot include credentials, a query, or a fragment"
        error is IllegalArgumentException || error is java.net.URISyntaxException -> "Enter a valid Nostr relay URL"
        else -> "Could not add relay"
    }
}
