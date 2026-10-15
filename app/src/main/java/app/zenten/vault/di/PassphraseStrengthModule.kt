package app.zenten.vault.di

import app.zenten.vault.ui.onboarding.createvaultkey.PassphraseStrengthChecker
import app.zenten.vault.ui.onboarding.createvaultkey.ZxcvbnPassphraseStrengthChecker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the passphrase guessability estimator used by the **Create VaultKey** wizard.
 *
 * Singleton scope is required rather than incidental: zxcvbn parses roughly 800 KB of
 * bundled dictionary data on first use, so one warm instance keeps that cost off every
 * passphrase keystroke.
 */
@Module
@InstallIn(SingletonComponent::class)
object PassphraseStrengthModule {
    @Provides
    @Singleton
    fun passphraseStrengthChecker(
        implementation: ZxcvbnPassphraseStrengthChecker,
    ): PassphraseStrengthChecker = implementation
}
