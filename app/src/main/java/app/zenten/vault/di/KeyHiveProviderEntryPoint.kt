package app.zenten.vault.di

import app.zenten.vault.data.secure.SecureStorageService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface KeyHiveProviderEntryPoint {
    fun secureStorage(): SecureStorageService
}
