package app.zenten.vault.keyhive

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import app.zenten.vault.data.secure.SecureStorageService
import app.zenten.vault.di.KeyHiveProviderEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Signature-protected, read-only access to associated accounts. */
class AssociatedAccountsProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "app.zenten.vault.keys"
        const val PERMISSION = "app.zenten.permission.VAULT_ACCESS"
        const val FORMAT = "json_v1"
        const val FORMAT_COLUMN = "format"
        const val DATA_COLUMN = "data"
        private const val ACCOUNTS = 1
        private const val ACCOUNT = 2
        private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "accounts", ACCOUNTS)
            addURI(AUTHORITY, "accounts/*", ACCOUNT)
        }
    }

    private lateinit var storage: SecureStorageService

    override fun onCreate(): Boolean = try {
        val application = context?.applicationContext ?: return false
        storage = EntryPointAccessors.fromApplication(application, KeyHiveProviderEntryPoint::class.java).secureStorage()
        true
    } catch (_: Exception) { false }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? {
        if (selection != null || !selectionArgs.isNullOrEmpty() || sortOrder != null) return null
        return try {
            when (matcher.match(uri)) {
                ACCOUNTS -> jsonCursor(Json.encodeToString(storage.getAccounts().map { it.createdAt.toString() }))
                ACCOUNT -> {
                    val id = uri.lastPathSegment?.toLongOrNull() ?: return null
                    if (id <= 0 || id.toString() != uri.lastPathSegment) return null
                    storage.getAccountById(id)?.let { jsonCursor(Json.encodeToString(it.toSharedAssociatedAccount())) }
                        ?: emptyCursor()
                }
                else -> null
            }
        } catch (_: Exception) { null }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun getType(uri: Uri): String? = when (matcher.match(uri)) {
        ACCOUNTS -> "vnd.android.cursor.item/vnd.zenten.associated-accounts"
        ACCOUNT -> "vnd.android.cursor.item/vnd.zenten.associated-account"
        else -> null
    }

    private fun jsonCursor(json: String): Cursor = keyHiveJsonCursor(json)

    private fun emptyCursor(): Cursor = MatrixCursor(arrayOf(FORMAT_COLUMN, DATA_COLUMN))
}

internal fun keyHiveJsonCursor(json: String): Cursor = MatrixCursor(
    arrayOf(AssociatedAccountsProvider.FORMAT_COLUMN, AssociatedAccountsProvider.DATA_COLUMN),
).apply {
    addRow(keyHiveJsonRow(json))
}

internal fun keyHiveJsonRow(json: String) = arrayOf(AssociatedAccountsProvider.FORMAT, json)
