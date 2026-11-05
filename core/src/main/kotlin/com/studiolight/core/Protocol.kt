package com.studiolight.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull

const val PORT = 8765
const val SERVICE_TYPE = "_studiolight._tcp."
const val PIN_HEADER = "X-Studio-Pin"
const val MAX_MESSAGE_BYTES = 4096

@Serializable
sealed interface ClientMessage {
  @Serializable @SerialName("set")
  data class Set(val state: StatePatch, val instant: Boolean = false) : ClientMessage
  @Serializable @SerialName("blackout")
  data object Blackout : ClientMessage
  @Serializable @SerialName("ping")
  data object Ping : ClientMessage
}

@Serializable
sealed interface ServerMessage {
  @Serializable @SerialName("hello")
  data class Hello(val deviceName: String, val appVersion: String) : ServerMessage
  @Serializable @SerialName("state")
  data class State(val state: LightState, val instant: Boolean = false) : ServerMessage
}

object Protocol {
  val json = Json { encodeDefaults = true; explicitNulls = false; classDiscriminator = "type" }
  fun encode(message: ClientMessage): String = json.encodeToString(message)
  fun encode(message: ServerMessage): String = json.encodeToString(message)
  fun client(text: String): ClientMessage {
    require(text.toByteArray().size <= MAX_MESSAGE_BYTES) { "Message too large" }
    validatePrimitiveTypes(text)
    return json.decodeFromString<ClientMessage>(text).also {
      if (it is ClientMessage.Set) it.state.applyTo(LightState())
    }
  }
  fun server(text: String): ServerMessage {
    require(text.toByteArray().size <= MAX_MESSAGE_BYTES)
    validatePrimitiveTypes(text)
    return json.decodeFromString<ServerMessage>(text).also {
      if (it is ServerMessage.State) it.state.validated()
    }
  }

  private fun validatePrimitiveTypes(text: String) {
    val root = json.parseToJsonElement(text) as? JsonObject ?: error("Object required")
    root["instant"]?.let { require(it is JsonPrimitive && !it.isString) }
    val fields = root["state"] as? JsonObject ?: return
    fields.forEach { (key, value) ->
      require(value is JsonPrimitive && value != JsonNull) { "Non-null primitive required" }
      if (key == "mode") require(value.isString) else require(!value.isString) { "Unquoted value required" }
    }
  }
}
