package app.zenten.vault.bravesync.password

import app.zenten.vault.bravesync.BraveSyncConfig
import app.zenten.vault.bravesync.crypto.NigoriCrypto
import app.zenten.vault.bravesync.proto.ProtoWire
import java.net.URI
import java.time.Clock
import java.util.Base64
import java.util.UUID

/** Builds the password entities that Brave creates for manually-added credentials. */
internal object PasswordEntityCodec {
    data class PasswordNote(val value: String, val hidden: Boolean = false)
    data class PasswordEntry(
        val id: String,
        val version: Long,
        val clientTagHash: String,
        val signonRealm: String,
        val origin: String,
        val username: String,
        val password: String,
        val notes: List<PasswordNote> = emptyList(),
    )
    data class NewPassword(val url: String, val username: String, val password: String, val note: PasswordNote? = null)

    fun create(input: NewPassword, keys: NigoriCrypto.Keys, clock: Clock = Clock.systemUTC(), id: String = UUID.randomUUID().toString()): ByteArray =
        entity(id, 0, input, keys, deleted = false, clock)

    fun update(existing: PasswordEntry, password: String, note: PasswordNote?, keys: NigoriCrypto.Keys, clock: Clock = Clock.systemUTC()): ByteArray =
        entity(existing.id, existing.version, NewPassword(existing.origin, existing.username, password, note), keys, deleted = false, clock)

    fun delete(existing: PasswordEntry, keys: NigoriCrypto.Keys, clock: Clock = Clock.systemUTC()): ByteArray =
        entity(existing.id, existing.version, NewPassword(existing.origin, existing.username, ""), keys, deleted = true, clock)

    private fun entity(id: String, version: Long, input: NewPassword, keys: NigoriCrypto.Keys, deleted: Boolean, clock: Clock): ByteArray {
        val (realm, origin) = urlComponents(input.url)
        val nowMillis = clock.millis()
        val nowWindowsMicros = nowMillis * 1_000 + BraveSyncConfig.WINDOWS_EPOCH_OFFSET_SECONDS * 1_000_000
        val payload = passwordData(realm, origin, input.username, input.password, input.note, nowWindowsMicros, deleted)
        val encrypted = encryptedData(payload, keys)
        val passwordSpecifics = ProtoWire.message {
            message(1, encrypted)
            if (!deleted) message(5, encryptedData(payload, keys))
        }
        val specifics = ProtoWire.message { message(BraveSyncConfig.PASSWORD_TYPE_ID, passwordSpecifics) }
        val uniqueTag = PasswordUniqueTag.compute(origin, "", input.username, "", realm)
        // Build the outer Chromium "SyncEntity" protobuf. ProtoWire.message runs this lambda
        // against a Writer; each call writes one field as a "tag + value", where the tag encodes
        // both the field number and the wire type (varint vs. length-delimited). The numbers and
        // types below are dictated by Chromium's sync.proto and must match it exactly.
        return ProtoWire.message {
            string(1, id)                              // entity id (server assigns the real one on commit for new records)
            varint(4, version)                         // optimistic-concurrency version; 0 means "new entity"
            varint(5, nowMillis)                       // ctime: creation time, Unix epoch milliseconds
            varint(6, nowMillis)                       // mtime: last-modified time, Unix epoch milliseconds
            string(7, realm)                           // "name" of the entity; for passwords this is the signon realm
            if (deleted) varint(18, 1)                 // tombstone flag; omitted entirely (i.e. false) for live records
            string(19, "")                             // originator cache GUID (empty: we don't track the originating device)
            string(20, "")                             // originator client item id (empty for the same reason)
            message(21, specifics)                     // EntitySpecifics: the type-specific payload (here, password data)
            varint(22, 0)                              // folder flag = false; passwords are leaf items, not folders
            string(23, PasswordUniqueTag.clientTagHash(uniqueTag)) // stable cross-device identity for this credential
        }
    }

    private fun passwordData(realm: String, origin: String, username: String, password: String, note: PasswordNote?, nowWindowsMicros: Long, deleted: Boolean): ByteArray = ProtoWire.message {
        // Chromium's PasswordSpecificsData message. Field numbers and meanings are from
        // components/sync/protocol/password_specifics.proto and must match it exactly.
        varint(1, 0)        // scheme: 0 = SCHEME_HTML (a saved login form)
        string(2, realm)    // signon_realm: where the credential is stored and refilled
        string(3, origin)   // origin: scheme://host[:port]/path
        string(4, "")       // action: form submit target (empty: not scraped from a form)
        string(5, "")       // username_element: form field name (empty: not scraped)
        string(6, username) // username_value: the actual username
        string(7, "")       // password_element: form field name (empty: not scraped)
        string(8, password) // password_value: the actual password
        if (!deleted) {
            varint(11, nowWindowsMicros) // date_created (Windows-epoch microseconds)
            varint(12, 0)                // blacklisted: false (site isn't blocked from saving)
            varint(13, 3)                // type: 3 = kManuallyAdded (typed by the user, not captured)
            varint(14, 0)                // times_used: 0 (no form logins recorded)
            string(15, "")               // display_name: account holder name (unused)
            string(16, "")               // avatar_url (unused)
            string(17, "")               // federation_url: IdP URL (unused; not a federated login)
            varint(18, 0)                // date_last_used: 0 (never used to log in)
            message(19, ProtoWire.message {}) // password_issues: empty set (no leak/reuse detected)
            varint(20, nowWindowsMicros) // date_password_modified (Windows-epoch microseconds)
            // notes (field 22) is the authoritative note container. Its field 1 is a
            // Note with: 1=unique_display_name, 2=value, 3=date_created, 4=hide_by_default.
            note?.let { value ->
                message(22, ProtoWire.message {
                    message(1, ProtoWire.message {
                        string(1, "")                         // unique_display_name (one note, so empty is fine)
                        string(2, value.value)                // Note.value: the note text
                        varint(3, nowWindowsMicros)           // Note.date_created (Windows-epoch micros)
                        varint(4, if (value.hidden) 1 else 0) // Note.hide_by_default
                    })
                })
            }
            string(23, "")       // sender_email (password sharing; unused)
            string(24, "")       // sender_name (password sharing; unused)
            varint(25, 0)        // date_received (password sharing; 0 = not shared to us)
            varint(26, 0)        // sharing_notification_displayed: false
            string(27, "")       // sender_profile_image_url (password sharing; unused)
        } else {
            // Tombstone: keep only the minimal fields a reader still expects.
            varint(12, 0) // blacklisted: false
            varint(13, 3) // type: kManuallyAdded
        }
    }

    private fun encryptedData(plaintext: ByteArray, keys: NigoriCrypto.Keys): ByteArray = ProtoWire.message {
        string(1, NigoriCrypto.computeKeyName(keys))
        string(2, Base64.getEncoder().encodeToString(NigoriCrypto.encrypt(plaintext, keys)))
    }

    private fun urlComponents(url: String): Pair<String, String> {
        val uri = try { URI(url) } catch (e: Exception) { throw IllegalArgumentException("Invalid password URL", e) }
        require(uri.scheme == "https" || uri.scheme == "http") { "Password URL must use HTTP or HTTPS" }
        require(!uri.host.isNullOrEmpty()) { "Password URL must have a host" }
        val port = when {
            uri.port == -1 || uri.port == 80 || uri.port == 443 -> ""
            else -> ":${uri.port}"
        }
        val realm = "${uri.scheme}://${uri.host}$port/"
        return realm to realm
    }
}
