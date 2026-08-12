package app.zenten.vault.ui

/**
 * Navigation routes for the PoC.
 *
 * Mirrors ui-ux-flows.md §1 (Navigation Structure), trimmed to the PoC scope
 * (no backup-status / settings screens for now).
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

    /** Master account and its per-source backup coverage. */
    const val MASTER_ACCOUNT = "master-account"

    fun accountDetail(id: Long): String = "accounts/$id"
}
