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
    check(failed && "b" !in engine.selected() && engine.selected().size == 3)
    val before = fake.frames.size
    check(!engine.audio(100L,doubleArrayOf(1.0,1.0,1.0,1.0)).beat)
    check(fake.frames.size == before)
    val stopped = engine.stop()
    check(stopped.size == 3 && stopped.values.all { it == ClassicMusicCoordinator.State.IDLE })
    check(fake.active["foreign"] == true && fake.active["b"] == true)
    check(engine.selected().isEmpty())
    check(!engine.audio(1000L,doubleArrayOf(1.0,1.0,1.0,1.0)).sent.any())
    println("PASS DJ bridge: 4-lamp, quiet flow, rate-bound HSV/brightness, foreign owner, failure isolation, stop, stale audio")
}
