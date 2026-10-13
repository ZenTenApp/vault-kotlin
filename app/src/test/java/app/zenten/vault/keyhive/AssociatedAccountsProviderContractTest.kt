package app.zenten.vault.keyhive

import app.zenten.vault.data.model.Account
import app.zenten.vault.data.model.NostrKeyPair
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class AssociatedAccountsProviderContractTest {
    @Test
    fun json_row_returns_plaintext_json_v1() {
        val json = "[\"1720000000000123\"]"
        val row = keyHiveJsonRow(json)

        assertEquals("json_v1", row[0])
        assertEquals(json, row[1])
    }

    @Test
    fun shared_record_contains_only_allowlisted_public_fields() {
        val pubkeyHex = "00".repeat(32)
        val privkeyHex = "01".repeat(32)
        val account = Account(
            createdAt = 1720000000000123,
            zenTenKey = "passphrase-protected-openssh-key",
            zenTenPass = "must-not-be-shared",
            nostr = NostrKeyPair(pubkeyHex, privkeyHex),
        )

        val fields = Json.parseToJsonElement(Json.encodeToString(account.toSharedAssociatedAccount())).jsonObject

        assertEquals(setOf("createdAt", "zentenKey", "nostrPubkey"), fields.keys)
        assertEquals(pubkeyHex, fields.getValue("nostrPubkey").jsonPrimitive.content)
        assertFalse(fields.values.any { it.toString().contains("must-not-be-shared") })
        assertFalse(fields.values.any { it.toString().contains(privkeyHex) })
    }
}