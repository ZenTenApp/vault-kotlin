package app.zenten.vault.di

import app.zenten.vault.network.nostr.NostrRelayGateway
import app.zenten.vault.network.nostr.NostrRelayService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    /**
     * The relay set is deliberately fixed and shared by all builds — no per-build or user
     * choice. Relay configuration is not implemented, so this always supplies the defaults.
     */
    @Provides @Singleton
    fun nostrRelayService(): NostrRelayGateway = NostrRelayService()
}
