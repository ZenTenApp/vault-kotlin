package app.zenten.vault.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VaultDestinationsTest {

    @Test
    fun accountDetail_buildsRouteFromId() {
        assertEquals("accounts/1234567890", VaultDestinations.accountDetail(1234567890L))
    }

    @Test
    fun accountDetail_routeMatchesTemplateShape() {
        // Template uses {id}; built route substitutes the numeric id.
        assertEquals("accounts/{id}", VaultDestinations.ACCOUNT_DETAIL)
        assertEquals("accounts/0", VaultDestinations.accountDetail(0L))
    }

    @Test
    fun localAccountBackup_buildsRoutesFromId() {
        assertEquals("accounts/1234567890/local-backup/qr", VaultDestinations.localAccountBackupQr(1234567890L))
        assertEquals("accounts/1234567890/local-backup/export", VaultDestinations.localAccountBackupExport(1234567890L))
        assertEquals("accounts/{id}/local-backup/qr", VaultDestinations.LOCAL_ACCOUNT_BACKUP_QR)
        assertEquals("accounts/{id}/local-backup/export", VaultDestinations.LOCAL_ACCOUNT_BACKUP_EXPORT)
    }
}
