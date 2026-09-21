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

    /**
     * Nested navigation graph that holds every MasterKey generation screen.
     *
     * Exists so the wizard's screens can share one ViewModel scoped to the
     * graph: the chosen passphrase must survive navigation between steps, but
     * must be discarded as soon as the whole wizard leaves the back stack.
     */
    const val CREATE_MASTER_KEY_GRAPH = "get-started/create-master-key"

    /**
     * First screen of the in-app MasterKey generation flow.
     *
     * This is a multi-screen wizard; more routes will be added under the
     * `get-started/create-master-key/` prefix as each subsequent screen is
     * implemented.
     */
    const val CREATE_MASTER_KEY_INTRO = "get-started/create-master-key/intro"

    /** Second screen: choose the passphrase that will protect the new MasterKey. */
    const val CREATE_MASTER_KEY_PASSPHRASE = "get-started/create-master-key/passphrase"

    /** Third screen: re-enter the chosen passphrase to catch typos. */
    const val CREATE_MASTER_KEY_CONFIRM_PASSPHRASE = "get-started/create-master-key/confirm-passphrase"

    /** Fourth screen: explain and export the first Vault Backup. */
    const val CREATE_MASTER_KEY_BACKUP_INTRO = "get-started/create-master-key/backup-intro"

    /** Fifth screen: paste the exported MasterKey to confirm the backup is usable. */
    const val CREATE_MASTER_KEY_CONFIRM_BACKUP = "get-started/create-master-key/confirm-backup"

    /** Sixth screen: back up the Brave mnemonic as grouped BIP-39 word positions. */
    const val CREATE_MASTER_KEY_SECRET_COMBINATIONS = "get-started/create-master-key/secret-combinations"

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
