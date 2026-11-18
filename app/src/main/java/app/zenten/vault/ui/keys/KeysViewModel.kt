package app.zenten.vault.ui.keys

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zenten.vault.data.model.Key
import app.zenten.vault.domain.KeyService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class KeysViewModel @Inject constructor(
    private val keyService: KeyService,
) : ViewModel() {
    private val _state = MutableStateFlow(KeysState())
    val state: StateFlow<KeysState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            keyService.keyUpdates.collect { keys ->
                _state.update { it.copy(loading = false, keys = keys) }
            }
        }
        refresh()
    }

    /** Reloads the local inventory; names come with the stored keys, so no network is needed. */
    fun refresh() {
        if (_state.value.keys.isEmpty()) _state.value = _state.value.copy(loading = true)
        viewModelScope.launch {
            _state.value = KeysState(loading = false, keys = keyService.getKeys())
        }
    }
}

data class KeysState(
    val loading: Boolean = false,
    val recoveryInProgress: Boolean = false,
    val recoveryMessage: String? = null,
    val keys: List<Key> = emptyList(),
)