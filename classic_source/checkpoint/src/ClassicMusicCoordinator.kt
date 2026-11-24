package loftdj.classic

/** Pure, per-device fail-closed state machine; no implicit retries or RGB fallback. */
class ClassicMusicCoordinator(private val transport: Transport) {
    interface Transport {
        fun readMusic(id: String): Boolean?
        fun requestStart(id: String): Boolean
        fun reverseConnected(id: String): Boolean
        fun requestStop(id: String): Boolean
        fun closeReverse(id: String)
        /** Reverse-channel only: implementors MUST NOT fall back to normal LAN RPC. */
        fun sendReverseFrame(id: String, generation: Long, rgb: Int, brightness: Int): Boolean
    }
    enum class State { IDLE, UNKNOWN_OWNER, STARTING, OWNED, STOPPING, QUARANTINED }
    enum class Failure { NONE, LOCAL_BUDGET, DEVICE_QUOTA, DEVICE_REJECTED,
        TIMEOUT, NETWORK_UNCERTAIN, OEM_ABI, MUSIC_UNKNOWN, FOREIGN_OWNER,
        REVERSE_UNCERTAIN, CLEANUP_UNCERTAIN }
    data class Device(val id: String, val musicSupported: Boolean, var state: State = State.IDLE,
                      var generation: Long = 0, var reason: String = "", var ownsSocket: Boolean = false,
                      var startAccepted: Boolean = false, var cleanupPending: Boolean = false,
                      var failure: Failure = Failure.NONE)
    private val devices = linkedMapOf<String, Device>()
    @Synchronized fun register(id: String, musicSupported: Boolean) {
        require(id.isNotBlank() && id !in devices)
        devices[id] = Device(id, musicSupported)
    }
    @Synchronized fun snapshot(): List<Device> = devices.values.map { it.copy() }
    @Synchronized fun start(id: String): State {
        val d = device(id)
        if (!d.musicSupported || d.state != State.IDLE) return d.state
        d.generation++
        d.failure = Failure.NONE
        d.state = State.STARTING
        val active = safe(d, "read before start") { transport.readMusic(id) }
        if (d.state == State.QUARANTINED) return d.state
        if (active == null) return quarantine(d, "music state unavailable")
        if (active) {
            d.state = State.UNKNOWN_OWNER
            d.failure = Failure.FOREIGN_OWNER
            d.reason = "music_on=1 is not ownership; do not take over another session"
            return d.state
        }
        val ack = safe(d, "start") { transport.requestStart(id) }
        if (d.state == State.QUARANTINED) {
            // requestStart can throw AFTER opening a reverse listener. Close local resources
            // even though the remote music state remains uncertain. Never send blind STOP.
            closeTracked(d)
            return d.state
        }
        if (ack != true) {
            closeTracked(d)
            return quarantine(d, "start not acknowledged; completion uncertain", Failure.REVERSE_UNCERTAIN)
        }
        d.startAccepted = true
        val connected = safe(d, "reverse handshake") { transport.reverseConnected(id) }
        if (d.state == State.QUARANTINED) {
            // reverseConnected may throw after allocating a socket.
            closeTracked(d)
            return d.state
        }
        if (connected != true) {
            // The start ACK already changed the remote state. Close any partially
            // established local reverse socket; NEVER send a speculative stop.
            val closed = closeTracked(d)
            return quarantine(d, if (closed) "reverse channel not confirmed; remote music state uncertain"
                                else "reverse channel and local cleanup failed; remote music state uncertain",
                                Failure.REVERSE_UNCERTAIN)
        }
        d.ownsSocket = true
        d.state = State.OWNED
        d.reason = ""
        return d.state
    }
    /** Generation-fenced output: stale audio callbacks cannot write after stop/restart. */
    @Synchronized fun frame(id: String, generation: Long, rgb: Int, brightness: Int): Boolean {
        val d = device(id)
        if (d.state != State.OWNED || !d.ownsSocket || d.cleanupPending || d.generation != generation) return false
        if (rgb !in 0..0xffffff || brightness !in 1..100) return false
        val ack = safe(d, "reverse frame") { transport.sendReverseFrame(id, generation, rgb, brightness) }
        if (d.state == State.QUARANTINED) {
            // An uncertain frame outcome invalidates this session; local cleanup only.
            closeTracked(d)
            d.ownsSocket = false
            return false
        }
        if (ack != true) {
            closeTracked(d)
            d.ownsSocket = false
            quarantine(d, "reverse frame not acknowledged; outcome uncertain", Failure.REVERSE_UNCERTAIN)
            return false
        }
        return true
    }
    /** Stop only our confirmed reverse session; always try to close the socket exactly once. */
    @Synchronized fun stop(id: String): State {
        val d = device(id)
        if (d.state != State.OWNED || !d.ownsSocket) return d.state
        d.state = State.STOPPING
        val ack = safe(d, "stop") { transport.requestStop(id) }
        val close = closeTracked(d)
        d.ownsSocket = false
        if (d.state == State.QUARANTINED) return d.state
        if (!close) return quarantine(d, "reverse close failed")
        if (ack != true) return quarantine(d, "stop not acknowledged")
        val active = safe(d, "read after stop") { transport.readMusic(id) }
        if (d.state == State.QUARANTINED) return d.state
        if (active != false) return quarantine(d, "music not confirmed off")
        d.startAccepted = false
        d.state = State.IDLE
        d.failure = Failure.NONE
        d.reason = ""
        return d.state
    }
    /** Explicit local approval cannot claim an unknown or still-active foreign session. */
    @Synchronized fun confirmRecovery(id: String, userApproved: Boolean): State {
        val d = device(id)
        if (!userApproved || d.state !in setOf(State.UNKNOWN_OWNER, State.QUARANTINED)) return d.state
        if (d.ownsSocket) return d.state
        // A failed close leaves a potentially live local reverse listener. A remote
        // music_on=0 read alone must never clear that local resource uncertainty.
        if (d.cleanupPending && !closeTracked(d)) {
            return quarantine(d, "local reverse cleanup still unconfirmed")
        }
        val active = try { transport.readMusic(id) } catch (e: Exception) {
            quarantine(d, "transport error during recovery read: ${e.javaClass.simpleName}")
            return d.state
        } catch (e: LinkageError) {
            // OEM/API ABI drift is an uncertain transport outcome, not a
            // reason to clear quarantine or send any automatic command.
            quarantine(d, "transport ABI error during recovery read")
            return d.state
        }
        if (active != false) {
            d.reason = "firmware is active or unknown; no automatic takeover"
            return d.state
        }
        d.startAccepted = false
        d.state = State.IDLE
        d.failure = Failure.NONE
        d.reason = ""
        return d.state
    }
    /** Disconnect callback is generation fenced; no stop or automatic restart on uncertainty. */
    @Synchronized fun disconnected(id: String, generation: Long) {
        val d = device(id)
        if (d.generation != generation || d.state != State.OWNED) return
        d.ownsSocket = false
        val closed = closeTracked(d)
        quarantine(d, if (closed) "owned reverse socket disconnected; remote music state unknown"
                      else "disconnect cleanup failed; remote and local states unknown", Failure.REVERSE_UNCERTAIN)
    }
    /** Stop each confirmed own session independently; never stop unknown/foreign owners. */
    @Synchronized fun stopGroup(ids: List<String>): Map<String, State> {
        require(ids.distinct().size == ids.size)
        // Validate before any command to avoid partial group side effects on bad IDs.
        ids.forEach { device(it) }
        return ids.associateWith { stop(it) }
    }
    /** Check for cancellation before each additional participant, not just once.
     * A stop may arrive during a previous reverse-channel handshake.
     * Already confirmed OWNED members remain visible to the caller for cleanup.
     */
    @Synchronized fun startGroup(
        ids: List<String>, onConfirmed: (Int, Int) -> Unit = { _, _ -> },
        allowNextStart: () -> Boolean = { true }
    ): Map<String, State> {
        require(ids.distinct().size == ids.size)
        ids.forEach { device(it) }
        val states = linkedMapOf<String, State>()
        var confirmed = 0
        for (id in ids) {
            // Revocation or a failed original-host callback must NOT discard
            // the receipt for already OWNED peers; caller rolls those back.
            val allowed = try { allowNextStart() }
                catch (_: RuntimeException) { false }
                catch (_: LinkageError) { false }
            if (!allowed) break
            val state = start(id)
            states[id] = state
            // A foreign owner, missing ACK or uncertain session revokes the
            // proposed show. Never needlessly open Music on later lamps.
            // Caller rolls back independently confirmed OWNED predecessors.
            if (state != State.OWNED) break
            // Increment only after the reverse socket, never just set_music ACK.
            ++confirmed
            // Progress cannot endanger already-owned physical Music sessions.
            try { onConfirmed(confirmed, ids.size) }
            catch (_: RuntimeException) { }
            catch (_: LinkageError) { }
        }
        return states
    }
    private fun device(id: String) = requireNotNull(devices[id]) { "unregistered device" }
    private fun quarantine(d: Device, reason: String,
                           failure: Failure = Failure.MUSIC_UNKNOWN): State {
        d.state = State.QUARANTINED
        if (d.failure == Failure.NONE) d.failure = failure
        d.reason = reason
        return d.state
    }
    private fun <T> safe(d: Device, operation: String, call: () -> T): T? = try { call() }
    catch (e: ClassicLanFailure) {
        val code = when (e.kind) {
            ClassicLanFailure.Kind.LOCAL_BUDGET -> Failure.LOCAL_BUDGET
            ClassicLanFailure.Kind.DEVICE_QUOTA -> Failure.DEVICE_QUOTA
            ClassicLanFailure.Kind.DEVICE_REJECTED -> Failure.DEVICE_REJECTED
        }
        quarantine(d, "transport refused during $operation", code)
        null
    } catch (_: java.net.SocketTimeoutException) {
        quarantine(d, "transport timeout during $operation", Failure.TIMEOUT)
        null
    } catch (_: Exception) {
        quarantine(d, "transport uncertain during $operation", Failure.NETWORK_UNCERTAIN)
        null
    } catch (_: LinkageError) {
        // A vendor ABI failure can follow a successful physical write.
        quarantine(d, "transport ABI error during $operation", Failure.OEM_ABI)
        null
    }
    private fun closeTracked(d: Device): Boolean = try {
        transport.closeReverse(d.id)
        d.cleanupPending = false
        true
    } catch (_: Exception) {
        d.cleanupPending = true
        if (d.failure == Failure.NONE) d.failure = Failure.CLEANUP_UNCERTAIN
        false
    } catch (_: LinkageError) {
        // Local socket resources may still be live after a failed OEM close.
        // Recovery must explicitly retry cleanup and verify music_on=0.
        d.cleanupPending = true
        if (d.failure == Failure.NONE) d.failure = Failure.CLEANUP_UNCERTAIN
        false
    }
}
