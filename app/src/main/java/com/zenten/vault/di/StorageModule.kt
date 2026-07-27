package com.zenten.vault.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.zenten.vault.data.kv.AppStorageService
import com.zenten.vault.data.secure.SecureStorageService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "vault_app")

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {
    @Provides @Singleton
    fun secureStorage(@ApplicationContext context: Context) = SecureStorageService(context)

    @Provides @Singleton
    fun appDataStore(@ApplicationContext context: Context): DataStore<Preferences> = context.appDataStore

    @Provides @Singleton
    fun appStorage(dataStore: DataStore<Preferences>) = AppStorageService(dataStore)
}
