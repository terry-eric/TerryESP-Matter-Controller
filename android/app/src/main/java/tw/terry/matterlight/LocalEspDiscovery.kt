package tw.terry.matterlight

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

data class LocalEspCandidate(
    val name: String,
    val host: String,
    val port: Int,
    val deviceId: String? = null,
)

class LocalEspDiscovery(context: Context, private val onFound: (LocalEspCandidate) -> Unit) {
    private val manager = context.getSystemService(NsdManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private val multicastLock = wifiManager.createMulticastLock("terryesp-mdns").apply {
        setReferenceCounted(false)
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val discovered = mutableMapOf<String, String?>()
    @Volatile private var started = false
    private var udpThread: Thread? = null
    private val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (serviceInfo.serviceType.trimEnd('.') != "_terryesp._tcp") return
            manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val addresses = if (android.os.Build.VERSION.SDK_INT >= 34) info.hostAddresses else emptyList()
                    val address = addresses.firstOrNull { it is Inet4Address }
                        ?: addresses.firstOrNull()
                        ?: info.host
                        ?: return
                    val host = address.hostAddress ?: return
                    val id = host + ":" + info.port
                    emitIfNewOrMoreSpecific(
                        LocalEspCandidate(info.serviceName, host, info.port, inferDeviceId(info.serviceName))
                    )
                }
            })
        }
    }

    fun start() {
        if (!started) {
            started = true
            runCatching { multicastLock.acquire() }
            runCatching { manager.discoverServices("_terryesp._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }
            startUdpDiscovery()
        }
    }

    fun stop() {
        if (started) {
            started = false
            runCatching { manager.stopServiceDiscovery(listener) }
            runCatching { if (multicastLock.isHeld) multicastLock.release() }
            udpThread?.interrupt()
            udpThread = null
        }
    }

    private fun startUdpDiscovery() {
        udpThread = Thread({
            val query = "TERRYESP_DISCOVER".toByteArray(Charsets.UTF_8)
            while (started) {
                try {
                    DatagramSocket().use { socket ->
                        socket.broadcast = true
                        socket.soTimeout = 500
                        val destinations = mutableSetOf(InetAddress.getByName("255.255.255.255"))
                        @Suppress("DEPRECATION")
                        wifiManager.dhcpInfo?.let { dhcp ->
                            val broadcast = (dhcp.ipAddress and dhcp.netmask) or dhcp.netmask.inv()
                            val bytes = ByteArray(4) { index -> ((broadcast shr (index * 8)) and 0xff).toByte() }
                            destinations += InetAddress.getByAddress(bytes)
                        }
                        destinations.forEach { destination ->
                            socket.send(DatagramPacket(query, query.size, destination, 4210))
                        }
                        val deadline = System.currentTimeMillis() + 1800
                        while (started && System.currentTimeMillis() < deadline) {
                            val buffer = ByteArray(256)
                            val packet = DatagramPacket(buffer, buffer.size)
                            try {
                                socket.receive(packet)
                            } catch (_: SocketTimeoutException) {
                                continue
                            }
                            val fields = String(packet.data, 0, packet.length, Charsets.UTF_8).split('|')
                            if (fields.size < 3 || fields[0] != "TERRYESP") continue
                            val port = fields[2].toIntOrNull() ?: continue
                            val candidate = LocalEspCandidate(
                                fields[1], packet.address.hostAddress ?: continue, port,
                                fields.getOrNull(3)?.takeIf(String::isNotBlank),
                            )
                            emitIfNewOrMoreSpecific(candidate)
                        }
                    }
                    Thread.sleep(1200)
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (_: Throwable) {
                    try { Thread.sleep(1200) } catch (_: InterruptedException) { return@Thread }
                }
            }
        }, "terryesp-discovery").also { it.start() }
    }

    private fun inferDeviceId(serviceName: String): String? {
        val normalized = serviceName.lowercase()
        return normalized.takeIf { it.matches(Regex("terryesp-[0-9a-f]{6}")) }
    }

    private fun emitIfNewOrMoreSpecific(candidate: LocalEspCandidate) {
        val endpoint = candidate.host + ":" + candidate.port
        val shouldEmit = synchronized(discovered) {
            val previousId = discovered[endpoint]
            val isNew = !discovered.containsKey(endpoint)
            val gainedDeviceId = previousId == null && candidate.deviceId != null
            if (isNew || gainedDeviceId) discovered[endpoint] = candidate.deviceId
            isNew || gainedDeviceId
        }
        if (shouldEmit) mainHandler.post { onFound(candidate) }
    }
}
