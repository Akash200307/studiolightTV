package com.studiolight.core

import java.net.InetAddress

/** No DNS lookup for user input: only numeric LAN IPv4/IPv6 endpoints. */
data class LanEndpoint(val host: String, val port: Int = PORT) {
  init {
    require(port in 1..65535) { "Invalid port" }
    require(host.matches(Regex("[0-9.]+")) ||
      (':' in host && host.matches(Regex("[0-9a-fA-F:%a-zA-Z0-9_.-]+")))) { "Enter a numeric LAN IP" }
    require(isLocalAddress(InetAddress.getByName(host))) { "Only local network addresses are allowed" }
  }
  val authority: String get() = if (':' in host) "[$host]:$port" else "$host:$port"
  companion object {
    fun parse(input: String): LanEndpoint {
      val text = input.trim()
      require(!text.contains('/') && !text.contains('@')) { "Enter IP[:port], without a URL" }
      return if (text.startsWith('[')) {
        val end = text.indexOf(']'); require(end > 0)
        val suffix = text.substring(end + 1)
        require(suffix.isEmpty() || suffix.startsWith(':'))
        LanEndpoint(text.substring(1, end), if (suffix.isEmpty()) PORT else suffix.drop(1).toInt())
      } else if (text.count { it == ':' } == 1) {
        LanEndpoint(text.substringBefore(':'), text.substringAfter(':').toInt())
      } else LanEndpoint(text)
    }
  }
}

fun isLocalAddress(address: InetAddress): Boolean {
  val bytes = address.address
  val uniqueLocalV6 = bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
  return !address.isAnyLocalAddress && !address.isMulticastAddress &&
    (address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress || uniqueLocalV6)
}
