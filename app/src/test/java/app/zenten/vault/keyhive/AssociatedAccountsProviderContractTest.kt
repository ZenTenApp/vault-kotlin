package app.zenten.vault.keyhive

import app.zenten.vault.data.model.Account
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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
    fun shared_record_contains_only_createdAt_and_zentenKey() {
        val account = Account(
            createdAt = 1720000000000123,
            zenTenKey = "passphrase-protected-openssh-key",
            zenTenPass = "must-not-be-shared",
            mnemonic = "must-not-be-shared",
        )

        val fields = Json.parseToJsonElement(Json.encodeToString(account.toSharedAssociatedAccount())).jsonObject

        assertEquals(setOf("createdAt", "zentenKey"), fields.keys)
        assertFalse(fields.values.any { it.toString().contains("must-not-be-shared") })
    }
}
