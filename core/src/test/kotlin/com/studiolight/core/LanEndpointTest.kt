package com.studiolight.core

import kotlin.test.*

class LanEndpointTest {
  @Test fun privateIpAndPort() {
    assertEquals("192.168.1.42:8765", LanEndpoint.parse("192.168.1.42").authority)
    assertEquals(9999, LanEndpoint.parse("10.0.0.3:9999").port)
    assertEquals("[fd00::1]:8765", LanEndpoint.parse("[fd00::1]:8765").authority)
  }
  @Test fun publicAndNonNumericEndpointsRejected() {
    listOf("8.8.8.8", "example.com", "https://192.168.1.1", "0.0.0.0", "224.0.0.1",
      "192.168.1.2:0", "192.168.1.2:65536", "192.168.1.1/path").forEach {
      assertFails("accepted $it") { LanEndpoint.parse(it) }
    }
  }
}
