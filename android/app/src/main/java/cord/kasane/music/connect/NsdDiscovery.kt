package cord.kasane.music.connect

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build

private const val SERVICE_TYPE = "_kasane._tcp."

/** mDNS register + browse via Android NSD. Resolves are serialized. */
class NsdDiscovery(
    context: Context,
    private val listener: DiscoveredListener,
) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var resolving = false
    private val pending = ArrayDeque<NsdServiceInfo>()
    var lost: ((id: String?, host: String?, port: Int) -> Unit)? = null
    private val resolvedByName = java.util.concurrent.ConcurrentHashMap<String, Triple<String, String, Int>>()

    /** Advertise our Connect server. */
    fun register(name: String, port: Int, txt: Map<String, String>) {
        stopRegister()
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            this.port = port
            txt.forEach { (k, v) -> setAttribute(k, v) }
        }
        val reg = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(service: NsdServiceInfo?, error: Int) {}
            override fun onUnregistrationFailed(service: NsdServiceInfo?, error: Int) {}
            override fun onServiceRegistered(service: NsdServiceInfo?) {}
            override fun onServiceUnregistered(service: NsdServiceInfo?) {}
        }
        registration = reg
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg)
    }

    fun stopRegister() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
    }

    fun discover() {
        stopDiscovery()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String?) {}
            override fun onDiscoveryStopped(type: String?) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                enqueueResolve(service)
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                // Lost events carry only the service name (no TXT, no address): map it back to what it resolved to,
                // otherwise the record stays listed as available for ever.
                val known = resolvedByName.remove(service.serviceName)
                lost?.invoke(known?.first ?: service.attributes["id"]?.decode(), known?.second ?: service.host?.hostAddress,
                    known?.third ?: service.port)
            }
            override fun onStartDiscoveryFailed(type: String?, error: Int) {}
            override fun onStopDiscoveryFailed(type: String?, error: Int) {}
        }
        discovery = listener
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stopDiscovery() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
        pending.clear()
        resolving = false
    }

    private fun enqueueResolve(service: NsdServiceInfo) {
        synchronized(pending) {
            pending.addLast(service)
            if (resolving) return
            resolving = true
        }
        drainResolves()
    }

    private fun drainResolves() {
        val next = synchronized(pending) {
            val service = pending.removeFirstOrNull()
            if (service == null) resolving = false
            service
        } ?: return
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo?, error: Int) {
                drainResolves()
            }
            override fun onServiceResolved(service: NsdServiceInfo) {
                val txt = service.attributes
                val host = service.host?.hostAddress ?: return drainResolves()
                txt["id"]?.decode()?.let { resolvedByName[service.serviceName] = Triple(it, host, service.port) }
                listener.onService(
                    txt["id"]?.decode(),
                    // The display name travels in TXT; the instance name carries an id suffix to stay unique.
                    txt["name"]?.decode()?.takeIf { it.isNotBlank() } ?: service.serviceName,
                    txt["platform"]?.decode(),
                    host,
                    service.port,
                )
                drainResolves()
            }
        })
    }
}

private fun ByteArray.decode(): String = String(this, Charsets.UTF_8)
