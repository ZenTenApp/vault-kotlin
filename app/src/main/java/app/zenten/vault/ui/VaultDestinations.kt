package app.zenten.vault.ui

/**
 * Navigation routes for the PoC.
 *
 * Routes for the current proof-of-concept screens.
 */
object VaultDestinations {
    /** App entry — redirects based on setup state. */
    const val ROOT = "root"

    /** Welcome / onboarding root. */
    const val GET_STARTED = "get-started"

    /** SSH key entry + passphrase + setup progress. */
    const val GET_STARTED_SSH = "get-started/ssh"

    /** Pre-setup configured backup-source list. */
    const val BACKUP_SOURCES = "backup-sources"

    /** Add a pre-setup custom Nostr relay. */
    const val ADD_BACKUP_SOURCE = "backup-sources/add"

    /** Accounts list (main screen after setup). */
    const val ACCOUNTS = "accounts"

    /** Account detail. Arg: {id} = createdAt microsecond timestamp. */
    const val ACCOUNT_DETAIL = "accounts/{id}"

    /** Create new associated account. */
    const val NEW_ACCOUNT = "new-accounts"

    /** Master account and its per-source backup coverage. */
    const val MASTER_ACCOUNT = "master-account"

    fun accountDetail(id: Long): String = "accounts/$id"
}
