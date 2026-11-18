package app.zenten.vault.ui.keys

import app.zenten.vault.data.model.Key
import app.zenten.vault.domain.KeyService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KeysViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun shows_the_key_name_stored_with_the_key() = runTest(dispatcher) {
        val key = Key(10L, "key", keyName = "Cached")
        val keyService = mockk<KeyService>()
        val keys = MutableStateFlow(listOf(key))
        every { keyService.keyUpdates } returns keys
        every { keyService.getKeys() } returns listOf(key)

        val viewModel = KeysViewModel(keyService)
        advanceUntilIdle()
        assertEquals("Cached", viewModel.state.value.keys.single().keyName)
    }

    @Test
    fun displays_keys_restored_after_the_list_is_already_visible() = runTest(dispatcher) {
        val restored = Key(20L, "restored")
        val keyService = mockk<KeyService>()
        val keys = MutableStateFlow<List<Key>>(emptyList())
        every { keyService.keyUpdates } returns keys
        every { keyService.getKeys() } returns emptyList()

        val viewModel = KeysViewModel(keyService)
        advanceUntilIdle()
        keys.value = listOf(restored)
        advanceUntilIdle()

        assertEquals(listOf(restored), viewModel.state.value.keys)
    }
}
