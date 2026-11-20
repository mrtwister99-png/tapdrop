package cz.tapdrop.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import cz.tapdrop.app.data.Prefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address

object Discovery {
    private const val TAG = "TapDrop.Discovery"

    fun wifiConnected(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /**
     * Najde notebook: nejdřív mDNS (_tapdrop._tcp), potom uložená IP z párování.
     * Vrací ověřené API (odpověděl /discover se správným tokenem a ID).
     */
    suspend fun connect(ctx: Context, prefs: Prefs): Api {
        if (!prefs.paired) throw TapError(ErrorKind.NOT_PAIRED)
        if (!wifiConnected(ctx)) throw TapError(ErrorKind.WIFI_OFF)

        val candidates = mutableListOf<Endpoint>()
        mdns(ctx, prefs.notebookId)?.let { candidates.add(it) }
        val saved = Endpoint(prefs.host, prefs.port)
        if (candidates.none { it == saved }) candidates.add(saved)

        var lastError: Exception? = null
        for (ep in candidates) {
            try {
                val api = Api(ep, prefs.token)
                val info = api.discover()
                if (info.id != prefs.notebookId) {
                    lastError = TapError(ErrorKind.NOT_FOUND, "Na ${ep.host} odpovídá jiný notebook (${info.id})")
                    continue
                }
                // mDNS našlo novou adresu → zapamatuj si ji pro příště
                if (ep != saved) {
                    prefs.host = ep.host
                    prefs.port = ep.port
                }
                return api
            } catch (e: TapError) {
                if (e.kind == ErrorKind.BAD_TOKEN) throw e
                lastError = e
            } catch (e: Exception) {
                Log.w(TAG, "Spojení s ${ep.host}:${ep.port} selhalo: ${e.message}")
                lastError = e
            }
        }
        throw (lastError as? TapError) ?: TapError(ErrorKind.NOT_FOUND)
    }

    private suspend fun mdns(ctx: Context, notebookId: String): Endpoint? = withTimeoutOrNull(2500) {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        val result = CompletableDeferred<Endpoint?>()

        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                result.complete(null)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                try {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(si: NsdServiceInfo) {
                            val id = si.attributes["id"]?.let { String(it) }
                            @Suppress("DEPRECATION")
                            val h = si.host
                            if (id == notebookId && h is Inet4Address) {
                                result.complete(Endpoint(h.hostAddress ?: return, si.port))
                            }
                        }
                    })
                } catch (e: Exception) {
                    Log.w(TAG, "resolve: ${e.message}")
                }
            }
        }
        try {
            nsd.discoverServices("_tapdrop._tcp", NsdManager.PROTOCOL_DNS_SD, discovery)
        } catch (e: Exception) {
            return@withTimeoutOrNull null
        }
        try {
            result.await()
        } finally {
            runCatching { nsd.stopServiceDiscovery(discovery) }
        }
    }
}
