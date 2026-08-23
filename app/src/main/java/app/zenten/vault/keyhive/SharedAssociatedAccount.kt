package app.zenten.vault.keyhive

import app.zenten.vault.data.model.Account
import kotlinx.serialization.Serializable

/** The only sensitive record shape available to trusted sibling applications. */
@Serializable
data class SharedAssociatedAccount(
    val createdAt: String,
    val zentenKey: String,
)

fun Account.toSharedAssociatedAccount() = SharedAssociatedAccount(
    createdAt = createdAt.toString(),
    zentenKey = zenTenKey,
)
