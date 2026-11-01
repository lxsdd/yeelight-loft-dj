package io.github.lxsdd.yeelightloftdj

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
        @Volatile var lastActiveNanos = System.nanoTime()
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        val output = socket.getOutputStream()
        fun close() { runCatching { socket.close() } }
    }
    private val sessions = ConcurrentHashMap<Key, Session>()
    // Important: do not retain a lamp's one of only four LAN slots while
    // the user reads the screen or switches to the official Yeelight app.
    private val sweeper = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "yeelight-idle-tcp").apply { isDaemon = true }
    }
    init {
        sweeper.scheduleWithFixedDelay({ runCatching { closeIdle(8_000L) } },
            2, 2, TimeUnit.SECONDS)
    }

    fun closeIdle(maxIdleMs: Long) {
        val now = System.nanoTime()
        sessions.entries.toList().forEach { (key, session) ->
            if ((now - session.lastActiveNanos) / 1_000_000L >= maxIdleMs) {
                synchronized(session) {
                    if ((System.nanoTime() - session.lastActiveNanos) / 1_000_000L >= maxIdleMs)
                        invalidate(key, session)
                }
            }
        }
    }

    fun request(ip: String, port: Int, id: Int, method: String, params: List<Any>): JSONObject {
        require(port in 1..65535 && id > 0)
        val key = Key(ip,port)
        val session = sessions.computeIfAbsent(key) { Session(connect(ip,port)) }
        synchronized(session) {
            session.lastActiveNanos = System.nanoTime()
            try {
                val payload = JSONObject().put("id",id).put("method",method)
                    .put("params", JSONArray(params)).toString() + "\r\n"
                session.output.write(payload.toByteArray(StandardCharsets.UTF_8))
                session.output.flush()
                repeat(24) {
                    val line = session.reader.readLine() ?: error("TCP-Verbindung vom Gerät beendet")
                    val reply = JSONObject(line)
                    if (reply.has("id") && reply.optInt("id",-1) == id) {
                        session.lastActiveNanos = System.nanoTime()
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
        sessions.entries.toList().forEach { (key, session) ->
            synchronized(session) { invalidate(key, session) }
        }
    }

    fun openCount(): Int = sessions.size
}
