package com.studiolight.phone

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.studiolight.core.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.random.Random

private val Context.controllerStore by preferencesDataStore("controller")
@Serializable data class TvDevice(val name: String, val address: String, val pin: String = "")
@Serializable data class SavedController(val tvs: List<TvDevice> = emptyList(), val presets: List<LightState> = emptyList())

class StudioApplication : Application() {
  val controller by lazy { Controller(this) }
}

class Controller(context: Context) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val store = context.controllerStore
  private val settingsKey = stringPreferencesKey("settings")
  private val client = HttpClient(CIO) {
    followRedirects = false // Never redirect a LAN socket or its PIN to another host.
    install(WebSockets) { pingIntervalMillis = 10_000; maxFrameSize = MAX_MESSAGE_BYTES.toLong() }
    engine { requestTimeout = 15_000; endpoint { connectTimeout = 5000 } }
  }
  val discovery = Discovery(context, scope)
  val state = MutableStateFlow(LightState())
  val saved = MutableStateFlow(SavedController())
  val connected = MutableStateFlow(false)
  val status = MutableStateFlow("Choose a TV")
  val name = MutableStateFlow("Studio Light")
  val needsPin = MutableStateFlow<TvDevice?>(null)
  val storageError = MutableStateFlow<String?>(null)
  private var target: TvDevice? = null
  private var connection: Job? = null
  private val ready = CompletableDeferred<Unit>()
  private var foreground = false
  private val wakeSender = Channel<Unit>(Channel.CONFLATED)
  private var pending: StatePatch? = null
  private var instant = false
  private var blackoutPending = false
  private var dragging: StatePatch? = null

  init {
    scope.launch {
      try {
        val text = store.data.first()[settingsKey]
        saved.value = if (text == null) SavedController() else Protocol.json.decodeFromString<SavedController>(text).let {
          it.copy(tvs = it.tvs.filter { tv -> runCatching { LanEndpoint.parse(tv.address) }.isSuccess },
            presets = it.presets.filter { s -> runCatching { s.validated() }.isSuccess }.take(12))
        }
        target = saved.value.tvs.firstOrNull()
      } catch (e: Exception) { storageError.value = "Saved settings unavailable." }
      finally { ready.complete(Unit) }
      if (foreground) target?.let { connect(it) }
    }
  }

  fun start() {
    foreground = true
    discovery.start()
    scope.launch { ready.await(); if (connection?.isActive != true) target?.let { connect(it) } }
  }
  fun stop() {
    foreground = false
    discovery.stop()
    connection?.cancel()
    connected.value = false
    clearPending()
  }
  fun choose(device: TvDevice) {
    val remembered = saved.value.tvs.firstOrNull { it.address == device.address }
    if (remembered != null) connect(remembered) else needsPin.value = device
  }
  fun pair(device: TvDevice, pin: String) {
    if (pin.matches(Regex("[0-9]{4}"))) { needsPin.value = null; connect(device.copy(pin = pin)) }
  }
  fun connect(device: TvDevice) {
    val endpoint = LanEndpoint.parse(device.address)
    val previous = connection
    previous?.cancel()
    target = device; name.value = device.name
    connected.value = false; clearPending()
    connection = scope.launch {
      previous?.join()
      var backoff = 500L
      while (isActive && foreground) {
        status.value = "Reconnecting…"
        var requireNewPin = false
        try {
          client.webSocket(urlString = "ws://${endpoint.authority}/light", request = {
            header(PIN_HEADER, device.pin)
          }) {
            var greeted = false
            val sender = launch {
              for (signal in wakeSender) {
                delay(33) // Merge edits to at most ~30 messages/second.
                val patch = pending; val immediate = instant; val black = blackoutPending
                pending = null; blackoutPending = false; instant = false
                if (black) send(Protocol.encode(ClientMessage.Blackout))
                if (patch != null) send(Protocol.encode(ClientMessage.Set(patch, immediate)))
              }
            }
            try {
              for (frame in incoming) {
                if (frame !is Frame.Text) continue
                when (val message = Protocol.server(frame.readText())) {
                  is ServerMessage.Hello -> { name.value = message.deviceName; greeted = true }
                  is ServerMessage.State -> {
                    state.value = dragging?.applyTo(message.state) ?: message.state
                    if (!connected.value && greeted) {
                      connected.value = true; status.value = "Connected"; backoff = 500
                      saved.value = saved.value.copy(tvs =
                        (listOf(device.copy(name = name.value)) + saved.value.tvs.filterNot { it.address == device.address }).take(12))
                      persist()
                    }
                  }
                }
              }
            } finally { sender.cancelAndJoin() }
            if (closeReason.await()?.code == CloseReason.Codes.VIOLATED_POLICY.code) {
              needsPin.value = device.copy(pin = ""); status.value = "Enter the TV’s new PIN"
              requireNewPin = true
            }
          }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
          if (e.message.orEmpty().contains("401") || e.message.orEmpty().contains("Unauthorized", true)) {
            needsPin.value = device.copy(pin = ""); status.value = "PIN rejected · enter PIN from TV"
            break
          }
          status.value = "Reconnecting · check TV and WiFi"
        } finally { connected.value = false; clearPending() }
        if (requireNewPin) break
        delay(backoff + Random.nextLong(0, 250))
        backoff = (backoff * 2).coerceAtMost(8000)
      }
    }
  }

  fun edit(patch: StatePatch, isDragging: Boolean = false) {
    if (!connected.value) return
    state.value = patch.applyTo(state.value)
    if (isDragging) dragging = (dragging ?: StatePatch()).mergedWith(patch)
    pending = (pending ?: StatePatch()).mergedWith(patch)
    instant = instant || isDragging
    wakeSender.trySend(Unit)
  }
  fun finishDrag() {
    val finalPatch = dragging
    dragging = null
    if (finalPatch != null) {
      pending = (pending ?: StatePatch()).mergedWith(finalPatch)
      instant = true; wakeSender.trySend(Unit)
    }
  }
  fun blackout() {
    if (!connected.value) return
    state.value = state.value.blackout()
    dragging = null; pending = null; blackoutPending = true
    wakeSender.trySend(Unit)
  }
  fun savePreset() {
    saved.value = saved.value.copy(presets = (saved.value.presets + state.value.copy(strobe = false, cycle = false)).takeLast(12))
    persist()
  }
  fun deletePreset(index: Int) {
    saved.value = saved.value.copy(presets = saved.value.presets.filterIndexed { i, _ -> i != index })
    persist()
  }
  private fun clearPending() {
    pending = null; dragging = null; instant = false; blackoutPending = false
    while (wakeSender.tryReceive().isSuccess) { /* discard edits after disconnect */ }
  }
  private fun persist() {
    val snapshot = saved.value
    scope.launch {
      try { store.edit { it[settingsKey] = Protocol.json.encodeToString(snapshot) } }
      catch (e: Exception) { storageError.value = "Could not save pairing or presets." }
    }
  }
}
