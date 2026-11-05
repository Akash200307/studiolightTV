package com.studiolight.tv

import com.studiolight.core.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.Collections
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration.Companion.seconds

/** Transport has no Android dependencies, allowing real socket integration tests. */
class LightServer(
  private val deviceName: String,
  private val version: String,
  private val pin: () -> String,
  private val state: StateFlow<ServerMessage.State>,
  private val onMessage: suspend (ClientMessage) -> Unit,
  private val port: Int = PORT,
) {
  private val sessions: MutableSet<DefaultWebSocketServerSession> =
    Collections.newSetFromMap(ConcurrentHashMap<DefaultWebSocketServerSession, Boolean>())
  private val attempts = LinkedHashMap<String, Pair<Long, Int>>()
  private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

  fun start() {
    engine = embeddedServer(CIO, host = "0.0.0.0", port = port) {
      install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 30.seconds
        maxFrameSize = MAX_MESSAGE_BYTES.toLong()
      }
      routing {
        route("/light") {
          intercept(ApplicationCallPipeline.Call) {
            val peer = call.request.local.remoteHost
            if (!runCatching { isLocalAddress(InetAddress.getByName(peer)) }.getOrDefault(false)) {
              call.respond(HttpStatusCode.Forbidden); finish()
            } else if (!authenticate(peer, call.request.headers[PIN_HEADER])) {
              call.respond(HttpStatusCode.Unauthorized); finish()
            } else if (sessions.size >= 8) {
              call.respond(HttpStatusCode.ServiceUnavailable); finish()
            }
          }
          webSocket {
            val sessionPin = call.request.headers[PIN_HEADER]
            sessions.add(this)
            try {
              if (sessionPin != pin()) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "PIN changed"))
                return@webSocket
              }
              send(Protocol.encode(ServerMessage.Hello(deviceName, version)))
              // StateFlow immediately delivers the full state, then all changes.
              val broadcast = launch {
                state.collect {
                  if (sessionPin == pin()) send(Protocol.encode(it))
                  else close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "PIN changed"))
                }
              }
              try {
                for (frame in incoming) {
                  if (sessionPin != pin()) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "PIN changed")); break
                  }
                  if (frame !is Frame.Text) {
                    close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Text JSON required")); break
                  }
                  val message = try { Protocol.client(frame.readText()) }
                  catch (e: Exception) {
                    close(CloseReason(CloseReason.Codes.NOT_CONSISTENT, "Invalid message")); break
                  }
                  if (message == ClientMessage.Ping) send(Protocol.encode(state.value))
                  else onMessage(message)
                }
              } finally { broadcast.cancelAndJoin() }
            } finally { sessions.remove(this) }
          }
        }
      }
    }.start(wait = false)
  }

  private fun authenticate(peer: String, supplied: String?): Boolean = synchronized(attempts) {
    val now = System.nanoTime() / 1_000_000
    val (time, count) = attempts[peer]?.takeIf { now - it.first < 60_000 } ?: (now to 0)
    if (count >= 5) return@synchronized false
    if (supplied != null && supplied.matches(Regex("[0-9]{4}")) && supplied == pin()) {
      attempts.remove(peer)
      true
    } else {
      if (attempts.size >= 128 && peer !in attempts) attempts.remove(attempts.keys.first())
      attempts[peer] = time to (count + 1)
      false
    }
  }

  suspend fun disconnectControllers() {
    sessions.toList().forEach { it.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "PIN changed")) }
    synchronized(attempts) { attempts.clear() }
  }
  fun stop() { engine?.stop(300, 1000); engine = null }
}
