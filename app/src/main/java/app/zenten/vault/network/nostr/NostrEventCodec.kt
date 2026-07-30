package app.zenten.vault.network.nostr

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.EventAssembler
import com.vitorpamplona.quartz.nip01Core.crypto.verify

/** Quartz adapter: event construction and signature validation never leak past networking. */
object NostrEventCodec {
    fun sign(
        privateKey: ByteArray,
        publicKey: ByteArray,
        kind: Int,
        tags: List<List<String>>,
        content: String,
        createdAtSeconds: Long,
    ): NostrEvent {
        val event = EventAssembler.hashAndSign<Event>(
            pubKey = publicKey.hex(),
            createdAt = createdAtSeconds,
            kind = kind,
            tags = tags.map { it.toTypedArray() }.toTypedArray(),
            content = content,
            privKey = privateKey,
        )
        check(event.verify()) { "Quartz produced an invalid signed event" }
        return event.toAppEvent()
    }

    fun isValid(event: NostrEvent): Boolean = runCatching { event.toQuartzEvent().verify() }.getOrDefault(false)

    internal fun NostrEvent.toQuartzEvent() = Event(id, pubkey, createdAt, kind, tags.map { it.toTypedArray() }.toTypedArray(), content, sig)

    internal fun Event.toAppEvent() = NostrEvent(id, pubKey, createdAt, kind, tags.map { it.toList() }, content, sig)
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
