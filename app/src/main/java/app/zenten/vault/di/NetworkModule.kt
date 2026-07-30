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
    /** PoC relay set is deliberately fixed; relay configuration UI is deferred. */
    @Provides @Singleton
    fun nostrRelayService(): NostrRelayGateway = NostrRelayService()
}
