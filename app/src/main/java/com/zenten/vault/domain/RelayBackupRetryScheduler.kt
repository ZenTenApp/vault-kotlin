package com.zenten.vault.domain

/** Platform boundary so domain code can request durable retry without depending on WorkManager. */
interface RelayBackupRetryScheduler {
    fun schedule()
}
