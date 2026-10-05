package app.zenten.vault.network.nostr

import app.zenten.vault.crypto.NostrKeyDerivation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NostrEventCodecTest {
    private val keys = NostrKeyDerivation.derive(
        "alter gap broom kitten orient over settle work honey rule coach system wage effort mask void solid devote divert quarter quote broccoli jaguar lady",
    )

    @Test
    fun signs_and_verifies_app_owned_event() {
        val event = NostrEventCodec.sign(
            privateKey = keys.privateKey,
            publicKey = keys.publicKey,
            kind = 37_801,
            tags = listOf(listOf("d", "vaultKey"), listOf("t", "ssh-key-backup")),
            content = "encrypted-content",
            createdAtSeconds = 1_700_000_000L,
        )

        assertEquals(keys.publicKey.hex(), event.pubkey)
        assertEquals(37_801, event.kind)
        assertTrue(NostrEventCodec.isValid(event))
        assertFalse(NostrEventCodec.isValid(event.copy(content = "tampered")))
    }

    @Test
    fun default_relays_are_the_two_privileged_backup_relays() {
        assertEquals(
            listOf("wss://appdata1.zentext.me", "wss://appdata2.nostr.box"),
            NostrRelayService.DEFAULT_RELAYS,
        )
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
