package com.studiolight.tv

import com.studiolight.core.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.websocket.*
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

class LightServerTest {
  private suspend fun DefaultClientWebSocketSession.next(): ServerMessage = withTimeout(5000) {
    Protocol.server((incoming.receive() as Frame.Text).readText())
  }
  private suspend fun fixture(pin: () -> String = { "1234" },
    block: suspend (LightServer, HttpClient, Int, MutableStateFlow<ServerMessage.State>) -> Unit) {
    val port = ServerSocket(0).use { it.localPort }
    val state = MutableStateFlow(ServerMessage.State(LightState(hue = 77f)))
    val server = LightServer("Test TV", "1.0.0", pin, state, { msg ->
      state.value = when (msg) {
        is ClientMessage.Set -> ServerMessage.State(msg.state.applyTo(state.value.state), msg.instant)
        ClientMessage.Blackout -> ServerMessage.State(state.value.state.blackout(), true)
        ClientMessage.Ping -> state.value
      }
    }, port)
    val client = HttpClient(CIO) { install(WebSockets) }
    try { server.start(); block(server, client, port, state) }
    finally { client.close(); server.stop() }
  }
  private suspend fun HttpClient.connect(port: Int, pin: String = "1234") =
    webSocketSession("ws://127.0.0.1:$port/light") { header(PIN_HEADER, pin) }

  @Test fun initialStateBroadcastBlackoutAndPing() = runBlocking {
    fixture { _, client, port, _ ->
      val a = client.connect(port); val b = client.connect(port)
      assertEquals(ServerMessage.Hello("Test TV", "1.0.0"), a.next())
      assertEquals(77f, (a.next() as ServerMessage.State).state.hue)
      b.next(); b.next()
      a.send(Protocol.encode(ClientMessage.Set(StatePatch(hue = 220f), true)))
      assertEquals(220f, (a.next() as ServerMessage.State).state.hue)
      val other = b.next() as ServerMessage.State
      assertEquals(220f, other.state.hue); assertTrue(other.instant)
      b.send(Protocol.encode(ClientMessage.Blackout))
      assertEquals(0f, (a.next() as ServerMessage.State).state.brightness)
      assertEquals(0f, (b.next() as ServerMessage.State).state.brightness)
      a.send(Protocol.encode(ClientMessage.Ping))
      assertEquals(0f, (a.next() as ServerMessage.State).state.brightness)
      a.close(); b.close()
    }
  }
  @Test fun invalidPinRejectedBeforeStateAndRateLimited() = runBlocking {
    fixture { _, client, port, _ ->
      repeat(5) {
        val error = assertFails { client.connect(port, "9999") }
        assertTrue(error.message.orEmpty().contains("401"), "PIN failures must expose the HTTP status to the phone")
      }
      assertFails { client.connect(port) }
    }
  }
  @Test fun malformedMessagesCloseOnlyOffendingConnection() = runBlocking {
    fixture { _, client, port, state ->
      val a = client.connect(port); a.next(); a.next()
      a.send(Frame.Text("""{"type":"set","state":{"brightness":101}}"""))
      assertEquals(CloseReason.Codes.NOT_CONSISTENT.code, withTimeout(5000) { a.closeReason.await() }?.code)
      assertEquals(80f, state.value.state.brightness)
      val b = client.connect(port); b.next()
      assertEquals(80f, (b.next() as ServerMessage.State).state.brightness)
      b.close()
    }
  }
  @Test fun regeneratingPinDisconnectsControllers() = runBlocking {
    fixture { server, client, port, _ ->
      val a = client.connect(port); a.next(); a.next()
      server.disconnectControllers()
      assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, withTimeout(5000) { a.closeReason.await() }?.code)
    }
  }
  @Test fun missingPinCannotUpgrade() = runBlocking {
    fixture { _, client, port, _ ->
      val error = assertFails { client.webSocketSession("ws://127.0.0.1:$port/light") }
      assertTrue(error.message.orEmpty().contains("401"))
    }
  }
  @Test fun revokedPinCannotSendUpdatesEvenBeforeDisconnectBroadcast() = runBlocking {
    val pin = AtomicReference("1234")
    fixture(pin = pin::get) { _, client, port, state ->
      val a = client.connect(port); a.next(); a.next()
      pin.set("5678")
      a.send(Protocol.encode(ClientMessage.Set(StatePatch(brightness = 10f))))
      assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, withTimeout(5000) { a.closeReason.await() }?.code)
      assertEquals(80f, state.value.state.brightness)
      val b = client.connect(port, "5678"); b.next()
      assertEquals(80f, (b.next() as ServerMessage.State).state.brightness)
      b.close()
    }
  }
}
