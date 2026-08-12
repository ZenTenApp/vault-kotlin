package app.zenten.vault.ui.sources

import app.zenten.vault.domain.BackupSourceConfig
import app.zenten.vault.domain.BackupSourceType
import app.zenten.vault.domain.SourceAuthentication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BackupSourceUiTest {
    @Test
    fun custom_count_includes_only_custom_nostr_sources() {
        val sources = listOf(
            source("default", BackupSourceType.NOSTR_RELAY, isDefault = true),
            source("nostr-1", BackupSourceType.NOSTR_RELAY),
            source("nostr-2", BackupSourceType.NOSTR_RELAY),
            source("brave", BackupSourceType.BRAVE_SYNC),
        )

        assertEquals(2, customNostrSourceCount(sources))
        assertEquals(true, BackupSourcesState(customNostrCount = 2).canAddNostr)
        assertEquals(false, BackupSourcesState(customNostrCount = 3).canAddNostr)
    }

    @Test
    fun blank_label_uses_normalized_endpoint_hostname() {
        assertEquals("relay.example", customSourceLabel("  ", "wss://relay.example/path"))
        assertEquals("My relay", customSourceLabel("  My relay  ", "wss://relay.example"))
    }

    @Test
    fun expected_repository_errors_have_safe_user_messages() {
        assertEquals("That backup source already exists", sourceErrorMessage(IllegalArgumentException("Backup source already exists")))
        assertEquals("Nostr relay URLs must use wss://", sourceErrorMessage(IllegalArgumentException("Nostr sources must use wss")))
        assertEquals(
            "Relay URLs cannot include credentials, a query, or a fragment",
            sourceErrorMessage(IllegalArgumentException("Source URL cannot contain credentials, query, or fragment")),
        )
        assertEquals("Enter a valid Nostr relay URL", sourceErrorMessage(IllegalArgumentException("Source URL must include a host")))
        assertEquals("Could not add relay", sourceErrorMessage(IllegalStateException("disk detail")))
    }

    private fun source(id: String, type: BackupSourceType, isDefault: Boolean = false) = BackupSourceConfig(
        id = id,
        label = id,
        type = type,
        endpoint = if (type == BackupSourceType.NOSTR_RELAY) "wss://$id.example" else "https://$id.example",
        order = 1,
        isDefault = isDefault,
        authentication = if (type == BackupSourceType.NOSTR_RELAY) {
            SourceAuthentication.NIP42_OPTIONAL
        } else {
            SourceAuthentication.BRAVE_SYNC_TOKEN
        },
    )
}
