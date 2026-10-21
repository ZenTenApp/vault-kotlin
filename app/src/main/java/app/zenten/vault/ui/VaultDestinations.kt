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

    /** Recovery graph keeps Secret Combinations in memory until passphrase submission. */
    const val RECOVER_VAULT_KEY_GRAPH = "get-started/recover-vault-key"
    const val RECOVER_VAULT_KEY_COMBINATIONS = "get-started/recover-vault-key/combinations"
    const val RECOVER_VAULT_KEY_PASSPHRASE = "get-started/recover-vault-key/passphrase"

    /**
     * Nested navigation graph that holds every VaultKey generation screen.
     *
     * Exists so the wizard's screens can share one ViewModel scoped to the
     * graph: the chosen passphrase must survive navigation between steps, but
     * must be discarded as soon as the whole wizard leaves the back stack.
     */
    const val CREATE_VAULT_KEY_GRAPH = "get-started/create-vault-key"

    /**
     * First screen of the in-app VaultKey generation flow.
     *
     * This is a multi-screen wizard; more routes will be added under the
     * the Vault Key route prefix as each subsequent screen is
     * implemented.
     */
    const val CREATE_VAULT_KEY_INTRO = "get-started/create-vault-key/intro"

    /** Second screen: choose the passphrase that will protect the new VaultKey. */
    const val CREATE_VAULT_KEY_PASSPHRASE = "get-started/create-vault-key/passphrase"

    /** Third screen: re-enter the chosen passphrase to catch typos. */
    const val CREATE_VAULT_KEY_CONFIRM_PASSPHRASE = "get-started/create-vault-key/confirm-passphrase"

    /** Irreversible creation and per-source synchronization progress. */
    const val CREATE_VAULT_KEY_CREATING = "get-started/create-vault-key/creating"

    /** Fourth screen: explain and export the first Vault Backup. */
    const val CREATE_VAULT_KEY_BACKUP_INTRO = "get-started/create-vault-key/backup-intro"

    /** Fifth screen: paste the exported VaultKey to confirm the backup is usable. */
    const val CREATE_VAULT_KEY_CONFIRM_BACKUP = "get-started/create-vault-key/confirm-backup"

    /** Sixth screen: back up the Vault Key backup recovery mnemonic as grouped BIP-39 word positions. */
    const val CREATE_VAULT_KEY_SECRET_COMBINATIONS = "get-started/create-vault-key/secret-combinations"

    /** Seventh screen: sequentially re-enter every grouped word position. */
    const val CREATE_VAULT_KEY_CONFIRM_SECRET_COMBINATIONS =
        "get-started/create-vault-key/confirm-secret-combinations"

    /** Final screen: celebrate successful VaultKey creation and backup verification. */
    const val CREATE_VAULT_KEY_CONGRATULATIONS =
        "get-started/create-vault-key/congratulations"

    /** Accounts list (main screen after setup). */
    const val ACCOUNTS = "accounts"

    /** Account detail. Arg: {id} = createdAt microsecond timestamp. */
    const val ACCOUNT_DETAIL = "accounts/{id}"

    /** Create new associated account. */
    const val NEW_ACCOUNT = "new-accounts"

    /** Mandatory local-backup step immediately after account creation. */
    const val LOCAL_ACCOUNT_BACKUP_EXPORT = "accounts/{id}/local-backup/export"

    /** VaultKey account and its per-source backup coverage. */
    const val VAULT_KEY = "vaultKey-account"

    /**
     * Nested graph for the Settings-initiated **Secret Combinations** review flow.
     *
     * Like the create wizard, this is a graph so the combination values and the verification
     * cursor can be shared by its screens without putting secret material in route arguments.
     * Once the passphrase gate is passed the flow is irreversible and a persisted step reopens
     * the unfinished destination after a process restart.
     */
    const val VIEW_SECRET_COMBINATIONS_GRAPH = "secret-combinations"

    /** Passphrase gate shown before any combination is displayed. Abortable until it passes. */
    const val VIEW_SECRET_COMBINATIONS_VERIFY = "secret-combinations/verify"

    /** Displays the 24 BIP-39 positions for transcription; Back is disabled. */
    const val VIEW_SECRET_COMBINATIONS_SHOW = "secret-combinations/show"

    /** Sequentially re-enters every position; Back is disabled. */
    const val VIEW_SECRET_COMBINATIONS_CONFIRM = "secret-combinations/confirm"

    /** Brief success confirmation before returning to Settings. */
    const val VIEW_SECRET_COMBINATIONS_COMPLETE = "secret-combinations/complete"

    /** App settings and vault management entry points. */
    const val SETTINGS = "settings"

    fun accountDetail(id: Long): String = "accounts/$id"
    fun localAccountBackupExport(id: Long): String = "accounts/$id/local-backup/export"
}
