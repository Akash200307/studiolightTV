package com.studiolight.phone

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.studiolight.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow

class Discovery(context: Context, private val scope: CoroutineScope) {
  private val manager = context.getSystemService(NsdManager::class.java)
  private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
  private var lock: WifiManager.MulticastLock? = null
  private var listener: NsdManager.DiscoveryListener? = null
  private var resolver: Job? = null
  val devices = MutableStateFlow<List<TvDevice>>(emptyList())
  val message = MutableStateFlow("Looking for TVs on your WiFi…")

  fun start() {
    if (listener != null) return
    val queue = Channel<NsdServiceInfo>(32)
    resolver = scope.launch {
      for (service in queue) {
        val result = CompletableDeferred<NsdServiceInfo?>()
        try {
          manager.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) { result.complete(null) }
            override fun onServiceResolved(info: NsdServiceInfo) { result.complete(info) }
          })
          // Serialize resolve calls for Android's legacy NSD API.
          val info = result.await() ?: continue
          val host = info.host?.hostAddress ?: continue
          val endpoint = runCatching { LanEndpoint(host, info.port) }.getOrNull() ?: continue
          val device = TvDevice(info.serviceName, endpoint.authority)
          devices.value = (devices.value.filterNot { it.name == device.name } + device).sortedBy { it.name }
        } catch (e: SecurityException) { message.value = "Nearby permission denied; use Connect by IP." }
      }
    }
    listener = object : NsdManager.DiscoveryListener {
      override fun onDiscoveryStarted(type: String) { message.value = "Looking for TVs on your WiFi…" }
      override fun onServiceFound(info: NsdServiceInfo) {
        if (info.serviceType.trimEnd('.') == SERVICE_TYPE.trimEnd('.')) queue.trySend(info)
      }
      override fun onServiceLost(info: NsdServiceInfo) {
        devices.value = devices.value.filterNot { it.name == info.serviceName }
      }
      override fun onDiscoveryStopped(type: String) = Unit
      override fun onStartDiscoveryFailed(type: String, code: Int) { message.value = "Discovery unavailable; use Connect by IP." }
      override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
    }
    try {
      lock = wifi.createMulticastLock("StudioLightDiscovery").apply { setReferenceCounted(false); acquire() }
      manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    } catch (e: Exception) { message.value = "Discovery unavailable; use Connect by IP." }
  }

  fun stop() {
    listener?.let { runCatching { manager.stopServiceDiscovery(it) } }
    listener = null
    resolver?.cancel()
    lock?.let { if (it.isHeld) it.release() }; lock = null
    devices.value = emptyList()
  }
}
