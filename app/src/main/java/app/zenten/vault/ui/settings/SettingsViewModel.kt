package app.zenten.vault.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zenten.vault.crypto.SshKeyCodec
import app.zenten.vault.data.kv.AppStorageService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appStorage: AppStorageService,
) : ViewModel() {
    val sshKdfRounds: StateFlow<Int> = appStorage.sshKdfRounds.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SshKeyCodec.DEFAULT_BCRYPT_PBKDF_ROUNDS,
    )

    fun setSshKdfRounds(rounds: Int) {
        viewModelScope.launch { appStorage.setSshKdfRounds(rounds) }
    }
}
