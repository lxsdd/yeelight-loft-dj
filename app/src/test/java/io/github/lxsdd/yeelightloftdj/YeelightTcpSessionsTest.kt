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
            val tcp=YeelightTcpSessions { ip,port -> Socket(ip,port).apply { soTimeout=1800 } }
            try {
                assertEquals("ok",tcp.request("127.0.0.1",server.localPort,11,"set_rgb",
                    listOf(123,"sudden",0)).getJSONArray("result").getString(0))
                assertEquals("ok",tcp.request("127.0.0.1",server.localPort,12,"set_bright",
                    listOf(80,"sudden",0)).getJSONArray("result").getString(0))
                assertEquals(1,accepted.get())
                assertEquals(1,tcp.openCount())
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
