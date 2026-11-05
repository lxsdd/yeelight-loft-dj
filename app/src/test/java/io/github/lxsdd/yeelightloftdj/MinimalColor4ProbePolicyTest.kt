package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class MinimalColor4ProbePolicyTest {
    private val lamp = YeelightDevice(
        "0xfeed000011", "192.168.1.22", model = "yeelink.light.color4",
        methods = setOf("set_music", "set_rgb", "get_prop")
    )
    private fun frame(rms: Float = .2f, peak: Float = .4f) =
        MusicFrame(rms, peak, listOf(.4f,.2f,.2f,.1f,.1f,.1f),.3f,false,null,1000L)

    @Test fun exactSingleColor4AndMethodsRequired() {
        assertNull(MinimalColor4ProbePolicy.refusal(listOf(lamp), emptySet(),false,false,emptySet()))
        assertNotNull(MinimalColor4ProbePolicy.refusal(emptyList(),emptySet(),false,false,emptySet()))
        assertNotNull(MinimalColor4ProbePolicy.refusal(listOf(lamp,lamp.copy(ip="192.168.1.23")),
            emptySet(),false,false,emptySet()))
        assertNotNull(MinimalColor4ProbePolicy.refusal(listOf(lamp.copy(model="ceiling1")),
            emptySet(),false,false,emptySet()))
        assertNotNull(MinimalColor4ProbePolicy.refusal(listOf(lamp.copy(methods=emptySet())),
            emptySet(),false,false,emptySet()))
    }

    @Test fun quarantinePendingRecoveryAndPriorProbeRefuse() {
        assertNotNull(MinimalColor4ProbePolicy.refusal(listOf(lamp),setOf(lamp.ip),false,false,emptySet()))
        assertNotNull(MinimalColor4ProbePolicy.refusal(listOf(lamp),emptySet(),true,false,emptySet()))
        assertNotNull(MinimalColor4ProbePolicy.refusal(listOf(lamp),emptySet(),false,true,emptySet()))
        assertNotNull(MinimalColor4ProbePolicy.refusal(listOf(lamp),emptySet(),false,false,setOf(lamp.ip)))
    }

    @Test fun audioOnlyWithinEightSecondsAndOneSecondSpacing() {
        assertFalse(MinimalColor4ProbePolicy.canSend(null,1000,-1000,0))
        assertFalse(MinimalColor4ProbePolicy.canSend(frame(rms=.001f),1000,-1000,0))
        assertTrue(MinimalColor4ProbePolicy.canSend(frame(),1000,0,0))
        assertFalse(MinimalColor4ProbePolicy.canSend(frame(),1900,1000,1))
        assertTrue(MinimalColor4ProbePolicy.canSend(frame(),2000,1000,1))
        assertFalse(MinimalColor4ProbePolicy.canSend(frame(),8000,7000,7))
        assertFalse(MinimalColor4ProbePolicy.canSend(frame(),5000,1000,8))
    }

    @Test fun socketOnlyStopNeverIssuesNormalLanStopAndClosesPeer() {
        val normalStops = AtomicInteger()
        val sawEof = CountDownLatch(1)
        var peer: Thread? = null
        val channel = YeelightMusicChannel(
            deviceIp = "127.0.0.1",
            enableOnDevice = { host, port ->
                peer = thread {
                    Socket(host,port).use { connected ->
                        connected.soTimeout = 3000
                        if (connected.getInputStream().read() == -1) sawEof.countDown()
                    }
                }
            },
            disableOnDevice = { normalStops.incrementAndGet() },
            socketStopOnly = true,
        )
        try {
            channel.start("127.0.0.1")
            assertTrue(channel.isActive)
            channel.close()
            assertTrue("Reverse socket never closed", sawEof.await(3, TimeUnit.SECONDS))
            assertFalse(channel.isActive)
            assertEquals(0, normalStops.get())
            assertFalse("Socket close is not a firmware ACK", channel.alreadyDisabledSuccessfully)
        } finally {
            channel.close()
            peer?.join(2000)
        }
    }

    @Test fun rejectedStartStillNeverSendsNormalStop() {
        val stops = AtomicInteger()
        val channel = YeelightMusicChannel(
            deviceIp="127.0.0.1",
            enableOnDevice={ _, _ -> throw YeelightLan.RejectedCommand("quota exceeded") },
            disableOnDevice={ stops.incrementAndGet() }, socketStopOnly=true
        )
        assertTrue(runCatching { channel.start("127.0.0.1") }.isFailure)
        channel.close()
        assertEquals(0,stops.get())
        assertFalse(channel.alreadyDisabledSuccessfully)
    }
}
