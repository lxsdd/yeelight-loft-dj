package loftdj.classic

/**
 * Real source-bound JVM qualifications for the three ordered spatial effects.
 * No sockets, OEM binaries or devices; pure 14-position simulation plus
 * generation-owned 4-participant coordinator/engine integration.
 */
private class SpatialMockTransport : ClassicMusicCoordinator.Transport {
    val active = mutableMapOf<String, Boolean>()
    val calls = mutableListOf<String>()
    val sent = mutableListOf<Triple<String, Int, Int>>()
    override fun readMusic(id: String): Boolean? = active[id]
    override fun requestStart(id: String): Boolean {
        calls += "start:$id"; active[id] = true; return true
    }
    override fun reverseConnected(id: String) = true
    override fun requestStop(id: String): Boolean {
        calls += "stop:$id"; active[id] = false; return true
    }
    override fun closeReverse(id: String) { calls += "close:$id" }
    override fun sendReverseFrame(id: String, generation: Long, rgb: Int,
                                  brightness: Int): Boolean {
        check(generation > 0 && rgb in 0..0xFFFFFF && brightness in 1..100)
        sent.add(Triple(id, rgb, brightness))
        return true
    }
}

fun main() {
    val choreography = ClassicSpatialChoreography()
    val count = 14
    val modes = ClassicSpatialChoreography.Mode.entries
    check(modes.map { it.name } == listOf("SCANNER", "METEOR", "WAVE"))
    for (mode in modes) {
        val frames = (0L..9000L step 210L).map { time ->
            (0 until count).map { index ->
                choreography.frame(mode, index, count, time, .7, time % 840L == 0L)
            }
        }
        check(frames.flatten().all { it.rgb in 0..0xFFFFFF && it.brightness in 1..100 })
        check(frames.any { row -> row.map { it.brightness }.distinct().size >= 3 }) {
            "Spatial per-lamp intensity is required: $mode"
        }
        check(frames.map { row -> row.map { it.brightness } }.distinct().size >= 4) {
            "Spatial pattern must travel: $mode"
        }
        check(frames.any { row -> row.any { it.brightness == 1 } }) {
            "Unlit tails must be 1%, not a fictitious power off: $mode"
        }
        check(frames.map { row -> row.map { it.rgb } }.flatten().distinct().size == 1) {
            "A spatial scene has one explicit color identity: $mode"
        }
    }
    val scanner0=(0 until 14).map {
        choreography.frame(ClassicSpatialChoreography.Mode.SCANNER,it,14,0,.7,false).brightness
    }
    val scannerEnd=(0 until 14).map {
        choreography.frame(ClassicSpatialChoreography.Mode.SCANNER,it,14,2730,.7,false).brightness
    }
    check(scanner0.indexOf(scanner0.maxOrNull()) == 0)
    check(scannerEnd.indexOf(scannerEnd.maxOrNull()) == 13)
    val scannerReturn=(0 until 14).map {
        choreography.frame(ClassicSpatialChoreography.Mode.SCANNER,it,14,5460,.7,false).brightness
    }
    check(scannerReturn.indexOf(scannerReturn.maxOrNull()) == 0) { "Scanner must bounce back" }

    val meteor=(0 until 14).map {
        choreography.frame(ClassicSpatialChoreography.Mode.METEOR,it,14,165*5L,.7,false).brightness
    }
    check(meteor[5]>meteor[4] && meteor[4]>meteor[3] &&
          meteor[2] < meteor[4]) { "Meteor trail must decay behind head" }
    val wave=(0 until 14).map {
        choreography.frame(ClassicSpatialChoreography.Mode.WAVE,it,14,1000,.7,false).brightness
    }
    check(wave.toSet().size >= 5) { "Wave must vary spatial phase" }
    check((0 until 14).all {
        val weak=choreography.frame(ClassicSpatialChoreography.Mode.WAVE,it,14,1000,0.0,false)
        val loud=choreography.frame(ClassicSpatialChoreography.Mode.WAVE,it,14,1000,1.0,true)
        loud.brightness >= weak.brightness
    })
    // A genuine scene editor can tune colour, direction, tempo, trail and
    // brightness on all 14 simulation positions, without touching any lamp.
    val reverseChase = ClassicSpatialChoreography()
    reverseChase.configure(ClassicSpatialChoreography.Options(
        rgb = 0x123456, speedPercent = 200, reverse = true,
        trailLength = 2, minBrightness = 4, maxBrightness = 85))
    val reverseStart = (0 until 14).map { index ->
        reverseChase.frame(ClassicSpatialChoreography.Mode.SCANNER,index,14,0,.8,false)
    }
    check(reverseStart.indexOf(checkNotNull(reverseStart.maxByOrNull { it.brightness })) == 13) {
        "Reverse scanner must begin at last selected participant"
    }
    check(reverseStart.all { it.rgb == 0x123456 && it.brightness in 4..85 })
    val fastPhase = (0 until 14).map { index ->
        reverseChase.frame(ClassicSpatialChoreography.Mode.SCANNER,index,14,1050,.8,false).brightness
    }
    val slowPhase = (0 until 14).map { index ->
        choreography.frame(ClassicSpatialChoreography.Mode.SCANNER,index,14,1050,.8,false).brightness
    }
    check(fastPhase != slowPhase) { "Scene tempo setting must change actual geometry" }
    for (mode in modes) {
        check((0 until 14).all { i ->
            val v = reverseChase.frame(mode,i,14,1500,.8,true)
            v.rgb == 0x123456 && v.brightness in 4..85
        })
    }
    // Rainbow must change real per-lamp output, but no extra transport ownership.
    val rainbowChase = ClassicSpatialChoreography()
    rainbowChase.configure(ClassicSpatialChoreography.Options(
        rgb=0x123456, speedPercent=200, reverse=true, trailLength=4, rainbow=true))
    for (mode in modes) {
        val first = (0 until count).map {
            rainbowChase.frame(mode,it,count,1500,.8,true).rgb
        }
        val later = (0 until count).map {
            rainbowChase.frame(mode,it,count,3800,.8,true).rgb
        }
        check(first.distinct().size >= 10 && first != later) {
            "Spatial mode must have moving per-lamp rainbow output: $mode"
        }
        check(first.all { it in 0..0xFFFFFF })
        check(rainbowChase.frame(mode,13,count,1500,.8,true).rgb == first[13])
    }
    val invalidSettings = listOf(
        { ClassicSpatialChoreography.Options(rgb=-2) },
        { ClassicSpatialChoreography.Options(rgb=0x1000000) },
        { ClassicSpatialChoreography.Options(speedPercent=24) },
        { ClassicSpatialChoreography.Options(speedPercent=401) },
        { ClassicSpatialChoreography.Options(trailLength=0) },
        { ClassicSpatialChoreography.Options(trailLength=9) },
        { ClassicSpatialChoreography.Options(minBrightness=0) },
        { ClassicSpatialChoreography.Options(minBrightness=50,maxBrightness=50) },
        { ClassicSpatialChoreography.Options(maxBrightness=101) }
    )
    check(invalidSettings.all { runCatching { it() }.isFailure })
    for(mode in modes) {
        for(badIndex in listOf(-1,14)) {
            check(runCatching { choreography.frame(mode,badIndex,14,10,.1,false) }.isFailure)
        }
        check(runCatching { choreography.frame(mode,0,0,10,.1,false) }.isFailure)
        // 15 is now an ordinary valid fleet size, not a historical cap.
        check(runCatching { choreography.frame(mode,0,15,10,.1,false) }.isSuccess)
        check(runCatching { choreography.frame(mode,14,15,10,.1,false) }.isSuccess)
        check(runCatching { choreography.frame(mode,15,15,10,.1,false) }.isFailure)
        check(runCatching { choreography.frame(mode,0,4,-1,.1,false) }.isFailure)
        for(bad in listOf(-1.0,Double.NaN,Double.POSITIVE_INFINITY,1.1)) {
            check(runCatching { choreography.frame(mode,0,4,10,bad,false) }.isFailure)
        }
    }

    // Production-bound effect routing, with the same existing owned-only socket
    // gate as the prior four scenes. Never ask the foreign session to start.
    val transport=SpatialMockTransport()
    val coordinator=ClassicMusicCoordinator(transport)
    val ids=(1..4).map { "owned$it" }
    for(id in ids) { coordinator.register(id,true); transport.active[id]=false }
    coordinator.register("foreign",true); transport.active["foreign"]=true
    val engine=ClassicDjEngine(coordinator)
    check(engine.select(ids).values.all { it == ClassicMusicCoordinator.State.OWNED })
    check(engine.selected() == ids.toSet())
    check(transport.calls.count { it.startsWith("start:") } == 4)
    var now=0L
    for(mode in modes) {
        check(engine.setScene(mode.name))
        val before=transport.sent.size
        for(step in 0..8) {
            now+=320L
            engine.audio(now, doubleArrayOf(.8,.45,.2,.1))
        }
        val new=transport.sent.drop(before)
        check(new.isNotEmpty())
        check(new.map { it.first }.toSet() == ids.toSet())
        check(new.map { it.third }.distinct().size > 1)
        check(new.none { it.first=="foreign" })
        check(transport.calls.count { it.startsWith("start:") } == 4) {
            "Live scenes must not renegotiate"
        }
    }
    // Reconfigure an already-owned spatial effect with no new Music start or
    // foreign/extra participant, and no output from the setter itself.
    val beforeTuning = transport.sent.size
    check(engine.configureSpatial(0x123456,200,true,2,4,85))
    check(!engine.configureSpatial(-2,200,true,2,4,85))
    check(!engine.configureSpatial(0x123456,200,true,9,4,85))
    check(transport.sent.size == beforeTuning)
    check(engine.setScene("SCANNER"))
    now += 350L
    check(engine.clockTick(now).sent == ids.toSet())
    check(transport.sent.drop(beforeTuning).all {
        it.first in ids && it.second == 0x123456 && it.third in 4..85
    })
    check(transport.calls.count { it.startsWith("start:") } == 4)
    val beforeRainbow = transport.sent.size
    check(engine.configureSpatial(0x123456,200,true,2,4,85,true))
    now += 350L
    check(engine.clockTick(now).sent == ids.toSet())
    check(transport.sent.drop(beforeRainbow).map { it.second }.distinct().size >= 3)
    check(transport.calls.count { it.startsWith("start:") } == 4)
    // The *same production engine* renders independent clock ticks while the
    // owned music channel remains unchanged (no capture callback or new socket).
    check(engine.setScene("SCANNER"))
    var tickTime = now + 350L
    val beforeClock = transport.sent.size
    check(engine.clockTick(tickTime).sent == ids.toSet())
    check(engine.clockTick(tickTime).sent.isEmpty()) // replay must not write
    repeat(4) { tickTime += 350L; engine.clockTick(tickTime) }
    check(transport.sent.size >= beforeClock + 8)
    check(transport.sent.drop(beforeClock).all { it.first in ids })
    // A stale PCM callback after a clock tick retains telemetry but cannot
    // reset the renderer or overtake the newer scene output.
    val beforeStale = transport.sent.size
    check(engine.audio(tickTime - 12L, doubleArrayOf(.9,.2,.1,.1)).sent.isEmpty())
    check(transport.sent.size == beforeStale)
    // A duplicated/older *audio* timestamp used to reset the entire light
    // renderer and could rewind a newer timer tick. The new clock must not
    // regress, even if the PCM source discontinuously restarts behind it.
    check(engine.audio(now - 650L, doubleArrayOf(.9,.2,.1,.1)).sent.isEmpty())
    val afterRewind = tickTime + 350L
    check(engine.clockTick(afterRewind).sent == ids.toSet())
    check(transport.sent.drop(beforeStale).all { it.first in ids })
    tickTime = afterRewind
    // Only truly timed pulse may animate without new audio: beat-pulse and
    // ordinary BEAT/SPECTRUM explicitly refuse unclocked fake music.
    check(engine.setScene("PULSE"))
    check(engine.configurePulse(ClassicPulseScene.Options(beatSync = true)))
    tickTime += 350L
    check(engine.clockTick(tickTime).sent.isEmpty())
    check(engine.configurePulse(ClassicPulseScene.Options(beatSync = false, bpm = 90)))
    tickTime += 350L
    check(engine.clockTick(tickTime).sent == ids.toSet())
    check(engine.setScene("BEAT"))
    tickTime += 350L
    check(engine.clockTick(tickTime).sent.isEmpty())
    check(engine.setScene("SPECTRUM"))
    tickTime += 350L
    check(engine.clockTick(tickTime).sent.isEmpty())
    check(!engine.setScene("SCANNER;set_power"))
    check(!engine.setScene("UNSUPPORTED"))
    check(engine.setScene("PULSE"))
    check(engine.setScene("SPECTRUM"))
    check(engine.selected()==ids.toSet())
    engine.stop()
    check(engine.selected().isEmpty())
    tickTime += 350L
    check(engine.clockTick(tickTime).sent.isEmpty())
    check(transport.calls.count { it.startsWith("stop:") } == 4)
    check(transport.calls.none { it.contains("foreign") })
    // Dynamic fleet sizes are not protocol ceilings. The very same
    // OWNED-only production coordinator/engine must render all selected
    // members even after expanding beyond the original 4/14 samples.
    for (fleetSize in listOf(1, 5, 14, 15, 32, 64, 128)) {
        val fleetWire = SpatialMockTransport()
        val fleetCoordinator = ClassicMusicCoordinator(fleetWire)
        val fleetIds = (1..fleetSize).map { "selected-$it" }
        for (id in fleetIds) {
            fleetCoordinator.register(id, true)
            fleetWire.active[id] = false
        }
        fleetCoordinator.register("foreign-$fleetSize", true)
        fleetWire.active["foreign-$fleetSize"] = true
        val fleetEngine = ClassicDjEngine(fleetCoordinator)
        val states = fleetEngine.select(fleetIds)
        check(states.size == fleetSize && states.values.all {
            it == ClassicMusicCoordinator.State.OWNED
        })
        check(fleetEngine.selected() == fleetIds.toSet())
        for (mode in modes) {
            check(fleetEngine.setScene(mode.name))
            val tick = fleetEngine.clockTick(350L + modes.indexOf(mode) * 350L)
            check(tick.sent == fleetIds.toSet() && tick.failed.isEmpty()) {
                "Effect $mode did not reach every selected lamp for fleet size $fleetSize"
            }
        }
        check(fleetWire.sent.all { it.first in fleetIds })
        check(fleetWire.calls.none { it.contains("foreign-$fleetSize") })
        check(fleetWire.calls.count { it.startsWith("start:") } == fleetSize)
        val stopped = fleetEngine.stop()
        check(stopped.size == fleetSize && stopped.values.all {
            it == ClassicMusicCoordinator.State.IDLE
        })
        check(fleetWire.calls.count { it.startsWith("stop:") } == fleetSize)
        check(fleetEngine.clockTick(2000L).sent.isEmpty())
    }
    println("PASS dynamic participant counts 1/5/14/15/32/64/128, OWNED-only frames and stop")
    println("PASS Classic scanner/meteor/wave geometry, owned routing, no nonparticipants, live switching, stop and validation")
}
