package loftdj.classic

/** In-memory only: STOP during four-device startup must prevent subsequent starts. */
fun main() {
    class PausingTransport : ClassicMusicCoordinator.Transport {
        val active = mutableMapOf<String, Boolean>()
        val writes = mutableListOf<String>()
        var stillAllowed = true
        override fun readMusic(id: String): Boolean? = active[id] ?: false
        override fun requestStart(id: String): Boolean {
            writes.add("start:$id")
            active[id] = true
            if (id == "one") stillAllowed = false // STOP while first handshake is in flight
            return true
        }
        override fun reverseConnected(id: String) = true
        override fun requestStop(id: String): Boolean {
            writes.add("stop:$id")
            active[id] = false
            return true
        }
        override fun closeReverse(id: String) { writes.add("close:$id") }
        override fun sendReverseFrame(id: String, generation: Long, rgb: Int, brightness: Int) = true
    }
    val transport = PausingTransport()
    val coordinator = ClassicMusicCoordinator(transport)
    val ids = listOf("one", "two", "three", "four")
    ids.forEach { coordinator.register(it, true) }
    val engine = ClassicDjEngine(coordinator)
    val states = engine.select(ids) { transport.stillAllowed }
    check(states.keys.toList() == listOf("one"))
    check(states.values.single() == ClassicMusicCoordinator.State.OWNED)
    check(engine.selected() == setOf("one"))
    check(transport.writes == listOf("start:one"))
    check(coordinator.snapshot().filter { it.id != "one" }.all { it.state == ClassicMusicCoordinator.State.IDLE })
    val stopped = engine.stop()
    check(stopped["one"] == ClassicMusicCoordinator.State.IDLE)
    check(transport.writes == listOf("start:one", "stop:one", "close:one"))
    check(engine.selected().isEmpty())
    val none = PausingTransport()
    val empty = ClassicMusicCoordinator(none)
    ids.forEach { empty.register(it, true) }
    check(ClassicDjEngine(empty).select(ids) { false }.isEmpty())
    check(none.writes.isEmpty())
    try {
        empty.startGroup(listOf("one", "unregistered")) { false }
        error("unregistered target accepted")
    } catch (_: IllegalArgumentException) {
        check(none.writes.isEmpty())
    }
    println("PASS Classic startup cancellation: no subsequent lamp start; confirmed own-session cleanup")
}
