package io.github.lxsdd.yeelightloftdj

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class YeelightMusicChannelTest {
    @Test fun reverseChannelAcceptsTheKnownBulbAndSendsFramedRgbWithoutQuotaReplies() {
        val enableCalls = AtomicInteger()
        val disableCalls = AtomicInteger()
        val command = AtomicReference<String>()
        val workerError = AtomicReference<Throwable>()
        val workerDone = CountDownLatch(1)
        var worker: Thread? = null
        val channel = YeelightMusicChannel(
            "127.0.0.1",
            enableOnDevice = { host, port ->
                enableCalls.incrementAndGet()
                assertEquals("127.0.0.1", host)
                worker = thread {
                    try {
                        Socket(host, port).use { socket ->
                            socket.soTimeout = 2000
                            command.set(socket.getInputStream().bufferedReader().readLine())
                            workerDone.countDown()
                            // Closing the reverse connection must be visible
                            // to the bulb without another RGB or get_prop call.
                            assertEquals(-1, socket.getInputStream().read())
                        }
                    } catch (e: Throwable) { workerError.set(e); workerDone.countDown() }
                }
            },
            disableOnDevice = { disableCalls.incrementAndGet() }
        )
        try {
            channel.start("127.0.0.1")
            assertTrue(channel.isActive)
            channel.sendRgb(0x11AAFF)
            assertTrue(workerDone.await(2, TimeUnit.SECONDS))
            val json = JSONObject(command.get())
            assertEquals("set_rgb", json.getString("method"))
            assertEquals(0x11AAFF, json.getJSONArray("params").getInt(0))
            assertEquals("sudden", json.getJSONArray("params").getString(1))
            assertEquals(1, json.getInt("id"))
        } finally {
            channel.close()
            channel.close()
            worker?.join(2500)
        }
        assertNull(workerError.get()?.toString(), workerError.get())
        assertEquals(1, enableCalls.get())
        assertEquals(1, disableCalls.get())
        assertFalse(channel.isActive)
    }

    @Test fun interruptingReverseOutputSendsNoDuplicateLanDisable() {
        val disabled = AtomicInteger()
        val eof = CountDownLatch(1)
        val workerError = AtomicReference<Throwable>()
        var worker: Thread? = null
        val channel = YeelightMusicChannel(
            "127.0.0.1",
            enableOnDevice = { host, port ->
                worker = thread {
                    try {
                        Socket(host, port).use { peer ->
                            peer.soTimeout = 2000
                            assertEquals(-1, peer.getInputStream().read())
                            eof.countDown()
                        }
                    } catch (failure: Throwable) {
                        workerError.set(failure)
                        eof.countDown()
                    }
                }
            },
            disableOnDevice = { disabled.incrementAndGet() },
        )
        try {
            channel.start("127.0.0.1")
            assertTrue(channel.isActive)
            channel.interruptBlockedOutput()
            assertTrue("reverse socket did not close", eof.await(2, TimeUnit.SECONDS))
            assertEquals("interrupt itself must not use LAN budget", 0, disabled.get())
            assertFalse(channel.isActive)
            assertTrue(runCatching { channel.sendRgb(0x112233) }.isFailure)
        } finally {
            channel.close()
            channel.close()
            worker?.join(2000)
        }
        assertNull(workerError.get()?.toString(), workerError.get())
        assertEquals("exactly one deliberate set_music(0)", 1, disabled.get())
    }

    @Test fun failedEnableReleasesListenerAndAttemptsOneDisableOnly() {
        val disableCalls = AtomicInteger()
        val channel = YeelightMusicChannel(
            "127.0.0.1",
            enableOnDevice = { _, _ -> throw IllegalStateException("ACK verloren") },
            disableOnDevice = { disableCalls.incrementAndGet() }
        )
        assertTrue(runCatching { channel.start("127.0.0.1") }.isFailure)
        assertFalse(channel.isActive)
        channel.close()
        assertEquals(1, disableCalls.get())
        assertTrue(channel.alreadyDisabledSuccessfully)
    }

    @Test fun failedCleanupIsNotFalselyReportedAsAcknowledgedMusicDisable() {
        val disabled = AtomicInteger()
        val channel = YeelightMusicChannel(
            "127.0.0.1",
            enableOnDevice = { _, _ -> error("Handshake ACK missing") },
            disableOnDevice = {
                disabled.incrementAndGet()
                error("set_music(0) rejected")
            }
        )
        val failure = runCatching { channel.start("127.0.0.1") }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(1, disabled.get())
        assertFalse(channel.alreadyDisabledSuccessfully)
        assertEquals(1, failure!!.suppressed.size)
        channel.close() // no duplicated write
        assertEquals(1, disabled.get())
    }

    @Test fun unknownReversePeerIsRejectedBeforeAnyMusicCommandIsSent() {
        val disableCalls = AtomicInteger()
        var worker: Thread? = null
        val channel = YeelightMusicChannel(
            "127.0.0.2",
            enableOnDevice = { host, port ->
                worker = thread { Socket(host, port).use { } }
            },
            disableOnDevice = { disableCalls.incrementAndGet() }
        )
        assertTrue(runCatching { channel.start("127.0.0.1") }.isFailure)
        channel.close()
        worker?.join(2000)
        assertEquals(1, disableCalls.get())
        assertFalse(channel.isActive)
        assertTrue(runCatching { channel.sendRgb(0xFF0000) }.isFailure)
    }

    @Test fun invalidColorNeverTouchesTransport() {
        val channel = YeelightMusicChannel(
            "127.0.0.1", { _, _ -> }, { }
        )
        assertTrue(runCatching { channel.sendRgb(-1) }.isFailure)
        assertTrue(runCatching { channel.sendRgb(0x1000000) }.isFailure)
        channel.close()
    }
}
