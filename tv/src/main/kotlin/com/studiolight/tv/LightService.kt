package com.studiolight.tv

import android.app.*
import android.content.*
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import com.studiolight.core.*
import java.net.NetworkInterface
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun lanAddresses(): String = runCatching {
  NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
    .filter { !it.isLoopbackAddress && it.address.size == 4 && isLocalAddress(it) }
    .joinToString(" / ") { "${it.hostAddress}:$PORT" }.ifEmpty { "Connect TV to WiFi or Ethernet" }
}.getOrDefault("Network unavailable")

class LightService : Service() {
  companion object {
    // A quick Home/reopen must wait until the previous service releases 8765.
    private val serverMutex = Mutex()
  }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var registration: NsdManager.RegistrationListener? = null
  private var multicast: WifiManager.MulticastLock? = null
  private val repo get() = (application as StudioApplication).repository

  override fun onCreate() {
    super.onCreate()
    val notifications = getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= 26) notifications.createNotificationChannel(
      NotificationChannel("light", "Studio Light server", NotificationManager.IMPORTANCE_LOW))
    val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val notification = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "light")
      else Notification.Builder(this)).setContentTitle("Studio Light")
      .setContentText("Local controller server · port $PORT").setSmallIcon(R.drawable.ic_light)
      .setContentIntent(intent).setOngoing(true).build()
    startForeground(1, notification)
    scope.launch {
      repo.ready.await()
      serverMutex.withLock {
        val running = LightServer(Build.MODEL, BuildConfig.VERSION_NAME, { repo.pin.value }, repo.state, repo::apply)
        var observedPin = repo.pin.value
        try {
          running.start()
          ensureActive()
          repo.serviceStatus.value = "Ready · ${lanAddresses()}"
          withContext(Dispatchers.Main.immediate) {
            runCatching { advertise() }.onFailure {
              repo.serviceStatus.value = "mDNS unavailable; connect by IP · ${lanAddresses()}"
            }
          }
          repo.pin.collect { next ->
            if (next != observedPin) {
              running.disconnectControllers()
              observedPin = next
            }
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          repo.serviceStatus.value = "Server unavailable: ${e.message ?: "network error"}"
        } finally {
          running.stop()
        }
      }
    }
  }

  private fun advertise() {
    val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    multicast = wifi?.createMulticastLock("StudioLight")?.apply { setReferenceCounted(false); acquire() }
    val nsd = getSystemService(NsdManager::class.java)
    registration = object : NsdManager.RegistrationListener {
      override fun onServiceRegistered(info: NsdServiceInfo) = Unit
      override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {
        repo.serviceStatus.value = "mDNS unavailable; connect by IP · ${lanAddresses()}"
      }
      override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
      override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
    }.also { listener ->
      nsd.registerService(NsdServiceInfo().apply {
        serviceName = "Studio Light · ${Build.MODEL}"
        serviceType = SERVICE_TYPE
        port = PORT
      }, NsdManager.PROTOCOL_DNS_SD, listener)
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
  override fun onBind(intent: Intent?): IBinder? = null
  override fun onDestroy() {
    registration?.let { runCatching { getSystemService(NsdManager::class.java).unregisterService(it) } }
    multicast?.let { if (it.isHeld) it.release() }
    scope.cancel()
    repo.serviceStatus.value = "Server stopped"
    super.onDestroy()
  }
}
