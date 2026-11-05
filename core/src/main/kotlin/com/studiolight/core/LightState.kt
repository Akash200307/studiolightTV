package com.studiolight.core

import kotlinx.serialization.Serializable

@Serializable
enum class LightMode { COLOR, WHITE }

@Serializable
data class LightState(
  val hue: Float = 35f,
  val saturation: Float = 75f,
  val brightness: Float = 80f,
  val opacity: Float = 100f,
  val mode: LightMode = LightMode.COLOR,
  val kelvin: Int = 4500,
  val strobe: Boolean = false,
  val strobeHz: Float = 2f,
  val cycle: Boolean = false,
  val cycleSpeed: Float = 1f,
) {
  fun validated(): LightState = apply {
    require(hue.isFinite() && hue in 0f..360f) { "hue out of range" }
    require(saturation.isFinite() && saturation in 0f..100f) { "saturation out of range" }
    require(brightness.isFinite() && brightness in 0f..100f) { "brightness out of range" }
    require(opacity.isFinite() && opacity in 0f..100f) { "opacity out of range" }
    require(kelvin in 2500..9000) { "kelvin out of range" }
    require(strobeHz.isFinite() && strobeHz in 1f..10f) { "strobeHz out of range" }
    require(cycleSpeed.isFinite() && cycleSpeed in 1f..10f) { "cycleSpeed out of range" }
  }
  fun blackout() = copy(brightness = 0f, strobe = false, cycle = false)
}

@Serializable
data class StatePatch(
  val hue: Float? = null,
  val saturation: Float? = null,
  val brightness: Float? = null,
  val opacity: Float? = null,
  val mode: LightMode? = null,
  val kelvin: Int? = null,
  val strobe: Boolean? = null,
  val strobeHz: Float? = null,
  val cycle: Boolean? = null,
  val cycleSpeed: Float? = null,
) {
  fun applyTo(s: LightState) = s.copy(
    hue = hue ?: s.hue, saturation = saturation ?: s.saturation,
    brightness = brightness ?: s.brightness, opacity = opacity ?: s.opacity,
    mode = mode ?: s.mode, kelvin = kelvin ?: s.kelvin,
    strobe = strobe ?: s.strobe, strobeHz = strobeHz ?: s.strobeHz,
    cycle = cycle ?: s.cycle, cycleSpeed = cycleSpeed ?: s.cycleSpeed,
  ).validated()

  // Merge only fields being edited so another phone's changes are preserved.
  fun mergedWith(p: StatePatch) = StatePatch(
    p.hue ?: hue, p.saturation ?: saturation, p.brightness ?: brightness,
    p.opacity ?: opacity, p.mode ?: mode, p.kelvin ?: kelvin,
    p.strobe ?: strobe, p.strobeHz ?: strobeHz, p.cycle ?: cycle, p.cycleSpeed ?: cycleSpeed,
  )

  companion object {
    fun from(s: LightState) = StatePatch(s.hue, s.saturation, s.brightness, s.opacity,
      s.mode, s.kelvin, s.strobe, s.strobeHz, s.cycle, s.cycleSpeed)
  }
}

data class Preset(val name: String, val state: LightState)
val builtInPresets = listOf(
  Preset("Red", LightState(hue = 0f, saturation = 100f)),
  Preset("Orange", LightState(hue = 28f, saturation = 100f)),
  Preset("Warm", LightState(mode = LightMode.WHITE, kelvin = 3000)),
  Preset("Cool", LightState(mode = LightMode.WHITE, kelvin = 7500)),
  Preset("Pink", LightState(hue = 330f, saturation = 65f)),
  Preset("Purple", LightState(hue = 275f, saturation = 100f)),
  Preset("Blue", LightState(hue = 235f, saturation = 100f)),
  Preset("Cyan", LightState(hue = 185f, saturation = 100f)),
  Preset("Green", LightState(hue = 120f, saturation = 100f)),
  Preset("Neutral", LightState(mode = LightMode.WHITE, kelvin = 6500)),
)
