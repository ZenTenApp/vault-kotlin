package app.zenten.vault.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VaultDestinationsTest {

    @Test
    fun settings_hasStableRoute() {
        assertEquals("settings", VaultDestinations.SETTINGS)
    }

    @Test
    fun recoverVaultKeySteps_haveStableRoutes() {
        assertEquals("get-started/recover-vault-key", VaultDestinations.RECOVER_VAULT_KEY_GRAPH)
        assertEquals("get-started/recover-vault-key/combinations", VaultDestinations.RECOVER_VAULT_KEY_COMBINATIONS)
        assertEquals("get-started/recover-vault-key/passphrase", VaultDestinations.RECOVER_VAULT_KEY_PASSPHRASE)
    }

    @Test
    fun createVaultKeyIntro_hasStableRoute() {
        assertEquals("get-started/create-vault-key/intro", VaultDestinations.CREATE_VAULT_KEY_INTRO)
    }

    @Test
    fun createVaultKeyPassphraseSteps_haveStableRoutes() {
        assertEquals("get-started/create-vault-key/passphrase", VaultDestinations.CREATE_VAULT_KEY_PASSPHRASE)
        assertEquals(
            "get-started/create-vault-key/confirm-passphrase",
            VaultDestinations.CREATE_VAULT_KEY_CONFIRM_PASSPHRASE,
        )
        assertEquals("get-started/create-vault-key/creating", VaultDestinations.CREATE_VAULT_KEY_CREATING)
    }

    @Test
    fun createVaultKeyBackupSteps_haveStableRoutes() {
        assertEquals("get-started/create-vault-key/backup-intro", VaultDestinations.CREATE_VAULT_KEY_BACKUP_INTRO)
        assertEquals("get-started/create-vault-key/confirm-backup", VaultDestinations.CREATE_VAULT_KEY_CONFIRM_BACKUP)
        assertEquals(
            "get-started/create-vault-key/secret-combinations",
            VaultDestinations.CREATE_VAULT_KEY_SECRET_COMBINATIONS,
        )
        assertEquals(
            "get-started/create-vault-key/confirm-secret-combinations",
            VaultDestinations.CREATE_VAULT_KEY_CONFIRM_SECRET_COMBINATIONS,
        )
    }

    @Test
    fun createVaultKeyGraph_isThePrefixOfItsSteps() {
        // The nested graph route must stay a prefix of every step route so the
        // wizard's shared, graph-scoped ViewModel resolves for all of them.
        assertEquals("get-started/create-vault-key", VaultDestinations.CREATE_VAULT_KEY_GRAPH)
        listOf(
            VaultDestinations.CREATE_VAULT_KEY_INTRO,
            VaultDestinations.CREATE_VAULT_KEY_PASSPHRASE,
            VaultDestinations.CREATE_VAULT_KEY_CONFIRM_PASSPHRASE,
            VaultDestinations.CREATE_VAULT_KEY_CREATING,
            VaultDestinations.CREATE_VAULT_KEY_BACKUP_INTRO,
            VaultDestinations.CREATE_VAULT_KEY_CONFIRM_BACKUP,
            VaultDestinations.CREATE_VAULT_KEY_SECRET_COMBINATIONS,
            VaultDestinations.CREATE_VAULT_KEY_CONFIRM_SECRET_COMBINATIONS,
        ).forEach { route ->
            assertEquals(true, route.startsWith(VaultDestinations.CREATE_VAULT_KEY_GRAPH + "/"), route)
        }
    }

    @Test
    fun secretCombinationsViewSteps_haveStableRoutes() {
        assertEquals("secret-combinations", VaultDestinations.VIEW_SECRET_COMBINATIONS_GRAPH)
        assertEquals("secret-combinations/verify", VaultDestinations.VIEW_SECRET_COMBINATIONS_VERIFY)
        assertEquals("secret-combinations/show", VaultDestinations.VIEW_SECRET_COMBINATIONS_SHOW)
        assertEquals("secret-combinations/confirm", VaultDestinations.VIEW_SECRET_COMBINATIONS_CONFIRM)
        assertEquals("secret-combinations/complete", VaultDestinations.VIEW_SECRET_COMBINATIONS_COMPLETE)
    }

    @Test
    fun secretCombinationsViewGraph_isThePrefixOfItsSteps() {
        // The nested graph route must stay a prefix of every step route so the flow's
        // graph-scoped ViewModel resolves for all of them.
        listOf(
            VaultDestinations.VIEW_SECRET_COMBINATIONS_VERIFY,
            VaultDestinations.VIEW_SECRET_COMBINATIONS_SHOW,
            VaultDestinations.VIEW_SECRET_COMBINATIONS_CONFIRM,
            VaultDestinations.VIEW_SECRET_COMBINATIONS_COMPLETE,
        ).forEach { route ->
            assertEquals(true, route.startsWith(VaultDestinations.VIEW_SECRET_COMBINATIONS_GRAPH + "/"), route)
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
