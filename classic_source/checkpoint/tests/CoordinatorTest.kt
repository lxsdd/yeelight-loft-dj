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
    strictRpcReplyContractScenarios()
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
    linkageFailureSafetyScenario()
    quotaFailureFleetScenarios()
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

/** OEM/Android LinkageError is not an Exception. Simulate it *after* a
 * physical Music start or reverse listener allocation, where propagation
 * without quarantine and cleanup could strand one or more real lamps.
 * Entirely in memory; no private OEM files or LAN requests.
 */
fun linkageFailureSafetyScenario() {
    for (total in listOf(14, 32)) for (phase in listOf("READ", "START", "HANDSHAKE", "CLOSE")) {
        val ids=(1..total).map { "bulb$it" }
        val failure=ids[6]
        val remote=ids.associateWith { false }.toMutableMap()
        val actions=mutableListOf<String>()
        var closeFault=phase=="CLOSE"
        val transport=object : ClassicMusicCoordinator.Transport {
            override fun readMusic(id: String): Boolean? {
                actions.add("read:$id")
                if (id==failure && phase=="READ") throw NoSuchMethodError("private fixture")
                return remote[id]
            }
            override fun requestStart(id: String): Boolean {
                actions.add("start:$id")
                remote[id]=true // Even a thrown ABI error may follow a remote side effect.
                if (id==failure && phase=="START") throw NoSuchFieldError("private fixture")
                return true
            }
            override fun reverseConnected(id: String): Boolean {
                actions.add("connect:$id")
                if (id==failure && phase=="HANDSHAKE") throw NoSuchMethodError("private fixture")
                if (id==failure && phase=="CLOSE") return false
                return true
            }
            override fun requestStop(id: String): Boolean {
                actions.add("stop:$id")
                remote[id]=false
                return true
            }
            override fun closeReverse(id: String) {
                actions.add("close:$id")
                if (id==failure && closeFault) throw NoSuchFieldError("private close")
            }
            override fun sendReverseFrame(id: String, generation: Long,
                                          rgb: Int, brightness: Int): Boolean {
                actions.add("frame:$id")
                return true
            }
        }
        val coordinator=ClassicMusicCoordinator(transport)
        ids.forEach { coordinator.register(it,true) }
        val progress=mutableListOf<Int>()
        val reached=coordinator.startGroup(ids,onConfirmed={ n, expected ->
            check(expected==total)
            progress.add(n)
        })
        check(reached.size==7 && reached[failure]==ClassicMusicCoordinator.State.QUARANTINED) {
            "ABI failure escaped or false all-owned acknowledgement: $phase/$total"
        }
        check(progress==(1..6).toList())
        check(ids.take(6).all { reached[it]==ClassicMusicCoordinator.State.OWNED })
        check(actions.none { action -> ids.drop(7).any { action.endsWith(":$it") } }) {
            "started unselected later member after linkage failure $phase"
        }
        check(actions.none { it=="stop:$failure" })
        check(remote[failure]==(phase!="READ"))
        val after=coordinator.stopGroup(ids)
        check(ids.take(6).all { after[it]==ClassicMusicCoordinator.State.IDLE })
        check(after[failure]==ClassicMusicCoordinator.State.QUARANTINED)
        check(actions.count { it.startsWith("stop:") }==6)
        check(actions.none { it.startsWith("frame:") })
        check(coordinator.start(failure)==ClassicMusicCoordinator.State.QUARANTINED)
        check(coordinator.confirmRecovery(failure,false)==ClassicMusicCoordinator.State.QUARANTINED)
        if (phase=="CLOSE") {
            check(coordinator.snapshot()[6].cleanupPending)
            closeFault=false
            remote[failure]=false // External read-only evidence, not an automatic command.
            check(coordinator.confirmRecovery(failure,true)==ClassicMusicCoordinator.State.IDLE)
            check(!coordinator.snapshot()[6].cleanupPending)
        } else if (phase!="READ") {
            check(coordinator.confirmRecovery(failure,true)==ClassicMusicCoordinator.State.QUARANTINED)
        }
    }
    // Linkage during OWNED reverse frame never provokes a speculative STOP.
    run {
        val wire=Fake()
        wire.active["frame"]=false; wire.connected["frame"]=true
        val failing=object : ClassicMusicCoordinator.Transport by wire {
            override fun sendReverseFrame(id: String, generation: Long,
                                          rgb: Int, brightness: Int): Boolean {
                throw NoSuchMethodError("stale reverse ABI")
            }
        }
        val c=ClassicMusicCoordinator(failing)
        c.register("frame",true)
        check(c.start("frame")==ClassicMusicCoordinator.State.OWNED)
        val token=c.snapshot().single().generation
        check(!c.frame("frame",token,0x224466,60))
        check(c.snapshot().single().state==ClassicMusicCoordinator.State.QUARANTINED)
        check(wire.writes.count { it=="close:frame" }==1)
        check(c.stop("frame")==ClassicMusicCoordinator.State.QUARANTINED)
        check(wire.writes.none { it=="stop:frame" })
        check(!c.frame("frame",token,0x224466,60))
    }
    // STOP itself may fail after being sent, so close the local listener but
    // never assert IDLE, re-issue STOP or clear an unknown remote Music owner.
    run {
        val wire=Fake()
        wire.active["stop"]=false; wire.connected["stop"]=true
        val failing=object : ClassicMusicCoordinator.Transport by wire {
            override fun requestStop(id: String): Boolean {
                wire.writes.add("stop:$id")
                throw NoSuchFieldError("remote state uncertain")
            }
        }
        val c=ClassicMusicCoordinator(failing)
        c.register("stop",true)
        check(c.start("stop")==ClassicMusicCoordinator.State.OWNED)
        check(c.stop("stop")==ClassicMusicCoordinator.State.QUARANTINED)
        check(wire.writes.count { it=="close:stop" }==1)
        check(wire.writes.count { it=="stop:stop" }==1)
        check(c.stop("stop")==ClassicMusicCoordinator.State.QUARANTINED)
        check(wire.writes.count { it=="stop:stop" }==1)
    }
    // Explicit recovery cannot consider a failing get_prop proof as music=off.
    run {
        val wire=Fake()
        wire.active["recover"]=true
        var badRead=false
        val failing=object : ClassicMusicCoordinator.Transport by wire {
            override fun readMusic(id: String): Boolean? {
                if (badRead) throw NoSuchMethodError("original read ABI drift")
                return wire.readMusic(id)
            }
        }
        val c=ClassicMusicCoordinator(failing)
        c.register("recover",true)
        check(c.start("recover")==ClassicMusicCoordinator.State.UNKNOWN_OWNER)
        wire.active["recover"]=false
        badRead=true
        check(c.confirmRecovery("recover",true)==ClassicMusicCoordinator.State.QUARANTINED)
        check(wire.writes.none { it.startsWith("start:") || it.startsWith("stop:") })
        check(c.confirmRecovery("recover",false)==ClassicMusicCoordinator.State.QUARANTINED)
        badRead=false
        check(c.confirmRecovery("recover",true)==ClassicMusicCoordinator.State.IDLE)
    }
    println("PASS 14/32 reverse Music ABI faults, OWNED rollback, stop quarantine, explicit recovery")
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

/** Real production budget + coordinator; deterministic 14/32 device cases. */
fun quotaFailureFleetScenarios() {
    val budget=ClassicLanRpcBudget()
    for(size in listOf(14,32)) {
        val ids=(1..size).map { "fleet-$size-peer-$it" }
        ids.forEach { id ->
            repeat(8) { check(budget.admit(id,1000)) }
            check(!budget.admit(id,1000) && !budget.admit(id,60999))
            check(budget.admit(id,61000))
            check(!budget.admit(id,5))
        }
        check(!budget.admit("",1))
        val signed=ClassicLanRpcBudget()
        check(signed.admit("signed", -120_000L))
        check(signed.admit("signed", -119_000L))
        check(!signed.admit("signed", -120_001L))
        val wrap=ClassicLanRpcBudget()
        repeat(8) { check(wrap.admit("wrap",Long.MIN_VALUE+5L)) }
        check(!wrap.admit("wrap",Long.MIN_VALUE+5L))
        check(wrap.admit("wrap",Long.MAX_VALUE-5L))
        check(ClassicYeelightMusicTransport.classifyMatchedRpcError(
          """{"id":4,"error":{"code":-1,"message":"client quota exceeded"}}""") ==
          ClassicLanFailure.Kind.DEVICE_QUOTA)
        check(ClassicYeelightMusicTransport.classifyMatchedRpcError(
          """{"id":4,"error":{"code":-1,"message":"busy"}}""") ==
          ClassicLanFailure.Kind.DEVICE_REJECTED)
        check(ClassicYeelightMusicTransport.classifyMatchedRpcError(
          """{"id":4,"result":["client quota exceeded"]}""")==null)
        val faults=listOf<Exception>(
          ClassicLanFailure(ClassicLanFailure.Kind.LOCAL_BUDGET),
          ClassicLanFailure(ClassicLanFailure.Kind.DEVICE_QUOTA),
          ClassicLanFailure(ClassicLanFailure.Kind.DEVICE_REJECTED),
          java.net.SocketTimeoutException("injected"),
          java.io.IOException("injected"))
        val kinds=listOf(ClassicMusicCoordinator.Failure.LOCAL_BUDGET,
          ClassicMusicCoordinator.Failure.DEVICE_QUOTA,
          ClassicMusicCoordinator.Failure.DEVICE_REJECTED,
          ClassicMusicCoordinator.Failure.TIMEOUT,
          ClassicMusicCoordinator.Failure.NETWORK_UNCERTAIN)
        for ((fault,kind) in faults.zip(kinds)) {
            val active=mutableMapOf<String,Boolean>()
            val writes=mutableListOf<String>()
            val failed=ids[6]
            val wire=object:ClassicMusicCoordinator.Transport {
                override fun readMusic(id:String):Boolean?=active[id]?:false
                override fun requestStart(id:String):Boolean {
                    writes.add("start:$id")
                    if(id==failed) throw fault
                    active[id]=true;return true
                }
                override fun reverseConnected(id:String)=true
                override fun requestStop(id:String):Boolean {
                    writes.add("stop:$id");active[id]=false;return true
                }
                override fun closeReverse(id:String) { writes.add("close:$id") }
                override fun sendReverseFrame(id:String,generation:Long,rgb:Int,brightness:Int)=true
            }
            val c=ClassicMusicCoordinator(wire)
            ids.forEach { c.register(it,true) }
            val started=c.startGroup(ids)
            check(started.size==7 && started[failed]==ClassicMusicCoordinator.State.QUARANTINED)
            check(c.snapshot()[6].failure==kind)
            check(ids.drop(7).none { "start:$it" in writes })
            check(c.stopGroup(ids).values.count { it==ClassicMusicCoordinator.State.IDLE }==size-1)
            check(ids.take(6).all { "stop:$it" in writes })
            check("stop:$failed" !in writes)
            check(c.confirmRecovery(failed,false)==ClassicMusicCoordinator.State.QUARANTINED)
            check(c.snapshot()[6].failure==kind)
        }
    }
}


/** The SAME production TCP reply verifier runs in every public Kotlin CI.
 * A partial/trailing/nested response can NEVER claim music_on=0 or a START ACK.
 */
private fun strictRpcReplyContractScenarios() {
    val rpc = ClassicYeelightMusicTransport
    check(rpc.correlatedReplyId("""{"id":4,"result":["0"]}""") == 4)
    check(rpc.correlatedReplyId(""" {"id":42, "result":["1"]} """) == 42)
    check(rpc.parseMusicReply("""{"id":4,"result":["0"]}""") == false)
    check(rpc.parseMusicReply("""{"id":4,"result":["1"]}""") == true)
    check(rpc.validatedOkReply("""{"id":4,"result":["ok"]}"""))
    for (bad in listOf(
        """{"id":4,"result":["0"]""",
        """{"id":4,"result":["0"],"extra":"ignored"}""",
        """{"id":4,"params":{"result":["0"]}}""",
        """{"id":4,"result":["0"],"result":["1"]}""",
        """{"id":4,"result":[0]}""",
        """{"id":4,"result":["ok"]}""",
        """{"result":["0"],"id":4}""",
        """{"id":4,"result":["0"],"error":{"code":-1}}"""
    )) check(rpc.parseMusicReply(bad) == null) { "Unsafe Music OFF admitted: $bad" }
    for (bad in listOf(
        """{"id":4,"result":["ok"]""",
        """{"id":4,"params":{"result":["ok"]}}""",
        """{"id":4,"result":["ok"],"other":true}""",
        """{"result":["ok"],"id":4}""",
        """{"id":4,"result":["0"]}"""
    )) check(!rpc.validatedOkReply(bad)) { "Unverified Music START ACK admitted: $bad" }
    for (bad in listOf(
        """{"method":"props","params":{"id":4,"result":["0"]}}""",
        """{"params":{"id":4},"method":"props"}""",
        """{"id":"4","result":["0"]}""",
        """{"not_id":4,"result":["0"]}""",
        """{"id":2147483648,"result":["0"]}"""
    )) check(rpc.correlatedReplyId(bad) == null) { "Nested/foreign RPC id admitted: $bad" }
}
