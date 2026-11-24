package dev.openhelm.app.net

import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that keep one connection loop's teardown from reaching into another's.
 *
 * Every reconnect path in the app leans on these: stop-then-start must not close the new loop's
 * socket, and a loop told to stop must not be able to open a socket nobody is left to close.
 */
class SocketOwnerTest {

    private class Probe : Closeable {
        var closes = 0
        override fun close() {
            closes++
        }
    }

    @Test
    fun `adopt returns what it was given`() {
        val probe = Probe()
        assertSame(probe, SocketOwner().adopt(probe))
        assertEquals(0, probe.closes)
    }

    @Test
    fun `closeAll closes everything adopted and still accepts the next attempt's sockets`() {
        val owner = SocketOwner()
        val first = owner.adopt(Probe())
        val second = owner.adopt(Probe())
        owner.closeAll()
        assertEquals(1, first.closes)
        assertEquals(1, second.closes)

        // A retry: the loop is still running, so its next socket must be accepted...
        val third = owner.adopt(Probe())
        assertEquals(0, third.closes)
        // ...and the earlier ones are not closed a second time.
        owner.closeAll()
        assertEquals(1, first.closes)
        assertEquals(1, third.closes)
    }

    @Test
    fun `after shutdown a late socket is closed on the spot and refused`() {
        val owner = SocketOwner()
        val early = owner.adopt(Probe())
        owner.shutdown()
        assertEquals(1, early.closes)

        // The race this exists for: the loop was between sockets when it was told to stop.
        val late = Probe()
        assertThrows(IOException::class.java) { owner.adopt(late) }
        assertEquals("a refused socket must not be left open", 1, late.closes)
    }

    @Test
    fun `one owner never touches another's sockets`() {
        val old = SocketOwner()
        val new = SocketOwner()
        val oldSocket = old.adopt(Probe())
        val newSocket = new.adopt(Probe())
        // The stopped loop finishing its cleanup after its successor has already started.
        old.shutdown()
        old.closeAll()
        assertEquals(1, oldSocket.closes)
        assertEquals(0, newSocket.closes)
    }

    @Test
    fun `a failing close does not stop the others being closed`() {
        val owner = SocketOwner()
        owner.adopt(Closeable { throw IOException("already gone") })
        val after = owner.adopt(Probe())
        owner.closeAll()
        assertEquals(1, after.closes)
    }

    @Test
    fun `shutdown unblocks a read on a real socket from another thread`() {
        // The whole reason sockets are closed from outside: cancellation cannot interrupt this.
        ServerSocket(0).use { server ->
            val owner = SocketOwner()
            val client = owner.adopt(Socket())
            client.connect(InetSocketAddress("127.0.0.1", server.localPort), 2_000)
            server.accept().use {
                val failed = CountDownLatch(1)
                var sawIo = false
                val reader = thread {
                    try {
                        client.getInputStream().read()
                    } catch (_: IOException) {
                        sawIo = true
                    }
                    failed.countDown()
                }
                Thread.sleep(100) // let the read block
                assertFalse(client.isClosed)
                owner.shutdown()
                assertTrue("read did not return after shutdown", failed.await(2, TimeUnit.SECONDS))
                reader.join()
                assertTrue(sawIo)
            }
        }
    }
}
