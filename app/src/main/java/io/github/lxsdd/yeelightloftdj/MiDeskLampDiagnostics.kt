package io.github.lxsdd.yeelightloftdj

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Read-only, opt-in Xiaomi MiIO HELLO probe (UDP 54321).
 * Does not retrieve tokens or attempt authenticated controls.
 * A positive hello identifies a different protocol, not Yeelight LAN support.
 */
object MiDeskLampDiagnostics {
    private val hello = byteArrayOf(
        0x21, 0x31, 0, 0x20,
        -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1
    )

    fun validMiIoReply(data: ByteArray, size: Int): Boolean =
        size in 32..1024 && data.size >= size &&
            data[0] == 0x21.toByte() && data[1] == 0x31.toByte() &&
            ((data[2].toInt() and 0xff) shl 8 or (data[3].toInt() and 0xff)) in 32..size

    fun diagnostic(ip: String): String {
        require(YeelightLan.isLocalIp(ip)) { "Bitte eine gültige private WLAN-IP verwenden" }
        if (YeelightLan.probe(ip) != null) {
            return "Yeelight-LAN-Protokoll (TCP 55443) antwortet; Gerät kann hinzugefügt werden."
        }
        val reply = try {
            val local = YeelightLan.wifiIpv4Address()
                ?: error("Keine WLAN-IPv4-Adresse erkennbar")
            DatagramSocket(InetSocketAddress(InetAddress.getByName(local), 0)).use { socket ->
                socket.soTimeout = 650
                val address = InetAddress.getByName(ip)
                var detected = false
                repeat(2) {
                    socket.send(DatagramPacket(hello, hello.size, address, 54321))
                    try {
                        val data = ByteArray(1024)
                        val p = DatagramPacket(data, data.size)
                        socket.receive(p)
                        if (p.address.hostAddress == ip && validMiIoReply(p.data, p.length)) {
                            detected = true
                        }
                    } catch (_: SocketTimeoutException) {}
                }
                detected
            }
        } catch (_: Exception) { false }
        return if (reply) {
            "Xiaomi MiIO (UDP 54321) antwortet; Yeelight TCP 55443 nicht. " +
                "Die 1S benötigt gegebenenfalls einen separaten MiIO/MIoT-Adapter mit autorisiertem Token. " +
                "Keine Einstellungen oder Anmeldedaten wurden verändert."
        } else {
            "Weder Yeelight TCP 55443 noch eine MiIO-UDP-Antwort bestätigt. " +
                "IP, WLAN, LAN-Freigabe und Modell prüfen; fehlende Antwort beweist keinen Defekt."
        }
    }
}
