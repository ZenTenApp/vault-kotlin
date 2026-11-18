package app.zenten.vault.domain

import java.util.concurrent.ThreadLocalRandom

/**
 * Allocates the `createdAt` value that identifies a new key.
 *
 * The value is wall-clock microseconds with the sub-millisecond part filled by a random 1–999
 * instead of zeros. It is an identifier and a sort key, not a precise timestamp: `createdAt` is
 * the canonical key ID, so two keys created in the same millisecond must still get
 * distinguishable values. Filling the tail also keeps an ID from advertising that it was produced
 * by whole-millisecond arithmetic.
 *
 * The random tail is uniqueness jitter, not a security control: only 999 values are available
 * within one millisecond, and this helper does not consult already-stored IDs. Callers that can
 * race with each other also need a monotonic guard (see [KeyService.nextCreatedAt]).
 *
 * @param nowMillis the wall-clock time in milliseconds. Pass an injected clock where one exists so
 * tests can pin the base time; the tail stays random either way.
 */
internal fun newKeyCreatedAt(nowMillis: Long = System.currentTimeMillis()): Long =
    nowMillis * 1_000L + ThreadLocalRandom.current().nextLong(1, 1_000)
