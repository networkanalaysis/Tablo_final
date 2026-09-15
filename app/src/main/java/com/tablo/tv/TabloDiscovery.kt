package com.tablo.tv

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class DiscoveredTablo(
    val ipAddress: String,
    val name: String,
    val model: String,
    val tunerCount: Int,
    val serverId: String
) {
    val baseUrl: String get() = "http://$ipAddress:8885"
}

object TabloDiscovery {
    fun find(): List<DiscoveredTablo> {
        val ips = linkedSetOf<String>()
        discoverUdp(ips)
        discoverAssociationServer(ips)
        if (ips.isEmpty()) discoverSubnet(ips)
        return ips.mapNotNull { verify(it) }
    }

    private fun discoverUdp(ips: MutableSet<String>) {
        try {
            DatagramSocket(8882).use { receiver ->
                DatagramSocket().use { sender ->
                    receiver.broadcast = true
                    receiver.soTimeout = 350
                    val data = "tablo-discover".toByteArray(StandardCharsets.UTF_8)
                    sender.send(DatagramPacket(data, data.size, InetAddress.getByName("255.255.255.255"), 8881))
                    val deadline = System.currentTimeMillis() + 2_500
                    while (System.currentTimeMillis() < deadline) {
                        try {
                            val packet = DatagramPacket(ByteArray(2048), 2048)
                            receiver.receive(packet)
                            ips += packet.address.hostAddress.orEmpty()
                        } catch (_: SocketTimeoutException) {
                            // Keep listening until the discovery window closes.
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Association lookup and subnet discovery provide fallbacks.
        }
    }

    private fun discoverAssociationServer(ips: MutableSet<String>) {
        try {
            val body = http("https://api.tablotv.com/assocserver/getipinfo/")
            val cpes = JSONObject(body).optJSONArray("cpes") ?: return
            for (index in 0 until cpes.length()) {
                val cpe = cpes.getJSONObject(index)
                val ip = cpe.optString("private_ip", cpe.optString("slip"))
                if (ip.isNotBlank()) ips += ip
            }
        } catch (_: Exception) {
            // Local-only discovery still works when the association service is unavailable.
        }
    }

    private fun discoverSubnet(ips: MutableSet<String>) {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val host = address.hostAddress ?: continue
                        val prefix = host.substringBeforeLast('.', "")
                        for (lastOctet in 1..254) {
                            val candidate = "$prefix.$lastOctet"
                            try {
                                if (http("http://$candidate:8885/server/info", 250).isNotBlank()) ips += candidate
                            } catch (_: Exception) {
                                // Ignore hosts that do not expose the Tablo API.
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Report an empty device list to the login screen.
        }
    }

    private fun verify(ip: String): DiscoveredTablo? {
        return try {
            val info = JSONObject(http("http://$ip:8885/server/info"))
            DiscoveredTablo(
                ipAddress = ip,
                name = info.optString("name", "Tablo ($ip)"),
                model = info.optString("model", info.optString("board_type", "Tablo")),
                tunerCount = info.optInt("tuners", 2).coerceAtLeast(1),
                serverId = info.optString("server_id", "tablo_$ip")
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun http(url: String, timeout: Int = 6_000): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeout
            readTimeout = timeout
            requestMethod = "GET"
        }
        return connection.inputStream.bufferedReader().use { it.readText() }
    }
}
