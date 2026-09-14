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

    /** Secure SSH key paste + passphrase + setup progress. */
    const val GET_STARTED_SSH = "get-started/ssh"

    /** Accounts list (main screen after setup). */
    const val ACCOUNTS = "accounts"

    /** Account detail. Arg: {id} = createdAt microsecond timestamp. */
    const val ACCOUNT_DETAIL = "accounts/{id}"

    /** Create new associated account. */
    const val NEW_ACCOUNT = "new-accounts"

    /** Mandatory local-backup step immediately after account creation. */
    const val LOCAL_ACCOUNT_BACKUP_EXPORT = "accounts/{id}/local-backup/export"

    /** Master account and its per-source backup coverage. */
    const val MASTER_ACCOUNT = "master-account"

    /** App settings and vault management entry points. */
    const val SETTINGS = "settings"

    fun accountDetail(id: Long): String = "accounts/$id"
    fun localAccountBackupExport(id: Long): String = "accounts/$id/local-backup/export"
}
