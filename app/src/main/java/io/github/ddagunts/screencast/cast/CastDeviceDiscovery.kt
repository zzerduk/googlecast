package io.github.ddagunts.screencast.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.charset.StandardCharsets

/**
 * Discovers Google Cast devices on `_googlecast._tcp.` via Android's [NsdManager].
 *
 * Refactored in Phase 1 to unlock audio-only speakers (such as the 1st-Gen Google Home
 * with `md=Google Home` and `ca=2052`) by removing the legacy `ca & 0x01` video filter.
 */
class CastDeviceDiscovery(context: Context) {

    private val appContext = context.applicationContext
    private val nsdManager: NsdManager? =
        appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager: WifiManager? =
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var multicastLock: WifiManager.MulticastLock? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val _devices = MutableStateFlow<List<CastDeviceModel>>(emptyList())
    val devices: StateFlow<List<CastDeviceModel>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _discoveryLog = MutableStateFlow<List<String>>(emptyList())
    val discoveryLog: StateFlow<List<String>> = _discoveryLog.asStateFlow()

    fun startDiscovery() {
        val manager = nsdManager ?: run {
            appendLog("NsdManager unavailable on this device")
            return
        }
        if (_isScanning.value) return

        acquireMulticastLock()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                _isScanning.value = true
                appendLog("mDNS scan started for $regType (Video-bit 0x01 filter: DISABLED)")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (!service.serviceType.contains("_googlecast._tcp")) return
                resolveService(service)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                val lostName = service.serviceName
                appendLog("mDNS service lost: $lostName")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                _isScanning.value = false
                appendLog("mDNS scan stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                _isScanning.value = false
                appendLog("mDNS start failed (code=$errorCode)")
                releaseMulticastLock()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                _isScanning.value = false
                releaseMulticastLock()
            }
        }

        discoveryListener = listener
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start mDNS discovery", e)
            _isScanning.value = false
            appendLog("mDNS exception: ${e.message}")
            releaseMulticastLock()
        }
    }

    fun stopDiscovery() {
        val manager = nsdManager ?: return
        val listener = discoveryListener ?: return
        try {
            manager.stopServiceDiscovery(listener)
        } catch (e: Exception) {
            Log.w(TAG, "Stop discovery warning: ${e.message}")
        } finally {
            discoveryListener = null
            _isScanning.value = false
            releaseMulticastLock()
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveService(serviceInfo: NsdServiceInfo) {
        val manager = nsdManager ?: return
        try {
            manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    appendLog("Resolve failed for ${info.serviceName} (err=$errorCode)")
                }

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val model = parseServiceInfo(resolved) ?: return
                    upsertDevice(model)
                    appendLog(
                        "Discovered: ${model.friendlyName} [${model.modelName}] " +
                            "@ ${model.hostAddress}:${model.port} (ca=${model.capabilityMask})"
                    )
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve service ${serviceInfo.serviceName}", e)
        }
    }

    /**
     * Parses TXT records (`fn`, `md`, `ca`, `id`) from [NsdServiceInfo].
     * Explicitly retains headless audio-only Cast devices (`ca=2052`, `md=Google Home`).
     */
    @Suppress("DEPRECATION")
    internal fun parseServiceInfo(info: NsdServiceInfo): CastDeviceModel? {
        val host = info.host?.hostAddress ?: return null
        val port = if (info.port > 0) info.port else CastDeviceModel.DEFAULT_CAST_PORT

        val rawAttributes = mutableMapOf<String, String>()
        try {
            for ((key, bytes) in info.attributes) {
                if (key != null && bytes != null) {
                    rawAttributes[key] = String(bytes, StandardCharsets.UTF_8)
                }
            }
        } catch (_: Exception) {
            // Ignore malformed TXT attribute entries
        }

        val friendlyName = rawAttributes["fn"]?.takeIf { it.isNotBlank() }
            ?: info.serviceName.substringBefore("-")
        val modelName = rawAttributes["md"]?.takeIf { it.isNotBlank() }
            ?: "Google Home"
        val deviceId = rawAttributes["id"]?.takeIf { it.isNotBlank() }
            ?: "${host}:$port"
        val capabilityMask = rawAttributes["ca"]?.toIntOrNull()
            ?: CastDeviceModel.CAPABILITY_AUDIO_OUT

        if (!CastDeviceModel.isEligibleCastReceiver(capabilityMask, modelName)) {
            return null
        }

        return CastDeviceModel(
            id = deviceId,
            friendlyName = friendlyName,
            modelName = modelName,
            hostAddress = host,
            port = port,
            capabilityMask = capabilityMask,
            rawTxtRecords = rawAttributes
        )
    }

    /**
     * Allows manual addition of a 1st-Gen Google Home or Cast speaker by IP address
     * (useful on isolated Wi-Fi subnets or when testing without multicast routing).
     */
    fun addManualDevice(
        friendlyName: String,
        hostAddress: String,
        port: Int = CastDeviceModel.DEFAULT_CAST_PORT,
        modelName: String = "Google Home",
        capabilityMask: Int = 2052 // 1st-Gen Google Home default: 0x800 (Multizone) | 0x04 (Audio Out)
    ): CastDeviceModel {
        val cleanHost = hostAddress.trim()
        val device = CastDeviceModel(
            id = "manual-$cleanHost:$port",
            friendlyName = friendlyName.trim().ifEmpty { "Living Room Speaker" },
            modelName = modelName.trim().ifEmpty { "Google Home" },
            hostAddress = cleanHost,
            port = port,
            capabilityMask = capabilityMask,
            rawTxtRecords = mapOf(
                "fn" to friendlyName,
                "md" to modelName,
                "ca" to capabilityMask.toString(),
                "id" to "manual-$cleanHost"
            )
        )
        upsertDevice(device)
        appendLog("Added target speaker: ${device.friendlyName} (${device.modelName}, ca=${device.capabilityMask}) @ $cleanHost:$port")
        return device
    }

    private fun upsertDevice(device: CastDeviceModel) {
        _devices.update { current ->
            val filtered = current.filterNot {
                it.id == device.id || (it.hostAddress == device.hostAddress && it.port == device.port)
            }
            (filtered + device).sortedWith(
                compareByDescending<CastDeviceModel> { it.isFirstGenGoogleHome }
                    .thenBy { it.friendlyName }
            )
        }
    }

    private fun acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("screencast_audio_mdns")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (multicastLock?.isHeld == false) {
                multicastLock?.acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire MulticastLock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {
        }
    }

    private fun appendLog(entry: String) {
        _discoveryLog.update { logs ->
            (logs + entry).takeLast(40)
        }
    }

    companion object {
        private const val TAG = "CastDeviceDiscovery"
        const val SERVICE_TYPE = "_googlecast._tcp."
    }
}
