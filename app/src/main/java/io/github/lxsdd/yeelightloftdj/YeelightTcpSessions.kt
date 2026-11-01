package io.github.lxsdd.yeelightloftdj

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Exactly one reusable TCP channel per lamp (IP+port) for *all* Yeelight
 * commands, including status reads, power verification, scenes and recovery.
 *
 * Only call from DeviceCommandGate.run(ip) (per-IP serialized). Sessions never
 * silently replay an ambiguous command after disconnect/timeout. Unsolicited
 * props notifications are skipped using the matching command ID.
 */
class YeelightTcpSessions(
    private val connect: (String, Int) -> Socket = { ip, port ->
        Socket().apply {
            connect(InetSocketAddress(ip, port), 1800)
            soTimeout = 1800
            keepAlive = true
        }
    },
) {
    private data class Key(val ip: String, val port: Int)
    private class Session(val socket: Socket) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        val output = socket.getOutputStream()
        fun close() { runCatching { socket.close() } }
    }
    private val sessions = ConcurrentHashMap<Key, Session>()

    fun request(ip: String, port: Int, id: Int, method: String, params: List<Any>): JSONObject {
        require(port in 1..65535 && id > 0)
        val key = Key(ip,port)
        val session = sessions.computeIfAbsent(key) { Session(connect(ip,port)) }
        synchronized(session) {
            try {
                val payload = JSONObject().put("id",id).put("method",method)
                    .put("params", JSONArray(params)).toString() + "\r\n"
                session.output.write(payload.toByteArray(StandardCharsets.UTF_8))
                session.output.flush()
                repeat(24) {
                    val line = session.reader.readLine() ?: error("TCP-Verbindung vom Gerät beendet")
                    val reply = JSONObject(line)
                    if (reply.has("id") && reply.optInt("id",-1) == id) {
                        if (reply.has("error")) {
                            // Explicit firmware rejections may leave the channel
                            // in an unknown state; release the connection now.
                            invalidate(key,session)
                        }
                        return reply
                    }
                    // Firmware notifications are allowed; never confuse an
                    // asynchronous properties update with a command ACK.
                }
                error("Keine passende Antwort auf Yeelight-Befehl")
            } catch (ex: Exception) {
                invalidate(key,session)
                throw ex
            }
        }
    }

    private fun invalidate(key: Key, session: Session) {
        sessions.remove(key, session)
        session.close()
    }

    fun closeAll() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    fun openCount(): Int = sessions.size
}
