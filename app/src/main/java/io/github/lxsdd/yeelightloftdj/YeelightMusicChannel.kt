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
    // Keep writes serialized without preventing close() from interrupting a
    // stalled TCP send. Java Socket.soTimeout does NOT bound OutputStream.write.
    private val sendLock = Any()
    private var socket: Socket? = null
    private var enabledAttempted = false
    @Volatile private var disableAcknowledged = false
    val alreadyDisabledSuccessfully: Boolean get() = disableAcknowledged
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
        var candidate: Socket? = null
        try {
            listener.soTimeout = 4_000
            synchronized(lock) { enabledAttempted = true }
            // Listener MUST exist before telling the bulb to connect back.
            enableOnDevice(localWifiIp, listener.localPort)
            val accepted = listener.accept()
            candidate = accepted
            if (accepted.inetAddress.hostAddress != deviceIp) {
                error("Music-Mode-Rückverbindung stammt nicht von der gewählten Lampe")
            }
            accepted.tcpNoDelay = true
            synchronized(lock) {
                check(!closed) { "Music-Mode wurde zwischenzeitlich beendet" }
                socket = accepted
                candidate = null // ownership transferred to this channel
            }
        } catch (failure: Exception) {
            runCatching { candidate?.close() }
            // A missing ACK does not prove set_music failed: try exactly one
            // compensating disable and retain the original failure.
            runCatching { close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            listener.close()
        }
    }

    /**
     * Close ONLY the reverse data socket to interrupt a blocked writer.
     * Retain enabledAttempted so a subsequent close() performs precisely
     * one quota-accounted set_music(0); no command is sent here.
     */
    fun interruptBlockedOutput() {
        synchronized(lock) {
            runCatching { socket?.close() }
        }
    }

    val isActive: Boolean
        get() = synchronized(lock) {
            !closed && socket?.isConnected == true && socket?.isClosed == false
        }

    /** One non-replayed write; no ACK or fake device-state confirmation. */
    fun sendRgb(rgb: Int) {
        require(rgb in 0..0xFFFFFF)
        synchronized(sendLock) {
            val channel: Socket
            val request: String
            synchronized(lock) {
                channel = socket?.takeIf { !closed && it.isConnected && !it.isClosed }
                    ?: error("Music-Mode-Rückkanal ist nicht verbunden")
                request = JSONObject()
                    .put("id", nextId++)
                    .put("method", "set_rgb")
                    .put("params", JSONArray(listOf(rgb, "sudden", 0)))
                    .toString() + "\r\n"
            }
            try {
                // No lock held that stopMusicMode / close() needs. Closing the
                // socket from another thread interrupts a blocked write.
                channel.getOutputStream().write(request.toByteArray(StandardCharsets.UTF_8))
                channel.getOutputStream().flush()
            } catch (failure: Exception) {
                // Never replay an ambiguous music frame on the quota-limited
                // control channel: firmware might have processed it already.
                runCatching { channel.close() }
                throw failure
            }
        }
    }

    /**
     * For a normal stop, explicitly disable music mode WHILE the reverse
     * socket is still connected, then close it. The Yeelight spec treats
     * set_music(0) and closing the reverse socket as ALTERNATIVE stop paths.
     * Closing first can make older firmware reject a subsequent stop as
     * "invalid params" even though the music session has already ended.
     *
     * On an unacknowledged stop, close the reverse socket as a documented
     * best-effort fallback, but NEVER claim that physical recovery succeeded.
     * The caller retains its durable unresolved marker; no automatic retry.
     */
    override fun close() {
        val shouldDisable: Boolean
        val previousSocket: Socket?
        synchronized(lock) {
            if (closed) return
            closed = true // no new RGB writes after stop begins
            previousSocket = socket
            socket = null
            shouldDisable = enabledAttempted
            enabledAttempted = false
        }
        try {
            if (shouldDisable) {
                disableOnDevice()
                disableAcknowledged = true
            }
        } finally {
            // The reverse stream is a fallback stop even on rejected/timeout
            // set_music(0); it is NOT a verified firmware ACK.
            runCatching { previousSocket?.close() }
        }
    }
}
