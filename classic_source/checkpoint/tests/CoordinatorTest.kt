package loftdj.classic

class Fake : ClassicMusicCoordinator.Transport {
    val active = mutableMapOf<String, Boolean>()
    val connected = mutableMapOf<String, Boolean>()
    val writes = mutableListOf<String>()
    override fun readMusic(id: String) = active[id]
    override fun requestStart(id: String): Boolean { writes += "start:$id"; active[id] = true; return true }
    override fun reverseConnected(id: String) = connected[id] == true
    override fun requestStop(id: String): Boolean { writes += "stop:$id"; active[id] = false; return true }
    override fun closeReverse(id: String) { writes += "close:$id" }
    override fun sendReverseFrame(id: String, generation: Long, rgb: Int, brightness: Int): Boolean { writes += "frame:$id:$generation:$rgb:$brightness"; return true }
}
fun checkState(actual: ClassicMusicCoordinator.State, expected: ClassicMusicCoordinator.State) { check(actual == expected) { "$actual != $expected" } }
fun main() {
    val t = Fake(); val c = ClassicMusicCoordinator(t)
    (1..4).forEach { c.register("bulb$it", true); t.active["bulb$it"] = false; t.connected["bulb$it"] = true }
    check(c.startGroup((1..4).map { "bulb$it" }).values.all { it == ClassicMusicCoordinator.State.OWNED })
    check(t.writes.count { it.startsWith("start:") } == 4)
    val old = c.snapshot().first().generation
    c.disconnected("bulb1", old - 1); checkState(c.snapshot().first().state, ClassicMusicCoordinator.State.OWNED)
    c.disconnected("bulb1", old); checkState(c.snapshot().first().state, ClassicMusicCoordinator.State.QUARANTINED)
    checkState(c.stop("bulb1"), ClassicMusicCoordinator.State.QUARANTINED)
    check(t.writes.none { it == "stop:bulb1" })
    check(t.writes.count { it == "close:bulb1" } == 1)
    checkState(c.stop("bulb2"), ClassicMusicCoordinator.State.IDLE)
    check(t.writes.contains("stop:bulb2"))
    c.register("couch", true); t.active["couch"] = true
    checkState(c.start("couch"), ClassicMusicCoordinator.State.UNKNOWN_OWNER)
    checkState(c.confirmRecovery("couch", true), ClassicMusicCoordinator.State.UNKNOWN_OWNER)
    check(t.writes.none { it.contains("couch") })
    t.active["couch"] = false
    checkState(c.confirmRecovery("couch", false), ClassicMusicCoordinator.State.UNKNOWN_OWNER)
    checkState(c.confirmRecovery("couch", true), ClassicMusicCoordinator.State.IDLE)
    c.register("unsupported", false); t.active["unsupported"] = false
    checkState(c.start("unsupported"), ClassicMusicCoordinator.State.IDLE)
    c.register("noReverse", true); t.active["noReverse"] = false
    checkState(c.start("noReverse"), ClassicMusicCoordinator.State.QUARANTINED)
    checkState(c.start("noReverse"), ClassicMusicCoordinator.State.QUARANTINED)
    check(t.writes.count { it == "start:noReverse" } == 1)
    check(t.writes.count { it == "close:noReverse" } == 1)
    c.register("unknown", true)
    checkState(c.start("unknown"), ClassicMusicCoordinator.State.QUARANTINED)
    check(t.writes.none { it == "start:unknown" })
    frameScenarios()
    extraScenarios()
    reverseExceptionScenario()
    groupStopScenario()
    cleanupRecoveryScenario()
    groupProgressSafetyScenario()
    println("PASS coordinator synthetic safety scenarios")
}

/** Production coordinator reports only confirmed reverse sockets. An optional
 * UI observer cannot orphan a Music session if it throws during callback.
 */
fun groupProgressSafetyScenario() {
    for (total in listOf(14,32)) {
        val ids=(1..total).map { "lamp$it" }
        val wire=Fake()
        val group=ClassicMusicCoordinator(wire)
        for (id in ids) {
            group.register(id,true); wire.active[id]=false; wire.connected[id]=true
        }
        val progress=mutableListOf<Int>()
        val states=group.startGroup(ids,onConfirmed={ count, expected ->
            check(expected==total); progress.add(count)
        })
        check(states.size==total && states.values.all { it==ClassicMusicCoordinator.State.OWNED })
        check(progress==(1..total).toList()) { "Only reverse-connected peers count" }
        check(group.stopGroup(ids).values.all { it==ClassicMusicCoordinator.State.IDLE })
        check(wire.writes.count { it.startsWith("stop:") }==total)
    }
    val ids=(1..14).map { "lamp$it" }
    val wire=Fake(); val group=ClassicMusicCoordinator(wire)
    for (id in ids) {
        group.register(id,true)
        wire.active[id]=(id==ids[6]); wire.connected[id]=true
    }
    val progress=mutableListOf<Int>()
    val partial=group.startGroup(ids,onConfirmed={ count, expected ->
        check(expected==14); progress.add(count)
    })
    check(progress==(1..6).toList())
    check(partial.size==7 &&
          partial[ids[6]]==ClassicMusicCoordinator.State.UNKNOWN_OWNER)
    check(wire.writes.none { it=="start:" + ids[6] || it=="start:" + ids[7] })
    check(group.stopGroup(ids).getValue(ids[6])==ClassicMusicCoordinator.State.UNKNOWN_OWNER)
    check(wire.writes.none { it=="stop:" + ids[6] })
    check(wire.writes.count { it.startsWith("stop:") }==6)

    for (error in listOf(0,1)) {
        val bad=Fake(); val c=ClassicMusicCoordinator(bad)
        for (id in ids) { c.register(id,true); bad.active[id]=false; bad.connected[id]=true }
        val states=c.startGroup(ids,onConfirmed={ count,_ ->
            if (count==3) {
                if (error==0) throw IllegalStateException("UI closed")
                else throw NoSuchMethodError("Optional status changed")
            }
        })
        check(states.size==14 && states.values.all { it==ClassicMusicCoordinator.State.OWNED })
        check(c.stopGroup(ids).values.all { it==ClassicMusicCoordinator.State.IDLE })
        check(bad.writes.count { it.startsWith("stop:") }==14)
    }
    for (error in listOf(0,1)) {
        val bad=Fake(); val c=ClassicMusicCoordinator(bad)
        for (id in ids) { c.register(id,true); bad.active[id]=false; bad.connected[id]=true }
        var tries=0
        val states=c.startGroup(ids,allowNextStart={
            ++tries
            if (tries==4) {
                if (error==0) throw IllegalStateException("Original owner disappeared")
                else throw NoSuchFieldError("OEM callback changed")
            }
            true
        })
        check(states.keys==ids.take(3).toSet())
        check(bad.writes.count { it.startsWith("start:") }==3)
        check(c.stopGroup(ids).values.all { it==ClassicMusicCoordinator.State.IDLE })
        check(bad.writes.count { it.startsWith("stop:") }==3)
    }
}

class FaultTransport : ClassicMusicCoordinator.Transport {
    val writes = mutableListOf<String>()
    var failStop = false
    var failClose = false
    var music = false
    override fun readMusic(id: String): Boolean? = music
    override fun requestStart(id: String): Boolean { writes += "start"; music = true; return true }
    override fun reverseConnected(id: String) = true
    override fun requestStop(id: String): Boolean { writes += "stop"; if (failStop) throw IllegalStateException("timeout"); music = false; return true }
    override fun closeReverse(id: String) { writes += "close"; if (failClose) throw IllegalStateException("close") }
    override fun sendReverseFrame(id: String, generation: Long, rgb: Int, brightness: Int): Boolean { writes += "frame"; return true }
}
fun extraScenarios() {
    val t = FaultTransport()
    val c = ClassicMusicCoordinator(t)
    c.register("a", true)
    checkState(c.start("a"), ClassicMusicCoordinator.State.OWNED)
    t.failStop = true
    checkState(c.stop("a"), ClassicMusicCoordinator.State.QUARANTINED)
    check(t.writes == listOf("start", "stop", "close"))
    checkState(c.confirmRecovery("a", true), ClassicMusicCoordinator.State.QUARANTINED)
    t.music = false
    checkState(c.confirmRecovery("a", true), ClassicMusicCoordinator.State.IDLE)
    checkState(c.start("a"), ClassicMusicCoordinator.State.OWNED)
    t.failStop = false; t.failClose = true
    checkState(c.stop("a"), ClassicMusicCoordinator.State.QUARANTINED)
    check(t.writes.count { it == "close" } == 2)
    val u = FaultTransport(); val other = ClassicMusicCoordinator(u)
    other.register("b", true); u.music = true
    checkState(other.start("b"), ClassicMusicCoordinator.State.UNKNOWN_OWNER)
    check(u.writes.isEmpty())
}

class ReverseFailureTransport : ClassicMusicCoordinator.Transport {
    val calls = mutableListOf<String>()
    override fun readMusic(id: String): Boolean? = false
    override fun requestStart(id: String): Boolean { calls += "start"; return true }
    override fun reverseConnected(id: String): Boolean { throw IllegalStateException("handshake") }
    override fun requestStop(id: String): Boolean { calls += "stop"; return true }
    override fun closeReverse(id: String) { calls += "close" }
    override fun sendReverseFrame(id: String, generation: Long, rgb: Int, brightness: Int): Boolean { calls += "frame"; return true }
}
fun reverseExceptionScenario() {
    val t = ReverseFailureTransport(); val c = ClassicMusicCoordinator(t)
    c.register("bulb", true)
    checkState(c.start("bulb"), ClassicMusicCoordinator.State.QUARANTINED)
    check(t.calls == listOf("start", "close"))
    checkState(c.confirmRecovery("bulb", false), ClassicMusicCoordinator.State.QUARANTINED)
}

fun groupStopScenario() {
    val t = Fake(); val c = ClassicMusicCoordinator(t)
    listOf("a", "b", "foreign").forEach { id ->
        c.register(id, true); t.active[id] = id == "foreign"; t.connected[id] = true
    }
    checkState(c.start("a"), ClassicMusicCoordinator.State.OWNED)
    checkState(c.start("b"), ClassicMusicCoordinator.State.OWNED)
    checkState(c.start("foreign"), ClassicMusicCoordinator.State.UNKNOWN_OWNER)
    val before = t.writes.toList()
    try { c.stopGroup(listOf("a", "missing")); error("unknown id accepted") }
    catch (_: IllegalArgumentException) { check(t.writes == before) }
    val stopped = c.stopGroup(listOf("a", "b", "foreign"))
    checkState(stopped.getValue("a"), ClassicMusicCoordinator.State.IDLE)
    checkState(stopped.getValue("b"), ClassicMusicCoordinator.State.IDLE)
    checkState(stopped.getValue("foreign"), ClassicMusicCoordinator.State.UNKNOWN_OWNER)
    check(t.writes.none { it == "stop:foreign" })
}

/** A failed local close is not repaired by observing music_on=0. */
fun cleanupRecoveryScenario() {
    val t = FaultTransport(); val c = ClassicMusicCoordinator(t)
    c.register("socket", true)
    checkState(c.start("socket"), ClassicMusicCoordinator.State.OWNED)
    t.failClose = true
    checkState(c.stop("socket"), ClassicMusicCoordinator.State.QUARANTINED)
    check(c.snapshot().single().cleanupPending)
    val before = t.writes.count { it == "start" }
    checkState(c.confirmRecovery("socket", true), ClassicMusicCoordinator.State.QUARANTINED)
    check(c.snapshot().single().cleanupPending)
    check(t.writes.count { it == "start" } == before)
    t.failClose = false
    checkState(c.confirmRecovery("socket", true), ClassicMusicCoordinator.State.IDLE)
    check(!c.snapshot().single().cleanupPending)
    checkState(c.start("socket"), ClassicMusicCoordinator.State.OWNED)

    val u = FaultTransport(); val other = ClassicMusicCoordinator(u)
    other.register("disconnect", true)
    checkState(other.start("disconnect"), ClassicMusicCoordinator.State.OWNED)
    u.failClose = true
    val gen = other.snapshot().single().generation
    other.disconnected("disconnect", gen)
    check(other.snapshot().single().cleanupPending)
    u.music = false
    checkState(other.confirmRecovery("disconnect", true), ClassicMusicCoordinator.State.QUARANTINED)
    u.failClose = false
    checkState(other.confirmRecovery("disconnect", true), ClassicMusicCoordinator.State.IDLE)
}

fun frameScenarios() {
    val t = Fake(); val c = ClassicMusicCoordinator(t)
    listOf("a", "b", "couch").forEach { c.register(it, true); t.active[it] = it == "couch"; t.connected[it] = true }
    checkState(c.start("a"), ClassicMusicCoordinator.State.OWNED)
    checkState(c.start("b"), ClassicMusicCoordinator.State.OWNED)
    checkState(c.start("couch"), ClassicMusicCoordinator.State.UNKNOWN_OWNER)
    val a = c.snapshot().first { it.id == "a" }.generation
    val b = c.snapshot().first { it.id == "b" }.generation
    check(!c.frame("couch", 1, 0xff0000, 100))
    check(!c.frame("a", a - 1, 0xff0000, 100))
    check(!c.frame("a", a, -1, 50))
    check(!c.frame("a", a, 0xabcdef, 0))
    check(c.frame("a", a, 0xabcdef, 50))
    check(c.frame("b", b, 0x123456, 75))
    check(t.writes.count { it.startsWith("frame:") } == 2)
    checkState(c.stop("a"), ClassicMusicCoordinator.State.IDLE)
    check(!c.frame("a", a, 0xabcdef, 50))
    check(c.frame("b", b, 0xffffff, 100))
    check(t.writes.none { it.startsWith("frame:couch") })
    checkState(c.start("a"), ClassicMusicCoordinator.State.OWNED)
    val newGen = c.snapshot().first { it.id == "a" }.generation
    check(newGen > a)
    check(!c.frame("a", a, 0xffffff, 100))
    check(c.frame("a", newGen, 0xffffff, 100))

    val f = FaultTransport(); val d = ClassicMusicCoordinator(f)
    d.register("fail", true)
    checkState(d.start("fail"), ClassicMusicCoordinator.State.OWNED)
    // Negative acknowledgements on the reverse channel quarantine only this lamp.
    val bad = object : ClassicMusicCoordinator.Transport by f {
        override fun sendReverseFrame(id: String, generation: Long, rgb: Int, brightness: Int) = false
    }
    val e = ClassicMusicCoordinator(bad)
    e.register("reject", true); f.music = false
    checkState(e.start("reject"), ClassicMusicCoordinator.State.OWNED)
    val gen = e.snapshot().single().generation
    check(!e.frame("reject", gen, 0xffffff, 100))
    checkState(e.snapshot().single().state, ClassicMusicCoordinator.State.QUARANTINED)
    check(!e.frame("reject", gen, 0xffffff, 100))
    check(f.writes.count { it == "close" } == 1)
}
