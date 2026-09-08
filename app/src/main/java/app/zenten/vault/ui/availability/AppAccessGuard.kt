package app.zenten.vault.ui.availability

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

enum class AppAccessBlocker {
    INTERNET_UNAVAILABLE,
    DEVICE_TIME_INCORRECT,
}

data class AppAccessState(
    val blockers: Set<AppAccessBlocker>,
    val isCheckingTime: Boolean = false,
) {
    val canUseApp: Boolean get() = blockers.isEmpty() && !isCheckingTime
}

/** Keeps the clock-skew decision testable and consistent with the React Native Vault. */
internal object AppAccessPolicy {
    const val MAX_CLOCK_SKEW_MILLIS = 2 * 60 * 1_000L

    fun evaluate(hasValidatedInternet: Boolean, clockSkewMillis: Long?): AppAccessState =
        AppAccessState(
            buildSet {
                if (!hasValidatedInternet) add(AppAccessBlocker.INTERNET_UNAVAILABLE)
                if (clockSkewMillis != null && kotlin.math.abs(clockSkewMillis) > MAX_CLOCK_SKEW_MILLIS) {
                    add(AppAccessBlocker.DEVICE_TIME_INCORRECT)
                }
            },
        )
}

/**
 * Reads HTTP Date headers from independent HTTPS origins, just as the React Native Vault does.
 * The midpoint of the request limits the bias introduced by round-trip latency.
 */
internal class ServerTimeClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun fetchServerTimeMillis(): Long = withContext(Dispatchers.IO) {
        var lastFailure: Exception? = null
        for (url in TIME_SOURCES) {
            try {
                val startedAt = System.currentTimeMillis()
                val request = Request.Builder().url(url).head().cacheControl(CacheControl.FORCE_NETWORK).build()
                client.newCall(request).execute().use { response ->
                    val completedAt = System.currentTimeMillis()
                    val dateMillis = response.headers["Date"]?.let(::parseHttpDate)
                        ?: throw IllegalStateException("No Date header from $url")
                    return@withContext dateMillis + (completedAt - startedAt) / 2
                }
            } catch (failure: Exception) {
                lastFailure = failure
            }
        }
        throw IllegalStateException("Could not fetch server time", lastFailure)
    }

    private fun parseHttpDate(value: String): Long = try {
        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    } catch (failure: Exception) {
        throw IllegalArgumentException("Invalid HTTP Date header", failure)
    }

    private companion object {
        val TIME_SOURCES = listOf(
            "https://www.google.com/generate_204",
            "https://cloudflare.com",
        )
    }
}

/**
 * Blocks navigation for no validated internet or a measured device-clock error. A time-source
 * failure deliberately does not lock the app: it cannot establish that the local clock is wrong.
 */
class AppAccessGuard(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val timeClient = ServerTimeClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val generation = AtomicLong(0)
    private val _state = MutableStateFlow(AppAccessPolicy.evaluate(hasValidatedInternet(), null))
    val state: StateFlow<AppAccessState> = _state

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onLost(network: Network) = refresh()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refresh()
    }

    init {
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        refresh()
    }

    /** Revalidates time on launch, network changes, resume, and the blocking screen's retry. */
    fun refresh() {
        val requestGeneration = generation.incrementAndGet()
        if (!hasValidatedInternet()) {
            _state.value = AppAccessPolicy.evaluate(hasValidatedInternet = false, clockSkewMillis = null)
            return
        }
        _state.value = AppAccessState(emptySet(), isCheckingTime = true)
        scope.launch {
            val skewMillis = runCatching { System.currentTimeMillis() - timeClient.fetchServerTimeMillis() }.getOrNull()
            // Ignore an older result when connectivity changed or the user requested another check.
            if (generation.get() == requestGeneration && hasValidatedInternet()) {
                _state.value = AppAccessPolicy.evaluate(hasValidatedInternet = true, clockSkewMillis = skewMillis)
            }
        }
    }

    override fun close() {
        connectivityManager.unregisterNetworkCallback(networkCallback)
        scope.cancel()
    }

    private fun hasValidatedInternet(): Boolean = connectivityManager.activeNetwork
        ?.let(connectivityManager::getNetworkCapabilities)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
}
