package app.zenten.vault.ui.availability

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
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
import javax.inject.Inject
import javax.inject.Singleton

/** The outcomes shared by the foreground gate and remote backup work. */
sealed interface RemoteOperationReadiness {
    data object READY : RemoteOperationReadiness
    data object NO_VALIDATED_INTERNET : RemoteOperationReadiness
    data object DEVICE_TIME_INCORRECT : RemoteOperationReadiness
    /** A time-source outage cannot establish that the device clock is wrong. */
    data object TIME_UNVERIFIABLE : RemoteOperationReadiness
}

enum class AppAccessBlocker {
    INTERNET_UNAVAILABLE,
    DEVICE_TIME_INCORRECT,
}

data class AppAccessState(
    val blockers: Set<AppAccessBlocker>,
    val isCheckingTime: Boolean = false,
    /** The initial clock check must finish before navigation is first exposed. */
    val hasCompletedCheck: Boolean = false,
) {
    // Later refreshes retain the last known safe state instead of flashing the gate.
    val canUseApp: Boolean get() = hasCompletedCheck && blockers.isEmpty()
}

/** Keeps the mapping from shared prerequisite results to UI state testable. */
internal object AppAccessPolicy {
    fun evaluate(readiness: RemoteOperationReadiness): AppAccessState = when (readiness) {
        RemoteOperationReadiness.READY,
        RemoteOperationReadiness.TIME_UNVERIFIABLE -> AppAccessState(emptySet())
        RemoteOperationReadiness.NO_VALIDATED_INTERNET ->
            AppAccessState(setOf(AppAccessBlocker.INTERNET_UNAVAILABLE))
        RemoteOperationReadiness.DEVICE_TIME_INCORRECT ->
            AppAccessState(setOf(AppAccessBlocker.DEVICE_TIME_INCORRECT))
    }
}

/**
 * Reads HTTP Date headers from independent HTTPS origins, just as the React Native Vault does.
 * The midpoint of the request limits the bias introduced by round-trip latency.
 */
internal class ServerTimeClient {
    private val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()

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
 * Single prerequisite policy for every remote operation. Known bad connectivity or time fails
 * closed; an unavailable time source is allowed because it is not evidence of bad device time.
 */
@Singleton
class RemoteOperationPrecondition @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val timeClient = ServerTimeClient()

    suspend fun check(): RemoteOperationReadiness {
        if (!hasValidatedInternet()) return RemoteOperationReadiness.NO_VALIDATED_INTERNET
        val serverTimeMillis = runCatching { timeClient.fetchServerTimeMillis() }.getOrNull()
            ?: return RemoteOperationReadiness.TIME_UNVERIFIABLE
        val skewMillis = System.currentTimeMillis() - serverTimeMillis
        return if (kotlin.math.abs(skewMillis) > MAX_CLOCK_SKEW_MILLIS) {
            RemoteOperationReadiness.DEVICE_TIME_INCORRECT
        } else {
            RemoteOperationReadiness.READY
        }
    }

    fun hasValidatedInternet(): Boolean = connectivityManager.activeNetwork
        ?.let(connectivityManager::getNetworkCapabilities)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

    private companion object {
        const val MAX_CLOCK_SKEW_MILLIS = 2 * 60 * 1_000L
    }
}

/** Blocks navigation until the same prerequisite policy used by background work passes. */
class AppAccessGuard(context: Context) : AutoCloseable {
    private val precondition = RemoteOperationPrecondition(context.applicationContext)
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val generation = AtomicLong(0)
    private val _state = MutableStateFlow(initialState())
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

    /**
     * Revalidates time on launch, resume, network changes, and retry. Automatic refreshes keep
     * retry controls stable; only a user-initiated retry displays its in-progress state.
     */
    fun refresh(showChecking: Boolean = false) {
        val requestGeneration = generation.incrementAndGet()
        if (!precondition.hasValidatedInternet()) {
            _state.value = AppAccessPolicy.evaluate(RemoteOperationReadiness.NO_VALIDATED_INTERNET)
            return
        }
        // Network callbacks are frequent on some devices, so do not make the button flicker.
        _state.value = _state.value.copy(isCheckingTime = showChecking)
        scope.launch {
            val readiness = precondition.check()
            // Ignore an older result when connectivity changed or the user requested another check.
            if (generation.get() == requestGeneration) {
                _state.value = AppAccessPolicy.evaluate(readiness).copy(hasCompletedCheck = true)
            }
        }
    }

    override fun close() {
        connectivityManager.unregisterNetworkCallback(networkCallback)
        scope.cancel()
    }

    private fun initialState(): AppAccessState = if (precondition.hasValidatedInternet()) {
        AppAccessState(emptySet(), isCheckingTime = true)
    } else {
        AppAccessPolicy.evaluate(RemoteOperationReadiness.NO_VALIDATED_INTERNET).copy(hasCompletedCheck = true)
    }
}
