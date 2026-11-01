package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class YeelightTcpSessionsTest {
    @Test fun reusesOneConnectionAndSkipsUnsolicitedNotifications() {
        ServerSocket(0).use { server ->
            val accepted = AtomicInteger()
            val worker = thread {
                server.accept().use { socket ->
                    accepted.incrementAndGet()
                    val rd=socket.getInputStream().bufferedReader()
                    val out=socket.getOutputStream()
                    repeat(2) {
                        val raw=rd.readLine()
                        assertTrue(raw.contains("\"method\""))
                        val id=Regex("\"id\":(\\d+)").find(raw)!!.groupValues[1]
                        out.write(("{\"method\":\"props\",\"params\":{\"power\":\"on\"}}\r\n" +
                            "{\"id\":$id,\"result\":[\"ok\"]}\r\n").toByteArray())
                        out.flush()
                    }
                }
            }
            val events = mutableListOf<String>()
            val tcp=YeelightTcpSessions(
                trace = { events.add(it) },
                connect = { ip,port -> Socket(ip,port).apply { soTimeout=1800 } }
            )
            try {
                assertEquals("ok",tcp.request("127.0.0.1",server.localPort,11,"set_rgb",
                    listOf(123,"sudden",0)).getJSONArray("result").getString(0))
                assertEquals("ok",tcp.request("127.0.0.1",server.localPort,12,"set_bright",
                    listOf(80,"sudden",0)).getJSONArray("result").getString(0))
                assertEquals(1,accepted.get())
                assertEquals(1,tcp.openCount())
                assertEquals(1, events.count { it.startsWith("event=connect ") })
                assertEquals(2, events.count { it.startsWith("event=send ") })
                assertEquals(2, events.count { it.startsWith("event=ack ") })
                assertTrue(events.all { "0xFF0000" !in it && "password" !in it })
            } finally {
                tcp.closeAll()
                worker.join(2500)
                assertFalse(worker.isAlive)
            }
        }
    }

    @Test fun neverReplaysTimedOutWriteAndClosesDeadChannel() {
        ServerSocket(0).use { server ->
            val received=AtomicInteger()
            val worker=thread {
                server.accept().use {socket->
                    socket.getInputStream().bufferedReader().readLine()
                    received.incrementAndGet()
                    // Drop without ACK: the bulb may have executed the write.
                }
            }
            val tcp=YeelightTcpSessions { ip,port -> Socket(ip,port).apply { soTimeout=1000 } }
            try {
                assertTrue(runCatching {
                    tcp.request("127.0.0.1",server.localPort,11,"set_rgb",
                        listOf(123,"sudden",0))
                }.isFailure)
                assertEquals(1,received.get())
                assertEquals(0,tcp.openCount())
            } finally {
                tcp.closeAll()
                worker.join(2000)
            }
        }
    }

    @Test fun idleSocketIsReleasedSoOtherAppsCanUseLanSlot() {
        ServerSocket(0).use { server ->
            val closed = java.util.concurrent.atomic.AtomicBoolean(false)
            val worker = thread {
                server.accept().use { socket ->
                    socket.getInputStream().bufferedReader().readLine()
                    socket.getOutputStream().write(
                        "{\"id\":21,\"result\":[\"ok\"]}\r\n".toByteArray())
                    socket.getOutputStream().flush()
                    socket.soTimeout = 2500
                    closed.set(socket.getInputStream().read() == -1)
                }
            }
            val tcp = YeelightTcpSessions { ip, port ->
                Socket(ip,port).apply { soTimeout = 1400 }
            }
            try {
                tcp.request("127.0.0.1",server.localPort,21,"set_rgb",listOf(0xFF0000))
                assertEquals(1,tcp.openCount())
                tcp.closeIdle(0)
                assertEquals(0,tcp.openCount())
                worker.join(2700)
                assertTrue("TCP slot still held by the app",closed.get())
            } finally { tcp.closeAll(); worker.join(2700) }
        }
    }

    @Test fun targetedPreflightSocketCloseDoesNotCloseOtherLampOrForceExtraCommand() {
        ServerSocket(0).use { first ->
            ServerSocket(0).use { second ->
                val firstClosed = java.util.concurrent.atomic.AtomicBoolean(false)
                val worker1 = thread {
                    first.accept().use { socket ->
                        socket.soTimeout = 2500
                        val request=org.json.JSONObject(socket.getInputStream().bufferedReader().readLine())
                        val ack=org.json.JSONObject().put("id",request.getInt("id"))
                            .put("result",org.json.JSONArray().put("on"))
                        socket.getOutputStream().write((ack.toString() + "\r\n").toByteArray())
                        socket.getOutputStream().flush()
                        firstClosed.set(socket.getInputStream().read() == -1)
                    }
                }
                val worker2 = thread {
                    second.accept().use { socket ->
                        socket.soTimeout = 2500
                        val reader=socket.getInputStream().bufferedReader()
                        repeat(2) {
                            val request=org.json.JSONObject(reader.readLine())
                            val ack=org.json.JSONObject().put("id",request.getInt("id"))
                                .put("result",org.json.JSONArray().put("on"))
                            socket.getOutputStream().write((ack.toString() + "\r\n").toByteArray())
                            socket.getOutputStream().flush()
                        }
                    }
                }
                val tcp=YeelightTcpSessions(
                    trace={},
                    connect={ip,port -> Socket(ip,port).apply { soTimeout=2000 }}
                )
                try {
                    tcp.request("127.0.0.1",first.localPort,1,"get_prop",listOf("power"))
                    tcp.request("127.0.0.1",second.localPort,2,"get_prop",listOf("power"))
                    assertEquals(2,tcp.openCount())
                    tcp.closeTarget("127.0.0.1",first.localPort)
                    assertEquals(1,tcp.openCount())
                    // The unaffected lamp keeps its own connection and only
                    // receives the ONE request explicitly asked by caller.
                    tcp.request("127.0.0.1",second.localPort,3,"get_prop",listOf("power"))
                    assertEquals(1,tcp.openCount())
                    worker1.join(2400)
                    assertTrue("Preflight failed to free the target firmware slot",firstClosed.get())
                } finally {
                    tcp.closeAll()
                    worker1.join(2500)
                    worker2.join(2500)
                    assertFalse(worker1.isAlive)
                    assertFalse(worker2.isAlive)
                }
            }
        }
    }

    @Test fun firmwareQuotaIsQuarantinedEvenWhenCallerIgnoresErrorJson() {
        ServerSocket(0).use { server ->
            val worker = thread {
                server.accept().use { socket ->
                    val line = socket.getInputStream().bufferedReader().readLine()
                    val id = org.json.JSONObject(line).getInt("id")
                    socket.getOutputStream().write(
                        (org.json.JSONObject().put("id", id).put("error", org.json.JSONObject().put("message", "client quota exceeded")).toString() + "\r\n").toByteArray()
                    )
                    socket.getOutputStream().flush()
                }
            }
            val events = AtomicInteger()
            val gate = DeviceCommandGate(
                nowMs = { 10_000L }, minSpacingMs = 0,
                onQuarantined = { events.incrementAndGet() }
            )
            val tcp = YeelightTcpSessions(
                trace = { },
                onFirmwareQuotaRejected = { ip, error ->
                    gate.recordFirmwareRejection(ip, error)
                },
                connect = { ip, port -> Socket(ip, port).apply { soTimeout = 1_000 } },
            )
            try {
                val result = gate.run("127.0.0.1") {
                    tcp.request("127.0.0.1", server.localPort, 51,
                        "set_rgb", listOf(0x001234, "sudden", 0))
                }
                // Simulates a future caller that forgets to inspect "error".
                assertTrue(result.has("error"))
                assertEquals(1, events.get())
                assertTrue(gate.isQuarantined("127.0.0.1"))
                assertEquals(0, tcp.openCount())
                assertTrue(runCatching {
                    gate.run("127.0.0.1") { error("should be unreachable") }
                }.exceptionOrNull() is DeviceCommandGate.Deferred)
            } finally {
                tcp.closeAll()
                worker.join(2_000)
                assertFalse(worker.isAlive)
            }
        }
    }

    @Test fun nonQuotaFirmwareErrorDoesNotFabricateALanQuotaLockout() {
        ServerSocket(0).use { server ->
            val worker = thread {
                server.accept().use { socket ->
                    val line = socket.getInputStream().bufferedReader().readLine()
                    val id = org.json.JSONObject(line).getInt("id")
                    socket.getOutputStream().write(
                        (org.json.JSONObject().put("id", id).put("error", org.json.JSONObject().put("message", "unsupported method")).toString() + "\r\n").toByteArray()
                    )
                    socket.getOutputStream().flush()
                }
            }
            val gate = DeviceCommandGate(nowMs = { 10_000L }, minSpacingMs = 0)
            val tcp = YeelightTcpSessions(
                trace = { },
                onFirmwareQuotaRejected = { ip, error ->
                    gate.recordFirmwareRejection(ip, error)
                },
                connect = { ip, port -> Socket(ip, port).apply { soTimeout = 1_000 } },
            )
            try {
                val reply = gate.run("127.0.0.1") {
                    tcp.request("127.0.0.1", server.localPort, 52,
                        "get_prop", listOf("unknown_property"))
                }
                assertTrue(reply.has("error"))
                assertFalse(gate.isQuarantined("127.0.0.1"))
            } finally {
                tcp.closeAll()
                worker.join(2_000)
                assertFalse(worker.isAlive)
            }
        }
    }

    @Test fun explicitErrorIsReturnedAndConnectionIsDiscarded() {
        ServerSocket(0).use { server ->
            val worker=thread {
                server.accept().use {socket->
                    val line=socket.getInputStream().bufferedReader().readLine()
                    val id=Regex("\"id\":(\\d+)").find(line)!!.groupValues[1]
                    socket.getOutputStream().write(
                        "{\"id\":$id,\"error\":{\"message\":\"client quota exceeded\"}}\r\n".toByteArray())
                }
            }
            val tcp=YeelightTcpSessions { ip,port -> Socket(ip,port).apply { soTimeout=1000 } }
            try {
                val reply=tcp.request("127.0.0.1",server.localPort,3,"set_rgb",listOf(1))
                assertTrue(reply.has("error"))
                assertEquals(0,tcp.openCount())
            } finally {
                tcp.closeAll()
                worker.join(2000)
            }
        }
    }
}
