package com.studiolight.core

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

data class Rgb(val red: Int, val green: Int, val blue: Int) {
  fun dim(amount: Float) = Rgb((red * amount).roundToInt(), (green * amount).roundToInt(),
    (blue * amount).roundToInt())
  fun argb(): Int = (0xff shl 24) or (red shl 16) or (green shl 8) or blue
}

/** Approximate blackbody color (Tanner Helland), bounded to the supported range. */
fun kelvinToRgb(kelvin: Int): Rgb {
  val t = kelvin.coerceIn(2500, 9000) / 100.0
  fun channel(v: Double) = v.roundToInt().coerceIn(0, 255)
  val red = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
  val green = if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661
    else 288.1221695283 * (t - 60).pow(-0.0755148492)
  val blue = if (t >= 66) 255.0 else 138.5177312231 * ln(t - 10) - 305.0447927307
  return Rgb(channel(red), channel(green), channel(blue))
}

fun hsvToRgb(hue: Float, saturation: Float, brightness: Float): Rgb {
  val h = ((hue % 360 + 360) % 360) / 60f
  val c = brightness.coerceIn(0f, 100f) / 100 * saturation.coerceIn(0f, 100f) / 100
  val x = c * (1 - kotlin.math.abs(h % 2 - 1))
  val m = brightness.coerceIn(0f, 100f) / 100 - c
  val (r, g, b) = when (h.toInt()) {
    0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
    3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
  }
  return Rgb(((r + m) * 255).roundToInt(), ((g + m) * 255).roundToInt(), ((b + m) * 255).roundToInt())
}

fun LightState.rgb(hueOverride: Float = hue): Rgb =
  if (mode == LightMode.WHITE) kelvinToRgb(kelvin).dim(brightness / 100f)
  else hsvToRgb(hueOverride, saturation, brightness)
