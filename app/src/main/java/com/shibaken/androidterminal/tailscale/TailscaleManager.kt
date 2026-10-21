package com.shibaken.androidterminal.tailscale

import com.shibaken.androidterminal.R
import com.shibaken.androidterminal.App
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.util.Log
import com.shibaken.androidterminal.BuildConfig
import com.shibaken.androidterminal.data.AppJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.net.Inet6Address
import java.net.NetworkInterface

@Serializable
data class TailnetPeer(
    val hostName: String = "",
    val dnsName: String = "",
    val os: String = "",
    val ips: List<String> = emptyList(),
    val online: Boolean = false,
)

@Serializable
data class TailscaleStatus(
    val running: Boolean = false,
    val backendState: String = "Stopped",
    val authURL: String = "",
    val selfName: String = "",
    val selfDNSName: String = "",
    val tailscaleIPs: List<String> = emptyList(),
    val peers: List<TailnetPeer> = emptyList(),
    val health: List<String> = emptyList(),
    val error: String? = null,
) {
    val isConnected: Boolean get() = backendState == "Running"
    val needsLogin: Boolean get() = backendState == "NeedsLogin" || authURL.isNotEmpty()
}

/**
 * Embedded Tailscale node (tsnet, userspace). The Go side (tsbridge.aar, built by
 * tsbridge/build.sh) is optional and called via reflection so the app builds without it.
 */
class TailscaleManager(private val context: Context) {

    /** Loading the class loads libgojni and runs all Go package inits: do it lazily, off main. */
    private val bridge: Class<*>? by lazy {
        if (!BuildConfig.HAS_TSBRIDGE) return@lazy null
        try {
            Class.forName("tsbridge.Tsbridge")
        } catch (_: Throwable) {
            null
        }
    }

    /** True when tsbridge.aar is bundled into this build (known at compile time). */
    val available: Boolean get() = BuildConfig.HAS_TSBRIDGE

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val callbackRegistered = java.util.concurrent.atomic.AtomicBoolean(false)
    private val prefs = context.getSharedPreferences("tailscale", Context.MODE_PRIVATE)
    private val _status = MutableStateFlow(TailscaleStatus())
    val status: StateFlow<TailscaleStatus> = _status.asStateFlow()
    private var pollJob: Job? = null
    /** Last start() failure; kept across status refreshes, which would otherwise erase it. */
    @Volatile private var startError: String? = null

    val enabled: Boolean get() = prefs.getBoolean("enabled", false)

    init {
        if (available && enabled) scope.launch { runCatching { startInternal(null) } }
    }

    private fun call(name: String, vararg args: Any?): Any? {
        val cls = bridge ?: throw IOException(App.str(R.string.core_ts_not_built_in_build))
        val m = cls.methods.first { it.name == name && it.parameterTypes.size == args.size }
        return try {
            m.invoke(null, *args)
        } catch (e: InvocationTargetException) {
            throw IOException(e.targetException?.message ?: "tsbridge.$name failed", e.targetException)
        }
    }

    /** Start (or resume) the node. Empty [authKey] means interactive login via the URL in [status]. */
    fun start(authKey: String? = null) {
        prefs.edit().putBoolean("enabled", true).apply()
        scope.launch {
            try {
                startInternal(authKey)
            } catch (e: Exception) {
                startError = e.message ?: e.toString()
                _status.value = _status.value.copy(error = startError)
            }
        }
    }

    private fun startInternal(authKey: String?) {
        startError = null
        pushInterfaces()
        registerNetworkCallback()
        val dir = File(context.filesDir, "tailscale").apply { mkdirs() }
        val hostname = "android-terminal-" + Build.MODEL.lowercase().replace(Regex("[^a-z0-9-]"), "-")
        call("start", dir.absolutePath, hostname, authKey ?: "")
        startPolling()
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                refreshStatus()
                delay(if (_status.value.isConnected) 10_000 else 1_500)
            }
        }
    }

    /** Blocking (Go LocalAPI call, up to ~5 s); use from a background thread. */
    fun refreshStatus() {
        if (!available) return
        try {
            val json = call("status") as String
            val s = AppJson.decodeFromString(TailscaleStatus.serializer(), json)
            _status.value = if (s.error == null && startError != null) s.copy(error = startError) else s
        } catch (e: Exception) {
            _status.value = _status.value.copy(error = e.message)
        }
    }

    /** Suspends until the node is Running (or throws with a helpful message). */
    suspend fun awaitRunning(timeoutMs: Long = 30_000) {
        if (!available) throw IOException(App.str(R.string.core_ts_not_built_in))
        if (!_status.value.running) start(null)
        try {
            withTimeout(timeoutMs) {
                while (true) {
                    refreshStatus()
                    startError?.let { throw IOException(App.str(R.string.core_ts_start_failed, it)) }
                    val s = _status.value
                    if (s.isConnected) return@withTimeout
                    if (s.needsLogin) throw IOException(App.str(R.string.core_ts_login_required))
                    delay(500)
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw IOException(App.str(R.string.core_ts_timeout, _status.value.backendState.toString()))
        }
    }

    /** Returns a local port that tunnels to host:port over the tailnet. */
    suspend fun forward(host: String, port: Int): Int = withContext(Dispatchers.IO) {
        (call("forward", "$host:$port") as Number).toInt()
    }

    fun stopForward(port: Int) {
        runCatching { call("stopForward", port) }
    }

    fun logout() {
        scope.launch {
            runCatching { call("logout") }
            refreshStatus()
        }
    }

    fun stop() {
        prefs.edit().putBoolean("enabled", false).apply()
        pollJob?.cancel()
        scope.launch {
            runCatching { call("stop") }
            _status.value = TailscaleStatus()
        }
    }

    /** Blocking (Go call); use from a background thread. */
    fun recentLogs(): String = runCatching { call("recentLogs") as String }.getOrDefault("")

    // --- network interfaces -------------------------------------------------------------
    // Android (API 30+) denies Go's net.Interfaces() to apps; hand the list over from Java.

    private fun registerNetworkCallback() {
        if (!callbackRegistered.compareAndSet(false, true)) return
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = pushInterfaces()
            override fun onLost(network: Network) = pushInterfaces()
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                pushInterfaces()
                val gw = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway != null }?.gateway?.hostAddress ?: ""
                runCatching { call("setDefaultRoute", lp.interfaceName ?: "", gw) }
            }
        })
    }

    private fun pushInterfaces() {
        if (!available) return
        try {
            val sb = StringBuilder()
            for (ni in NetworkInterface.getNetworkInterfaces() ?: return) try {
                var flags = 0
                if (ni.isUp) flags = flags or 1
                if (!ni.isLoopback && !ni.isPointToPoint) flags = flags or 2
                if (ni.isLoopback) flags = flags or 4
                if (ni.isPointToPoint) flags = flags or 8
                if (ni.supportsMulticast()) flags = flags or 16
                val addrs = ni.interfaceAddresses.mapNotNull { ia ->
                    val a = ia.address ?: return@mapNotNull null
                    val host = a.hostAddress?.substringBefore('%') ?: return@mapNotNull null
                    val prefix = ia.networkPrefixLength.toInt().let { if (it < 0) (if (a is Inet6Address) 128 else 32) else it }
                    "$host/$prefix"
                }
                sb.append(ni.name).append('|').append(ni.index).append('|')
                    .append(runCatching { ni.mtu }.getOrDefault(1500)).append('|')
                    .append(flags).append('|').append(addrs.joinToString(",")).append('\n')
            } catch (e: Exception) {
                // One odd interface must not hide all the others from Go.
                Log.w("Tailscale", "skip interface ${ni.name}: ${e.message}")
            }
            call("setInterfaces", sb.toString())
        } catch (e: Exception) {
            Log.w("Tailscale", "pushInterfaces failed", e)
        }
    }
}
