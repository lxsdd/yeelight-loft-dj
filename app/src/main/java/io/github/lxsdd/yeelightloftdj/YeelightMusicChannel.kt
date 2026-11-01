package io.github.lxsdd.yeelightloftdj

import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * One short-lived, explicitly armed Yeelight music-mode reverse TCP channel.
 *
 * The device connects BACK to the Android phone after set_music(1, host, port).
 * Commands on that socket do not carry the normal LAN command quota, but
 * firmware may not acknowledge them or publish property notifications.
 *
 * Never treat a successful socket write as physical state verification.
 * This class neither starts music mode automatically nor retries an ambiguous
 * command. It only accepts the known device's source IP.
 */
class YeelightMusicChannel(
    private val deviceIp: String,
    private val enableOnDevice: (String, Int) -> Unit,
    private val disableOnDevice: () -> Unit,
    private val listenerFactory: (String) -> ServerSocket = { host ->
        ServerSocket(0, 1, InetAddress.getByName(host))
    },
) : Closeable {
    private val lock = Any()
    private var socket: Socket? = null
    private var enabledAttempted = false
    private var closed = false
    private var nextId = 1

    fun start(localWifiIp: String) {
        require(localWifiIp.isNotBlank() && deviceIp.isNotBlank())
        synchronized(lock) {
            check(!closed && !enabledAttempted && socket == null) {
                "Music-Mode-Kanal kann nur einmal gestartet werden"
            }
        }
        val listener = listenerFactory(localWifiIp)
        try {
            listener.soTimeout = 4_000
            synchronized(lock) { enabledAttempted = true }
            // Listener MUST exist before telling the bulb to connect back.
            enableOnDevice(localWifiIp, listener.localPort)
            val candidate = listener.accept()
            if (candidate.inetAddress.hostAddress != deviceIp) {
                candidate.close()
                error("Music-Mode-Rückverbindung stammt nicht von der gewählten Lampe")
            }
            candidate.tcpNoDelay = true
            synchronized(lock) {
                check(!closed) { "Music-Mode wurde zwischenzeitlich beendet" }
                socket = candidate
            }
        } catch (failure: Exception) {
            // A missing ACK does not prove set_music failed: try exactly one
            // compensating disable and retain the original failure.
            runCatching { close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            listener.close()
        }
    }

    val isActive: Boolean
        get() = synchronized(lock) {
            !closed && socket?.isConnected == true && socket?.isClosed == false
        }

    /** One non-replayed write; no ACK or fake device-state confirmation. */
    fun sendRgb(rgb: Int) {
        require(rgb in 0..0xFFFFFF)
        synchronized(lock) {
            val channel = socket?.takeIf { !closed && it.isConnected && !it.isClosed }
                ?: error("Music-Mode-Rückkanal ist nicht verbunden")
            val request = JSONObject()
                .put("id", nextId++)
                .put("method", "set_rgb")
                .put("params", JSONArray(listOf(rgb, "sudden", 0)))
                .toString() + "\r\n"
            try {
                channel.getOutputStream().write(request.toByteArray(StandardCharsets.UTF_8))
                channel.getOutputStream().flush()
            } catch (failure: Exception) {
                // Do not transparently reroute to the normal quota-limited
                // connection: the lamp's actual mode may still be unknown.
                runCatching { channel.close() }
                throw failure
            }
        }
    }

    /** Close the reverse channel before restoring bulb state. */
    override fun close() {
        val shouldDisable: Boolean
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { socket?.close() }
            socket = null
            shouldDisable = enabledAttempted
            enabledAttempted = false
        }
        // A single explicit set_music(0) is safer than assuming a lost
        // connection always resets every firmware. Never retry automatically.
        if (shouldDisable) disableOnDevice()
    }
}
