package com.keymo.keymocast

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import kotlin.coroutines.resume

class ConnectionManager(context: Context) {
    private val preferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    companion object {
        private const val DEFAULT_PORT = 8080
        private const val SOCKET_TIMEOUT = 300 // ms
        private const val NETWORK_TIMEOUT = 5000L // 5 seconds
    }

    fun getServerPort(): Int {
        return preferences.getString("port_number", DEFAULT_PORT.toString())?.toIntOrNull()
            ?: DEFAULT_PORT
    }

    private suspend fun getCurrentWifiIpAddress(): String? = withTimeoutOrNull(NETWORK_TIMEOUT) {
        suspendCancellableCoroutine { continuation ->
            val networkRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            val networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // Wait for LinkProperties
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    val addresses = linkProperties.linkAddresses
                    val ipv4Address = addresses.firstOrNull {
                        it.address is Inet4Address && !it.address.isLoopbackAddress
                    }?.address?.hostAddress

                    if (ipv4Address != null) {
                        connectivityManager.unregisterNetworkCallback(this)
                        if (!continuation.isCompleted) {
                            continuation.resume(ipv4Address)
                        }
                    }
                }

                override fun onUnavailable() {
                    connectivityManager.unregisterNetworkCallback(this)
                    if (!continuation.isCompleted) {
                        continuation.resume(null)
                    }
                }
            }

            continuation.invokeOnCancellation {
                connectivityManager.unregisterNetworkCallback(networkCallback)
            }

            connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        }
    }

    suspend fun findServer(): String? = withContext(Dispatchers.IO) {
        val port = getServerPort()

        // Try the saved address
        getSavedServerIp()?.let { savedIp ->
            if (isServerReachable(savedIp, port)) {
                return@withContext savedIp
            }
        }

        val localAddresses = mutableListOf<String>()

        // Get current WiFi IP
        getCurrentWifiIpAddress()?.let { wifiIp ->
            localAddresses.add(wifiIp)
        }

        // Get all the addresses if it doesn't work
        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { networkInterface ->
            if (networkInterface.isUp && !networkInterface.isLoopback) {
                networkInterface.inetAddresses.asSequence()
                    .filterIsInstance<Inet4Address>()
                    .filterNot { it.isLoopbackAddress }
                    .forEach { address ->
                        val ip = address.hostAddress
                        if (ip != null && !localAddresses.contains(ip)) {
                            localAddresses.add(ip)
                        }
                    }
            }
        }

        // Try each network range
        for (localAddress in localAddresses) {
            val baseIp = localAddress.substring(0, localAddress.lastIndexOf(".") + 1)

            for (i in 1..254) {
                val testIp = baseIp + i
                if (testIp != localAddress) {
                    if (isServerReachable(testIp, port)) {
                        saveServerIp(testIp)
                        return@withContext testIp
                    }
                }
            }
        }
        null
    }

    private fun isServerReachable(ip: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(ip, port),
                    SOCKET_TIMEOUT
                )
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun getSavedServerIp(): String? {
        return preferences.getString("server_ip", null)
    }

    private fun saveServerIp(ip: String) {
        preferences.edit().putString("server_ip", ip).apply()
    }
}