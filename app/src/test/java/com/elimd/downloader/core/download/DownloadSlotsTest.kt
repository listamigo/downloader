package com.elimd.downloader.core.download

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Si este limite falla, el usuario que pone "1 descarga a la vez" se lleva 4
 * en paralelo: la configuracion deja de ser una promesa.
 */
class DownloadSlotsTest {

    @Test
    fun `no deja pasar mas descargas que el limite`() {
        val slots = DownloadSlots()

        assertTrue(slots.tryAcquire(limit = 2))
        assertTrue(slots.tryAcquire(limit = 2))
        assertFalse(slots.tryAcquire(limit = 2))
    }

    @Test
    fun `soltar un hueco permite la siguiente descarga`() {
        val slots = DownloadSlots()

        assertTrue(slots.tryAcquire(limit = 1))
        assertFalse(slots.tryAcquire(limit = 1))

        slots.release()

        assertTrue(slots.tryAcquire(limit = 1))
    }

    @Test
    fun `un limite sin sentido cuenta como uno`() {
        val slots = DownloadSlots()

        assertTrue(slots.tryAcquire(limit = 0))
        assertFalse(slots.tryAcquire(limit = 0))
        assertFalse(slots.tryAcquire(limit = -3))
    }

    @Test
    fun `con hilos compitiendo nunca se pasa del limite`() {
        val slots = DownloadSlots()
        val limit = 3
        val inside = AtomicInteger()
        val maxSeen = AtomicInteger()
        val startGate = java.util.concurrent.CountDownLatch(1)

        val threads = (1..16).map {
            Thread {
                startGate.await()
                while (!slots.tryAcquire(limit)) Thread.sleep(1)
                val now = inside.incrementAndGet()
                maxSeen.accumulateAndGet(now, Math::max)
                Thread.sleep(5)
                inside.decrementAndGet()
                slots.release()
            }
        }

        threads.forEach { it.start() }
        startGate.countDown()
        threads.forEach { it.join(10_000) }

        assertTrue("hilos colgados esperando un hueco", threads.none { it.isAlive })
        assertTrue(
            "vieron ${maxSeen.get()} descargas dentro con limite $limit",
            maxSeen.get() <= limit
        )
    }
}
