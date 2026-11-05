package com.studiolight.tv

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.studiolight.core.*
import java.io.IOException
import java.security.SecureRandom
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

private val Context.lightStore by preferencesDataStore("studio-light")

class StudioApplication : Application() {
  val repository by lazy { LightRepository(this) }
}

@OptIn(FlowPreview::class)
class LightRepository(context: Context) {
  private val store = context.lightStore
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val mutex = Mutex()
  private val stateKey = stringPreferencesKey("state")
  private val pinKey = stringPreferencesKey("pin")
  val ready = CompletableDeferred<Unit>()
  val state = MutableStateFlow(ServerMessage.State(LightState()))
  val pin = MutableStateFlow("")
  val firstLaunch = MutableStateFlow(false)
  val serviceStatus = MutableStateFlow("Starting local server…")
  val persistenceError = MutableStateFlow<String?>(null)

  init {
    scope.launch {
      try {
        val prefs = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.first()
        firstLaunch.value = prefs[pinKey] == null
        pin.value = prefs[pinKey] ?: newPin()
        state.value = ServerMessage.State(runCatching {
          Protocol.json.decodeFromString<LightState>(prefs[stateKey] ?: "{}").validated()
        }.getOrDefault(LightState()))
        store.edit { it[pinKey] = pin.value }
      } catch (e: Exception) {
        pin.value = newPin()
        firstLaunch.value = true
        persistenceError.value = "Storage unavailable; changes may not survive restart."
      } finally { ready.complete(Unit) }
      // Conflate disk writes, not broadcasts; preserve the last slider value.
      state.debounce(180).collect { frame ->
        try { store.edit { it[stateKey] = Protocol.json.encodeToString(frame.state) } }
        catch (e: IOException) { persistenceError.value = "Could not save light state." }
      }
    }
  }

  suspend fun apply(message: ClientMessage) {
    ready.await()
    mutex.withLock {
      state.value = when (message) {
        is ClientMessage.Set -> ServerMessage.State(message.state.applyTo(state.value.state), message.instant)
        ClientMessage.Blackout -> ServerMessage.State(state.value.state.blackout(), true)
        ClientMessage.Ping -> state.value
      }
    }
  }

  suspend fun regeneratePin() {
    val next = newPin()
    try {
      store.edit { it[pinKey] = next }
      pin.value = next
    } catch (e: IOException) { persistenceError.value = "Could not save new PIN; try again." }
  }

  private fun newPin(): String {
    val random = SecureRandom()
    var next: String
    do { next = random.nextInt(10000).toString().padStart(4, '0') } while (next == pin.value)
    return next
  }
}
