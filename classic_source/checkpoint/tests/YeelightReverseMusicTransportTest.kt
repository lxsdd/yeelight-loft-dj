package loftdj.classic

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private fun privateHost(): String? {
    val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
    while (interfaces.hasMoreElements()) {
        val it = interfaces.nextElement()
        if (!it.isUp || it.isLoopback) continue
        val addresses = it.inetAddresses
        while (addresses.hasMoreElements()) {
            val address = addresses.nextElement()
            if (address is Inet4Address && ClassicYeelightMusicTransport.validLanIp(address.hostAddress))
                return address.hostAddress
        }
    }
    return null
}

private class FakeBulb(private val host: String, private val musicAlreadyActive: Boolean = false,
                       private val rewriteResponse: ((String, String) -> String)? = null) : AutoCloseable {
    val listener = ServerSocket(0, 5, InetAddress.getByName(host))
    val commands = LinkedBlockingQueue<String>()
    val reverseFrames = LinkedBlockingQueue<String>()
    @Volatile private var active = musicAlreadyActive
    @Volatile private var live = true
    private var musicSocket: Socket? = null
    private val worker = thread(isDaemon=true, name="fake-color4") {
        while (live) {
            try {
                val socket = listener.accept()
                socket.use { s ->
                    val cmd = s.getInputStream().bufferedReader().readLine() ?: return@use
                    commands.offer(cmd)
                    val id = Regex("\"id\":(\\d+)").find(cmd)?.groupValues?.get(1) ?: "0"
                    val result: String = when {
                        cmd.contains("\"get_prop\"") -> if (active) "1" else "0"
                        cmd.contains("\"set_music\"") && cmd.contains("[1,") -> {
                            val port = Regex("\\[1,\\\"[^\\\"]+\\\",(\\d+)\\]").find(cmd)?.groupValues?.get(1)?.toInt() ?: 0
                            if (port > 0) {
                                active = true
                                thread(isDaemon=true) {
                                    try {
                                        Socket(host,port).use { reverse ->
                                            musicSocket=reverse
                                            reverse.getInputStream().bufferedReader().forEachLine { reverseFrames.offer(it) }
                                        }
                                    } catch (_: Exception) { }
                                }
                            }
                            "ok"
                        }
                        cmd.contains("\"set_music\"") && cmd.contains("[0]") -> { active = false; "ok" }
                        else -> "error"
                    }
                    val response = if (result=="ok") "{\"id\":$id,\"result\":[\"ok\"]}"
                       else if (result=="error") "{\"id\":$id,\"error\":{\"code\":-1}}"
                       else "{\"id\":$id,\"result\":[\"$result\"]}"
                    val answer = rewriteResponse?.invoke(cmd, response) ?: response
                    s.getOutputStream().write((answer+"\r\n").toByteArray(StandardCharsets.US_ASCII))
                }
            } catch (_: Exception) { if (!live) return@thread }
        }
    }
    override fun close() {
        live=false
        listener.close()
        musicSocket?.close()
        worker.join(2000)
    }
}

fun main() {
    check(ClassicYeelightMusicTransport.validLanIp("192.168.55.42"))
    check(ClassicYeelightMusicTransport.validLanIp("10.12.3.4"))
    check(ClassicYeelightMusicTransport.validLanIp("172.16.1.2"))
    for (bad in listOf("8.8.8.8","127.0.0.1","169.254.1.1","192.168.0.0","192.168.0.255",
        "192.168.0.001", "192.168.1.2:55443","somehost","192.168.1.999","224.0.0.1"))
        check(!ClassicYeelightMusicTransport.validLanIp(bad)) { bad }

    val host = privateHost()
    if (host == null) {
        println("SKIP Java TCP fixture: host has no private IPv4 interface")
        return
    }
    FakeBulb(host).use { bulb ->
        val transport = ClassicYeelightMusicTransport(host, setOf(host), tcpPort=bulb.listener.localPort, clockMs={ 1_000L })
        val c = ClassicMusicCoordinator(transport)
        c.register(host,true)
        check(c.start(host)==ClassicMusicCoordinator.State.OWNED) { c.snapshot().toString() }
        val generation = c.snapshot().single().generation
        check(c.frame(host,generation,0xff33ee,73))
        val frame = bulb.reverseFrames.poll(2,TimeUnit.SECONDS) ?: error("No reverse music frame")
        check(frame.contains("\"method\":\"set_scene\"")) { frame }
        check(frame.contains("[\"color\",16724974,73]")) { frame }
        check(!bulb.commands.any { it.contains("\"set_rgb\"") || it.contains("\"set_scene\"") })
        check(c.stop(host)==ClassicMusicCoordinator.State.IDLE) { c.snapshot().toString() }
        val actions=bulb.commands.toList().map { it.substringAfter("\"method\":").substringBefore(",") }
        check(actions.count { it.contains("set_music") }==2) { actions }
        check(actions.count { it.contains("get_prop") }==2) { actions }
    }
    FakeBulb(host,musicAlreadyActive=true).use { bulb ->
        val c = ClassicMusicCoordinator(ClassicYeelightMusicTransport(host,setOf(host),tcpPort=bulb.listener.localPort, clockMs={ 1_000L }))
        c.register(host,true)
        check(c.start(host)==ClassicMusicCoordinator.State.UNKNOWN_OWNER)
        check(bulb.commands.toList().none { it.contains("\"set_music\"") })
        check(c.stop(host)==ClassicMusicCoordinator.State.UNKNOWN_OWNER)
    }
    // The same production socket transport now rejects a new Music START if
    // only one RPC slot remains, rather than orphaning its mandatory STOP.
    // Prior exact-host fixtures spent 4 + 1 slots in this process.
    FakeBulb(host).use { bulb ->
        val transport = ClassicYeelightMusicTransport(host, setOf(host),
            tcpPort=bulb.listener.localPort, clockMs={ 1_000L })
        check(!transport.readMusic(host)!!)
        val c = ClassicMusicCoordinator(transport)
        c.register(host, true)
        check(c.start(host) == ClassicMusicCoordinator.State.QUARANTINED)
        check(c.snapshot().single().failure == ClassicMusicCoordinator.Failure.LOCAL_BUDGET)
        check(bulb.commands.toList().none { it.contains("\"set_music\"") })
    }
    // Real production socket, private host-only fake bulb: a partial or
    // nested status cannot be mistaken for music_on=0 and start a stranger's
    // session. Fresh synthetic clock windows prevent cross-fixture budget noise.
    for ((index, kind) in listOf("truncated", "nested", "foreign").withIndex()) {
        FakeBulb(host, rewriteResponse = { cmd, result ->
            if (!cmd.contains("\"get_prop\"")) result else when (kind) {
                "truncated" -> result.dropLast(1)
                "nested" -> """{"id":1,"params":{"result":["0"]}}"""
                else -> """{"method":"props","params":{"id":1,"result":["0"]}}"""
            }
        }).use { bulb ->
            val c = ClassicMusicCoordinator(ClassicYeelightMusicTransport(
                host, setOf(host), tcpPort=bulb.listener.localPort,
                clockMs={ 200_000L + index * 90_000L }))
            c.register(host,true)
            check(c.start(host) == ClassicMusicCoordinator.State.QUARANTINED)
            check(c.snapshot().single().failure == ClassicMusicCoordinator.Failure.NETWORK_UNCERTAIN)
            check(bulb.commands.toList().none { it.contains("\"set_music\"") }) {
                "Unsafe Music START following $kind property reply"
            }
        }
    }
    // Even a successful physical set_music request is NOT an accepted START
    // without a complete correlated OK. Never send speculative set_music(0).
    FakeBulb(host, rewriteResponse = { cmd, result ->
        if (cmd.contains("\"set_music\"") && cmd.contains("[1,")) result.dropLast(1)
        else result
    }).use { bulb ->
        val c = ClassicMusicCoordinator(ClassicYeelightMusicTransport(
            host,setOf(host),tcpPort=bulb.listener.localPort,clockMs={ 600_000L }))
        c.register(host,true)
        check(c.start(host) == ClassicMusicCoordinator.State.QUARANTINED)
        check(c.snapshot().single().failure == ClassicMusicCoordinator.Failure.NETWORK_UNCERTAIN)
        check(bulb.commands.toList().none { it.contains("\"set_music\"") && it.contains("[0]") })
    }
    println("PASS Yeelight Color4 private reverse TCP music mode, selection, RGB+brightness, stop, foreign owner, reserved shutdown budget")
}
