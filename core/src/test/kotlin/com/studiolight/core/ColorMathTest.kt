package com.studiolight.core

import kotlin.test.*

class ColorMathTest {
  @Test fun warmEndpoint() { assertEquals(Rgb(255, 159, 70), kelvinToRgb(2500)) }
  @Test fun neutralWhite() { assertEquals(Rgb(255, 254, 250), kelvinToRgb(6500)) }
  @Test fun coolEndpoint() { assertEquals(Rgb(210, 223, 255), kelvinToRgb(9000)) }
  @Test fun clampsUnsupportedTemperatures() {
    assertEquals(kelvinToRgb(2500), kelvinToRgb(-100))
    assertEquals(kelvinToRgb(9000), kelvinToRgb(100000))
  }
  @Test fun allSupportedTemperaturesStayInGamut() {
    for (kelvin in 2500..9000) {
      val rgb = kelvinToRgb(kelvin)
      assertTrue(rgb.red in 0..255 && rgb.green in 0..255 && rgb.blue in 0..255)
    }
  }
  @Test fun warmerHasLessBlue() { assertTrue(kelvinToRgb(2500).blue < kelvinToRgb(6000).blue) }
  @Test fun primaryHuesAndWrap() {
    assertEquals(Rgb(255, 0, 0), hsvToRgb(0f, 100f, 100f))
    assertEquals(Rgb(0, 255, 0), hsvToRgb(120f, 100f, 100f))
    assertEquals(Rgb(0, 0, 255), hsvToRgb(240f, 100f, 100f))
    assertEquals(hsvToRgb(0f, 100f, 100f), hsvToRgb(360f, 100f, 100f))
  }
  @Test fun blackAndDesaturatedWhite() {
    assertEquals(Rgb(0, 0, 0), LightState(brightness = 0f).rgb())
    assertEquals(Rgb(255, 255, 255), hsvToRgb(17f, 0f, 100f))
    assertEquals(Rgb(0, 0, 0), LightState(mode = LightMode.WHITE, brightness = 0f).rgb())
  }
}
