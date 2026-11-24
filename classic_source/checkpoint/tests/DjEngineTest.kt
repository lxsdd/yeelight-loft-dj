package loftdj.classic

fun main() {
    class Fake : ClassicMusicCoordinator.Transport {
        val active = mutableMapOf<String, Boolean>()
        val frames = mutableListOf<Triple<String, Int, Int>>()
        var reject: String? = null
        override fun readMusic(id: String) = active[id] ?: false
        override fun requestStart(id: String): Boolean { active[id] = true; return true }
        override fun reverseConnected(id: String) = true
        override fun requestStop(id: String): Boolean { active[id] = false; return true }
        override fun closeReverse(id: String) {}
        override fun sendReverseFrame(id: String, generation: Long, rgb: Int, brightness: Int): Boolean {
            if (id == reject) return false
            frames.add(Triple(id, rgb, brightness)); return true
        }
    }
    val fake = Fake()
    val c = ClassicMusicCoordinator(fake)
    for (id in listOf("a","b","c","d","foreign")) c.register(id,true)
    fake.active["foreign"] = true
    val engine = ClassicDjEngine(c)
    val states = engine.select(listOf("a","b","c","d","foreign"))
    check(states["foreign"] == ClassicMusicCoordinator.State.UNKNOWN_OWNER)
    check(engine.selected().size == 4)
    var beats = 0
    for (t in 0L..4000L step 10L) {
        val onset = t % 460L < 30L
        val bands = if (onset) doubleArrayOf(.95,.65,.25,.1) else doubleArrayOf(.015,.015,.015,.015)
        val output = engine.audio(t,bands)
        if (output.beat) beats++
    }
    // Continuous flow adds bounded calm frames between beats; never an RPC flood.
    check(beats >= 7 && fake.frames.size > beats * 4)
    check(fake.frames.size <= 4 * 15) // 4 seconds / 300 ms + initial frame
    check(fake.frames.none { it.first == "foreign" })
    check(fake.frames.map { it.second }.distinct().size > 3)
    check(fake.frames.all { it.third in 12..100 })
    check(fake.frames.maxOf { it.third } >= 20)
    fake.reject = "b"
    var failed = false
    for (t in 4010L..5000L step 10L) {
        val onset = t % 460L < 30L
        val bands = if (onset) doubleArrayOf(.95,.65,.25,.1) else doubleArrayOf(.015,.015,.015,.015)
        if ("b" in engine.audio(t,bands).failed) failed = true
    }
    // A single uncertain reverse write now cancels the ENTIRE show.
    // Confirmed survivors are stopped once; the failed/foreign devices
    // are not sent speculative STOP commands.
    check(failed && engine.selected().isEmpty())
    check(engine.uncertainOutput()) { "Uncertain reverse frame lost before STOP" }
    check(listOf("a","c","d").all { fake.active[it] == false })
    check(fake.active["b"] == true && fake.active["foreign"] == true)
    val before = fake.frames.size
    check(!engine.audio(100L,doubleArrayOf(1.0,1.0,1.0,1.0)).beat)
    check(fake.frames.size == before)
    check(engine.stop().isEmpty()) // Survivors already stopped on failure.
    check(engine.uncertainOutput()) { "STOP must not erase uncertain peer" }
    check(listOf("a","c","d").all { fake.active[it] == false })
    check(fake.active["foreign"] == true && fake.active["b"] == true)
    check(engine.selected().isEmpty())
    check(engine.audio(1000L,doubleArrayOf(1.0,1.0,1.0,1.0)).sent.isEmpty())

    // A group handshake is fail-fast. A foreign middle peer must not cause
    // needless Music-start commands on later selected lamps.
    val earlyTransport = Fake()
    val earlyCoordinator = ClassicMusicCoordinator(earlyTransport)
    for (id in listOf("a", "foreign", "c", "d")) earlyCoordinator.register(id, true)
    earlyTransport.active["foreign"] = true
    val earlyEngine = ClassicDjEngine(earlyCoordinator)
    val earlyStates = earlyEngine.select(listOf("a", "foreign", "c", "d"))
    check(earlyStates.keys.toList() == listOf("a", "foreign"))
    check(earlyStates["foreign"] == ClassicMusicCoordinator.State.UNKNOWN_OWNER)
    check(earlyTransport.active["c"] != true && earlyTransport.active["d"] != true)
    check(earlyEngine.selected() == setOf("a"))
    check(earlyEngine.stop()["a"] == ClassicMusicCoordinator.State.IDLE)
    check(earlyTransport.active["foreign"] == true)

    // A user revocation between handshakes also skips every later lamp;
    // only the previously OWNED participant is eligible for STOP.
    val cancelledTransport = Fake()
    val cancelledCoordinator = ClassicMusicCoordinator(cancelledTransport)
    for (id in listOf("a", "b", "c")) cancelledCoordinator.register(id, true)
    val cancelledEngine = ClassicDjEngine(cancelledCoordinator)
    var admissionChecks = 0
    val cancelledStates = cancelledEngine.select(listOf("a", "b", "c")) {
        ++admissionChecks == 1
    }
    check(cancelledStates.keys.toList() == listOf("a"))
    check(cancelledEngine.selected() == setOf("a"))
    check(cancelledEngine.stop()["a"] == ClassicMusicCoordinator.State.IDLE)
    check(cancelledTransport.active["b"] != true && cancelledTransport.active["c"] != true)

    // Production DJ engine: equal RGB+brightness does not become 32 needless
    // Music writes every 300 ms. The first frame is always issued; identical
    // frames are held only inside the same confirmed reverse-session generation.
    for (total in listOf(1, 14, 32)) {
        val wire = Fake()
        val own = ClassicMusicCoordinator(wire)
        val bulbs = (1..total).map { "coalesce$it" }
        bulbs.forEach { own.register(it, true) }
        val dj = ClassicDjEngine(own)
        check(dj.setScene("BEAT"))
        check(dj.select(bulbs).values.all { it == ClassicMusicCoordinator.State.OWNED })
        val quiet = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        val first = dj.originalFrame(0L, quiet, false, 0.0, 0.0)
        check(first.sent == bulbs.toSet() && first.failed.isEmpty())
        for (at in listOf(300L, 600L, 1200L, 4500L)) {
            val held = dj.originalFrame(at, quiet, false, 0.0, 0.0)
            check(held.sent.isEmpty() && held.failed.isEmpty())
        }
        check(wire.frames.size == total) { "Unchanged frames were repeatedly written: $total" }
        // A stale timestamp cannot cause an early resend or a hidden retry.
        check(dj.originalFrame(4500L, quiet, true, 120.0, 0.9).sent.isEmpty())
        if (total > 1) wire.reject = bulbs[6]
        // Only a real next frame callback may reassert after 4.8 seconds.
        // A failed reassert is a real failed reverse write: entire show stops,
        // while the ambiguous peer is never sent speculative STOP.
        val reasserted = dj.originalFrame(4800L, quiet, false, 0.0, 0.0)
        if (total > 1) {
            check(reasserted.failed == setOf(bulbs[6]))
            check(dj.uncertainOutput() && dj.selected().isEmpty())
            check(wire.active[bulbs[6]] == true)
            check(bulbs.filter { it != bulbs[6] }.all { wire.active[it] == false })
            val writes = wire.frames.size
            check(dj.originalFrame(5100L, quiet, true, 120.0, 0.9).sent.isEmpty())
            check(wire.frames.size == writes)
            check(dj.stop().isEmpty())
        } else {
            check(reasserted.sent == bulbs.toSet() && reasserted.failed.isEmpty())
            val changed = dj.originalFrame(5100L, quiet, true, 120.0, 0.9)
            check(changed.sent == bulbs.toSet()) {
                "An actual beat colour change must bypass duplicate suppression"
            }
            check(dj.stop().values.all { it == ClassicMusicCoordinator.State.IDLE })
            // A new session on the SAME engine cannot inherit stale receipts.
            check(dj.setScene("BEAT"))
            check(dj.select(bulbs).values.all { it == ClassicMusicCoordinator.State.OWNED })
            check(dj.originalFrame(5200L, quiet, false, 0.0, 0.0).sent == bulbs.toSet())
            check(dj.stop().values.all { it == ClassicMusicCoordinator.State.IDLE })
        }
    }
    // For an ordered spatial show, some lamps visibly change while the
    // inactive tail remains at 1%. Only that unchanged tail is coalesced;
    // scanning continues across the full 14/32-member OWNED cohort.
    for (total in listOf(14, 32)) {
        val wire = Fake()
        val own = ClassicMusicCoordinator(wire)
        val bulbs = (1..total).map { "scan$it" }
        bulbs.forEach { own.register(it, true) }
        val dj = ClassicDjEngine(own)
        check(dj.setScene("SCANNER"))
        check(dj.select(bulbs).values.all { it == ClassicMusicCoordinator.State.OWNED })
        check(dj.clockTick(0L).sent == bulbs.toSet())
        val moved = dj.clockTick(300L)
        check(moved.failed.isEmpty() && moved.sent.isNotEmpty() && moved.sent.size < total) {
            "Spatial scanner must update its head but skip the unchanged fleet tail: $total"
        }
        check(dj.clockTick(300L).sent.isEmpty())
        check(dj.stop().values.all { it == ClassicMusicCoordinator.State.IDLE })
    }
    println("PASS DJ bridge: 4/14/32 lamps, duplicate-frame coalescing, bounded liveness reassertion, scanner head, fault isolation, stale audio")
}
