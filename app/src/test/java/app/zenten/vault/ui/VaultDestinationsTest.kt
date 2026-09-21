package app.zenten.vault.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VaultDestinationsTest {

    @Test
    fun settings_hasStableRoute() {
        assertEquals("settings", VaultDestinations.SETTINGS)
    }

    @Test
    fun createMasterKeyIntro_hasStableRoute() {
        assertEquals("get-started/create-master-key/intro", VaultDestinations.CREATE_MASTER_KEY_INTRO)
    }

    @Test
    fun createMasterKeyPassphraseSteps_haveStableRoutes() {
        assertEquals("get-started/create-master-key/passphrase", VaultDestinations.CREATE_MASTER_KEY_PASSPHRASE)
        assertEquals(
            "get-started/create-master-key/confirm-passphrase",
            VaultDestinations.CREATE_MASTER_KEY_CONFIRM_PASSPHRASE,
        )
    }

    @Test
    fun createMasterKeyBackupSteps_haveStableRoutes() {
        assertEquals("get-started/create-master-key/backup-intro", VaultDestinations.CREATE_MASTER_KEY_BACKUP_INTRO)
        assertEquals("get-started/create-master-key/confirm-backup", VaultDestinations.CREATE_MASTER_KEY_CONFIRM_BACKUP)
        assertEquals(
            "get-started/create-master-key/secret-combinations",
            VaultDestinations.CREATE_MASTER_KEY_SECRET_COMBINATIONS,
        )
    }

    @Test
    fun createMasterKeyGraph_isThePrefixOfItsSteps() {
        // The nested graph route must stay a prefix of every step route so the
        // wizard's shared, graph-scoped ViewModel resolves for all of them.
        assertEquals("get-started/create-master-key", VaultDestinations.CREATE_MASTER_KEY_GRAPH)
        listOf(
            VaultDestinations.CREATE_MASTER_KEY_INTRO,
            VaultDestinations.CREATE_MASTER_KEY_PASSPHRASE,
            VaultDestinations.CREATE_MASTER_KEY_CONFIRM_PASSPHRASE,
            VaultDestinations.CREATE_MASTER_KEY_BACKUP_INTRO,
            VaultDestinations.CREATE_MASTER_KEY_CONFIRM_BACKUP,
            VaultDestinations.CREATE_MASTER_KEY_SECRET_COMBINATIONS,
        ).forEach { route ->
            assertEquals(true, route.startsWith(VaultDestinations.CREATE_MASTER_KEY_GRAPH + "/"), route)
        }
    }

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
        assertEquals("accounts/1234567890/local-backup/export", VaultDestinations.localAccountBackupExport(1234567890L))
        assertEquals("accounts/{id}/local-backup/export", VaultDestinations.LOCAL_ACCOUNT_BACKUP_EXPORT)
    }
}
