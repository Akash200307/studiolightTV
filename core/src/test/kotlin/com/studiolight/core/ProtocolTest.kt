package com.studiolight.core

import kotlin.test.*

class ProtocolTest {
  @Test fun allClientMessagesRoundTrip() {
    val messages = listOf(ClientMessage.Set(StatePatch(hue = 120f, brightness = 60f), true),
      ClientMessage.Blackout, ClientMessage.Ping)
    messages.forEach { assertEquals(it, Protocol.client(Protocol.encode(it))) }
  }
  @Test fun fullStateAndHelloRoundTrip() {
    val messages = listOf(ServerMessage.Hello("Living room", "1.0.0"),
      ServerMessage.State(LightState(mode = LightMode.WHITE, kelvin = 9000), true))
    messages.forEach { assertEquals(it, Protocol.server(Protocol.encode(it))) }
  }
  @Test fun wireFormatUsesTypeAndPartialState() {
    val message = Protocol.client("""{"type":"set","state":{"brightness":45},"instant":true}""") as ClientMessage.Set
    val initial = LightState(hue = 225f, saturation = 47f, cycle = true)
    assertEquals(initial.copy(brightness = 45f), message.state.applyTo(initial))
    assertEquals("""{"type":"blackout"}""", Protocol.encode(ClientMessage.Blackout))
    assertTrue(Protocol.encode(ServerMessage.State(initial)).contains("\"kelvin\""))
  }
  @Test fun malformedUnknownAndMistypedMessagesAreRejected() {
    listOf("nope", "{}", "[]", """{"type":"other"}""",
      """{"type":"set","state":{"brightness":"50"}}""",
      """{"type":"set","state":{"mode":"INVALID"}}""",
      """{"type":"set","state":{"brightness":null}}""",
      """{"type":"set","state":{"strobe":"true"}}""",
      """{"type":"set","state":{"brightness":50},"extra":true}""",
      """{"type":"set","state":{"hue":-1}}""",
      """{"type":"set","state":{"brightness":101}}""",
      """{"type":"set","state":{"kelvin":9001}}""",
      """{"type":"set","state":{"strobeHz":0}}""",
      """{"type":"set","state":{"cycleSpeed":11}}""").forEach {
      assertFails("accepted $it") { Protocol.client(it) }
    }
  }
  @Test fun oversizedMessagesRejected() { assertFails { Protocol.client(" ".repeat(4097)) } }
  @Test fun nonFiniteStateRejected() {
    assertFails { LightState(hue = Float.NaN).validated() }
    assertFails { LightState(opacity = Float.POSITIVE_INFINITY).validated() }
  }
  @Test fun allBoundsAreValidated() {
    assertEquals(0f, LightState(hue = 0f, brightness = 0f, opacity = 0f).validated().hue)
    LightState(hue = 360f, saturation = 100f, kelvin = 2500, strobeHz = 10f, cycleSpeed = 10f).validated()
    assertFails { StatePatch(saturation = 101f).applyTo(LightState()) }
    assertFails { StatePatch(opacity = -1f).applyTo(LightState()) }
  }
  @Test fun blackoutStopsEffectsAndLeavesColor() {
    val s = LightState(hue = 200f, strobe = true, cycle = true).blackout()
    assertEquals(200f, s.hue); assertEquals(0f, s.brightness)
    assertFalse(s.strobe); assertFalse(s.cycle)
  }
  @Test fun mergeDoesNotOverwriteOtherFields() {
    val patch = StatePatch(hue = 10f, brightness = 40f).mergedWith(StatePatch(hue = 90f, cycle = false))
    val s = patch.applyTo(LightState(opacity = 32f, cycle = true))
    assertEquals(90f, s.hue); assertEquals(40f, s.brightness)
    assertEquals(32f, s.opacity); assertFalse(s.cycle)
  }
  @Test fun stateStorageRoundTrip() {
    val s = LightState(hue = 19.25f, saturation = 13f, mode = LightMode.WHITE, kelvin = 3200)
    val json = Protocol.json.encodeToString(LightState.serializer(), s)
    assertEquals(s, Protocol.json.decodeFromString<LightState>(json).validated())
  }
}
