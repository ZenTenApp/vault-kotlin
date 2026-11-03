package app.zenten.vault.ui.auth

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeviceLockPolicyTest {
    @Test
    fun `available device authentication starts locked`() {
        assertEquals(AppUnlockState.Locked, DeviceLockPolicy.stateFor(canAuthenticate = true))
    }

    @Test
    fun `missing device authentication fails closed`() {
        assertEquals(AppUnlockState.DeviceLockRequired, DeviceLockPolicy.stateFor(canAuthenticate = false))
    }

    @Test
    fun `unlocked session remains open inside thirty second grace period`() {
        assertFalse(DeviceLockPolicy.requiresAuthentication(true, backgroundAt = 1_000L, now = 30_999L))
    }

    @Test
    fun `session relocks at grace boundary`() {
        assertTrue(DeviceLockPolicy.requiresAuthentication(true, backgroundAt = 1_000L, now = 31_000L))
    }

    @Test
    fun `cold or previously locked session always authenticates`() {
        assertTrue(DeviceLockPolicy.requiresAuthentication(false, backgroundAt = 1_000L, now = 1_001L))
        assertTrue(DeviceLockPolicy.requiresAuthentication(true, backgroundAt = null, now = 1_001L))
    }

    @Test
    fun `cancellation stays on the retryable locked screen`() {
        assertEquals(
            AppUnlockState.Locked,
            AuthErrorPolicy.stateFor(BiometricPrompt.ERROR_USER_CANCELED, canAuthenticate = true),
        )
        assertEquals(
            AppUnlockState.Locked,
            AuthErrorPolicy.stateFor(BiometricPrompt.ERROR_NEGATIVE_BUTTON, canAuthenticate = true),
        )
    }

    @Test
    fun `lockout remains retryable while capability is intact`() {
        assertEquals(
            AppUnlockState.Locked,
            AuthErrorPolicy.stateFor(BiometricPrompt.ERROR_LOCKOUT, canAuthenticate = true),
        )
        assertEquals(
            AppUnlockState.Locked,
            AuthErrorPolicy.stateFor(BiometricPrompt.ERROR_LOCKOUT_PERMANENT, canAuthenticate = true),
        )
    }

    @Test
    fun `capability errors route to enrollment`() {
        listOf(
            BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
            BiometricPrompt.ERROR_NO_BIOMETRICS,
            BiometricPrompt.ERROR_HW_NOT_PRESENT,
        ).forEach { errorCode ->
            assertEquals(
                AppUnlockState.DeviceLockRequired,
                AuthErrorPolicy.stateFor(errorCode, canAuthenticate = true),
                "errorCode=$errorCode",
            )
        }
    }

    @Test
    fun `any error falls back to enrollment when capability is gone`() {
        assertEquals(
            AppUnlockState.DeviceLockRequired,
            AuthErrorPolicy.stateFor(BiometricPrompt.ERROR_CANCELED, canAuthenticate = false),
        )
    }

    @Test
    fun `api 26 and 27 use one combined strong biometric and credential prompt`() {
        assertEquals(
            UnlockPromptKind.Combined,
            DeviceLockPolicy.promptKind(Build.VERSION_CODES.O),
        )
        assertEquals(
            UnlockPromptKind.Combined,
            DeviceLockPolicy.promptKind(Build.VERSION_CODES.O_MR1),
        )
    }

    @Test
    fun `api 28 and 29 use the strong-only prompt with a credential fallback`() {
        assertEquals(
            UnlockPromptKind.StrongBiometricOnly,
            DeviceLockPolicy.promptKind(Build.VERSION_CODES.P),
        )
        assertEquals(
            UnlockPromptKind.StrongBiometricOnly,
            DeviceLockPolicy.promptKind(Build.VERSION_CODES.Q),
        )
    }

    @Test
    fun `api 30 and later use the combined prompt`() {
        assertEquals(
            UnlockPromptKind.Combined,
            DeviceLockPolicy.promptKind(Build.VERSION_CODES.R),
        )
        assertEquals(UnlockPromptKind.Combined, DeviceLockPolicy.promptKind(36))
    }

    @Test
    fun `credential fallback covers prompt errors that cannot complete`() {
        listOf(
            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
            BiometricPrompt.ERROR_NO_BIOMETRICS,
            BiometricPrompt.ERROR_HW_NOT_PRESENT,
        ).forEach { errorCode ->
            assertTrue(
                DeviceLockPolicy.isCredentialFallbackError(errorCode),
                "errorCode=$errorCode",
            )
        }
        assertFalse(
            DeviceLockPolicy.isCredentialFallbackError(BiometricPrompt.ERROR_USER_CANCELED),
        )
        assertFalse(DeviceLockPolicy.isCredentialFallbackError(BiometricPrompt.ERROR_LOCKOUT))
    }

    @Test
    fun `strong biometric availability accepts success and unknown only`() {
        assertFalse(
            DeviceLockPolicy.strongBiometricUnavailable(BiometricManager.BIOMETRIC_SUCCESS),
        )
        assertFalse(
            DeviceLockPolicy.strongBiometricUnavailable(BiometricManager.BIOMETRIC_STATUS_UNKNOWN),
        )
        assertTrue(
            DeviceLockPolicy.strongBiometricUnavailable(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED),
        )
        assertTrue(
            DeviceLockPolicy.strongBiometricUnavailable(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE),
        )
    }
}
