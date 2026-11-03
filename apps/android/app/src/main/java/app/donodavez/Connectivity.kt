package app.donodavez

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext

/** Conectividade atual (true = há rede com acesso à internet). */
@Composable
fun rememberOnline(): State<Boolean> {
    val context = LocalContext.current
    return produceState(initialValue = isOnline(context)) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { value = isOnline(context) }
            override fun onLost(network: Network) { value = isOnline(context) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        }
        cm.registerDefaultNetworkCallback(callback)
        awaitDispose { cm.unregisterNetworkCallback(callback) }
    }
}

private fun isOnline(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
