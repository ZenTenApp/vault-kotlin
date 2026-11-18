package app.zenten.vault.keyhive

import app.zenten.vault.data.model.Key
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class AssociatedKeysProviderContractTest {
    @Test
    fun json_row_returns_plaintext_json_v1() {
        val json = "[\"1720000000000123\"]"
        val row = keyHiveJsonRow(json)

        assertEquals("json_v1", row[0])
        assertEquals(json, row[1])
    }

    @Test
    fun shared_record_contains_only_allowlisted_fields() {
        val pubkeyHex = "00".repeat(32)
        val privkeyHex = "01".repeat(32)
        val key = Key(
            createdAt = 1720000000000123,
            zenTenKey = "passphrase-protected-openssh-key",
            keyName = "  Work key  ",
        )

        val fields = Json.parseToJsonElement(Json.encodeToString(key.toSharedAssociatedKey())).jsonObject

        assertEquals(setOf("createdAt", "zentenKey", "keyName"), fields.keys)
        assertEquals("Work key", fields.getValue("keyName").jsonPrimitive.content)
        assertFalse(fields.values.any { it.toString().contains("must-not-be-shared") })
        // The Nostr identity stays internal: neither half of the key pair crosses the boundary.
        assertFalse(fields.values.any { it.toString().contains(pubkeyHex) })
        assertFalse(fields.values.any { it.toString().contains(privkeyHex) })
    }

    @Test
    fun restored_key_without_a_label_shares_an_empty_name() {
        val key = Key(
            createdAt = 1720000000000123,
            zenTenKey = "passphrase-protected-openssh-key",
        )

        val fields = Json.parseToJsonElement(Json.encodeToString(key.toSharedAssociatedKey())).jsonObject

        // The field is always present as text so sibling apps never have to handle a null.
        assertEquals("", fields.getValue("keyName").jsonPrimitive.content)
    }
}