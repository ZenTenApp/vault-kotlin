package app.zenten.vault.bravesync

import app.zenten.vault.bravesync.auth.BraveSyncAuth
import app.zenten.vault.bravesync.crypto.NigoriCrypto
import app.zenten.vault.bravesync.proto.SyncMessages
import app.zenten.vault.bravesync.transport.BraveSyncTransport

/**
 * Password Sync connection lifecycle. Password entity support is added next;
 * this client currently authenticates, discovers, and derives a chain's Nigori keys.
 */
internal class BraveSyncClient(
    private val transport: BraveSyncTransport = BraveSyncTransport(),
) {
    data class Session(
        val clientId: String,
        val encryptionKeys: NigoriCrypto.Keys,
        val nigoriSettings: SyncMessages.NigoriSettings,
    )

    fun connect(existingSyncCode: String, nowMillis: Long = System.currentTimeMillis()): Session {
        val entropy = BraveSyncAuth.mnemonicEntropy(existingSyncCode)
        val keyPair = BraveSyncAuth.deriveKeyPair(entropy)
        val clientId = BraveSyncAuth.clientId(keyPair.publicKey)
        val token = BraveSyncAuth.generateAuthToken(keyPair, nowMillis)
        entropy.fill(0)

        val request = SyncMessages.getUpdatesRequest(BraveSyncConfig.NIGORI_TYPE_ID)
        val response = SyncMessages.parseResponse(transport.post(clientId, token, request))
        require(response.errorCode == 0) { "Brave Sync server error ${response.errorCode}: ${response.errorMessage.orEmpty()}" }
        val settings = requireNotNull(SyncMessages.nigoriSettings(response)) {
            "Brave Sync chain is not initialized; Nigori initialization will be added before write support"
        }
        val keys = NigoriCrypto.deriveKeys(existingSyncCode, settings.keyDerivationMethod, settings.salt)
        return Session(clientId, keys, settings)
    }
}
