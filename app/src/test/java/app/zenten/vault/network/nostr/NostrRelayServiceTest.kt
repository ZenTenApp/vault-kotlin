package app.zenten.vault.network.nostr

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NostrRelayServiceTest {
    private val eventId = "b94ccbb723f3b0c427839d517aceb58221947d15625fce8ef785c18efc795857"

    @Test
    fun auth_required_closed_reason_is_detected_case_insensitively() {
        assertTrue(NostrRelayService.isAuthRequired("auth-required: login required"))
        assertTrue(NostrRelayService.isAuthRequired("  AUTH-REQUIRED: restricted  "))
        assertFalse(NostrRelayService.isAuthRequired("restricted: login required"))
        assertFalse(NostrRelayService.isAuthRequired("authentication failed"))
    }

    @Test
    fun exact_already_exists_response_is_idempotent_success() {
        assertTrue(
            NostrRelayService.isIdempotentDuplicate(
                eventId,
                "blocked: event already exists: $eventId",
            ),
        )
    }

    @Test
    fun duplicate_response_must_name_the_exact_event() {
        assertFalse(
            NostrRelayService.isIdempotentDuplicate(
                eventId,
                "blocked: event already exists: ${"0".repeat(64)}",
            ),
        )
        assertFalse(NostrRelayService.isIdempotentDuplicate(eventId, "blocked: event already exists"))
        assertFalse(NostrRelayService.isIdempotentDuplicate(eventId, "blocked: policy violation: $eventId"))
    }
}
