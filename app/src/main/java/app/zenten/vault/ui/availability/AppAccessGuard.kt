package app.zenten.vault.ui.availability

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Reasons that prevent use of a vault whose remote backups depend on trusted connectivity and time. */
enum class AppAccessBlocker {
    INTERNET_UNAVAILABLE,
    AUTOMATIC_TIME_DISABLED,
}

data class AppAccessState(val blockers: Set<AppAccessBlocker>) {
    val canUseApp: Boolean get() = blockers.isEmpty()
}

/** Keeps the policy separate from Android services so its security-sensitive defaults are testable. */
internal object AppAccessPolicy {
    fun evaluate(hasValidatedInternet: Boolean, automaticTimeEnabled: Boolean): AppAccessState =
        AppAccessState(
            buildSet {
                if (!hasValidatedInternet) add(AppAccessBlocker.INTERNET_UNAVAILABLE)
                if (!automaticTimeEnabled) add(AppAccessBlocker.AUTOMATIC_TIME_DISABLED)
            },
        )
}

/**
 * Observes prerequisites while the activity is visible. VALIDATED is required rather than merely
 * a connected Wi-Fi/mobile network, because a captive portal cannot safely reach backup services.
 */
class AppAccessGuard(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val _state = MutableStateFlow(currentState())
    val state: StateFlow<AppAccessState> = _state

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onLost(network: Network) = refresh()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refresh()
    }
    private val timeChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    init {
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        appContext.registerReceiver(
            timeChangedReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
    }

    /** Call when returning from system settings, where the automatic-time preference can change. */
    fun refresh() {
        _state.value = currentState()
    }

    override fun close() {
        connectivityManager.unregisterNetworkCallback(networkCallback)
        appContext.unregisterReceiver(timeChangedReceiver)
    }

    private fun currentState(): AppAccessState = AppAccessPolicy.evaluate(
        hasValidatedInternet = connectivityManager.activeNetwork
            ?.let(connectivityManager::getNetworkCapabilities)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        // A manual clock has no trustworthy provenance for timestamped relay authentication.
        automaticTimeEnabled = runCatching {
            Settings.Global.getInt(appContext.contentResolver, Settings.Global.AUTO_TIME, 0) == 1
        }.getOrDefault(false),
    )
}
