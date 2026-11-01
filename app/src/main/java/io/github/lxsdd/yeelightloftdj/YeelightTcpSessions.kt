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
    private val trace: (String) -> Unit = { event ->
        // Android logcat can be captured without rooting the phone.
        // No user data, RGB values, cloud tokens or credentials are logged.
        runCatching { android.util.Log.i("LoftDjLan", event) }
    },
    private val onFirmwareQuotaRejected: (String, String?) -> Unit = { _, _ -> },
    private val connect: (String, Int) -> Socket = { ip, port ->
        Socket().apply {
            connect(InetSocketAddress(ip, port), 1800)
            soTimeout = 1800
            keepAlive = true
        }
    },
) {
    private fun traceSafe(message: String) { runCatching { trace(message) } }
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
        val session = sessions.computeIfAbsent(key) {
            val opened = Session(connect(ip,port))
            traceSafe("event=connect target=.${ip.substringAfterLast('.')} port=$port")
            opened
        }
        synchronized(session) {
            session.lastActiveNanos = System.nanoTime()
            try {
                val payload = JSONObject().put("id",id).put("method",method)
                    .put("params", JSONArray(params)).toString() + "\r\n"
                traceSafe("event=send target=.${ip.substringAfterLast('.')} id=$id method=$method")
                session.output.write(payload.toByteArray(StandardCharsets.UTF_8))
                session.output.flush()
                repeat(24) {
                    val line = session.reader.readLine() ?: error("TCP-Verbindung vom Gerät beendet")
                    val reply = JSONObject(line)
                    if (reply.has("id") && reply.optInt("id",-1) == id) {
                        session.lastActiveNanos = System.nanoTime()
                        val category = if (reply.has("error")) "firmware-error" else "ack"
                        traceSafe("event=$category target=.${ip.substringAfterLast('.')} " +
                            "id=$id method=$method" +
                            if (reply.has("error")) " quota=${QuotaRecoveryPolicy.isQuotaRejection(
                                reply.optJSONObject("error")?.optString("message")
                            )}" else "")
                        if (reply.has("error")) {
                            // Release this TCP slot before notifying the
                            // per-IP safety gate. Every call path (including
                            // future code) must observe a firmware quota error
                            // even when the caller forgets to throw on JSON.
                            invalidate(key,session)
                            val firmwareMessage = reply.optJSONObject("error")
                                ?.optString("message", "Yeelight-Fehler")
                            if (QuotaRecoveryPolicy.isQuotaRejection(firmwareMessage)) {
                                onFirmwareQuotaRejected(ip, firmwareMessage)
                            }
                        }
                        return reply
                    }
                    // Firmware notifications are allowed; never confuse an
                    // asynchronous properties update with a command ACK.
                }
                error("Keine passende Antwort auf Yeelight-Befehl")
            } catch (ex: Exception) {
                traceSafe("event=transport-error target=.${ip.substringAfterLast('.')} " +
                    "id=$id method=$method type=${ex.javaClass.simpleName}")
                invalidate(key,session)
                throw ex
            }
        }
    }

    private fun invalidate(key: Key, session: Session) {
        sessions.remove(key, session)
        session.close()
        traceSafe("event=close target=.${key.ip.substringAfterLast('.')} port=${key.port}")
    }

    /** Close exactly this bulb's TCP slot, never adjacent bulbs or music sockets. */
    fun closeTarget(ip: String, port: Int) {
        val key = Key(ip,port)
        val session = sessions[key] ?: return
        synchronized(session) { invalidate(key, session) }
    }

    fun closeAll() {
        sessions.entries.toList().forEach { (key, session) ->
            synchronized(session) { invalidate(key, session) }
        }
    }

    fun openCount(): Int = sessions.size
}
