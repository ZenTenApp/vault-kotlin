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

    /** Accounts list (main screen after setup). */
    const val ACCOUNTS = "accounts"

    /** Account detail. Arg: {id} = createdAt microsecond timestamp. */
    const val ACCOUNT_DETAIL = "accounts/{id}"

    /** Create new associated account. */
    const val NEW_ACCOUNT = "new-accounts"

    /** First mandatory local-backup step immediately after account creation. */
    const val LOCAL_ACCOUNT_BACKUP_QR = "accounts/{id}/local-backup/qr"

    /** Second mandatory local-backup step immediately after the QR image is saved. */
    const val LOCAL_ACCOUNT_BACKUP_EXPORT = "accounts/{id}/local-backup/export"

    /** Master account and its per-source backup coverage. */
    const val MASTER_ACCOUNT = "master-account"

    fun accountDetail(id: Long): String = "accounts/$id"
    fun localAccountBackupQr(id: Long): String = "accounts/$id/local-backup/qr"
    fun localAccountBackupExport(id: Long): String = "accounts/$id/local-backup/export"
}
