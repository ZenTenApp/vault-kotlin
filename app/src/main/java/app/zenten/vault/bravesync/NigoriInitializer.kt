package app.zenten.vault.bravesync

import app.zenten.vault.bravesync.crypto.NigoriCrypto
import app.zenten.vault.bravesync.proto.ProtoWire
import app.zenten.vault.bravesync.proto.SyncMessages
import java.time.Clock
import java.util.Base64

/** Creates the PBKDF2 Nigori node required by a newly-created Brave Sync chain. */
internal object NigoriInitializer {
    fun entity(syncCode: String, clock: Clock = Clock.systemUTC()): ByteArray {
        val keys = NigoriCrypto.derivePbkdf2Keys(syncCode)
        val userKey = NigoriCrypto.derivePbkdf2UserKey(syncCode)
        val keyName = NigoriCrypto.computeKeyName(keys)
        val key = ProtoWire.message {
            string(1, keyName)
            bytes(2, userKey)
            bytes(3, keys.encryptionKey)
            bytes(4, keys.macKey)
        }
        // NigoriKeyBag.key is field 2 (not field 1).
        val keybag = ProtoWire.message { message(2, key) }
        val encryptedKeybag = encryptedData(keybag, keys)
        val now = clock.millis()
        val nigori = ProtoWire.message {
            message(1, encryptedKeybag)
            varint(2, 1)
            varint(24, 1)
            varint(30, 4) // CUSTOM_PASSPHRASE
            varint(33, now)
            varint(45, BraveSyncConfig.KEY_DERIVATION_PBKDF2.toLong())
        }
        val specifics = ProtoWire.message { message(BraveSyncConfig.NIGORI_TYPE_ID, nigori) }
        return ProtoWire.message {
            string(1, "nigori")
            varint(4, 0)
            varint(5, now)
            varint(6, now)
            string(7, "Nigori")
            message(21, specifics)
            varint(22, 0)
        }
    }

    fun settings(): SyncMessages.NigoriSettings = SyncMessages.NigoriSettings(
        passphraseType = 4,
        keyDerivationMethod = BraveSyncConfig.KEY_DERIVATION_PBKDF2,
        salt = null,
        encryptEverything = true,
    )

    private fun encryptedData(plaintext: ByteArray, keys: NigoriCrypto.Keys): ByteArray = ProtoWire.message {
        string(1, NigoriCrypto.computeKeyName(keys))
        string(2, Base64.getEncoder().encodeToString(NigoriCrypto.encrypt(plaintext, keys)))
    }
}
