package app.zenten.vault.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VaultDestinationsTest {

    @Test
    fun sourceManagement_routes_are_stable() {
        assertEquals("backup-sources", VaultDestinations.BACKUP_SOURCES)
        assertEquals("backup-sources/add", VaultDestinations.ADD_BACKUP_SOURCE)
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
}
