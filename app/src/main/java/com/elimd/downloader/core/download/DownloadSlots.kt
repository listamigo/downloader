package com.elimd.downloader.core.download

import java.util.concurrent.atomic.AtomicInteger

/**
 * Limite de descargas en paralelo.
 *
 * El limite lo decide el usuario en Ajustes, asi que puede cambiar con las
 * descargas ya en marcha: por eso el limite se lee en cada intento en vez de
 * fijarse al construirlo (kotlinx.coroutines.sync.Semaphore no se puede
 * redimensionar). La compra es un CAS, asi que dos hilos no pueden colarse
 * juntos en la misma ultima plaza.
 */
internal class DownloadSlots {
    private val active = AtomicInteger()

    /**
     * Intenta coger hueco para una descarga mas.
     *
     * @param limit descargas que se permiten a la vez; 0 o menos cuenta como 1.
     * @return `false` si ya hay [limit] descargas dentro: el llamador debe
     *         esperar y volver a intentarlo.
     */
    fun tryAcquire(limit: Int): Boolean {
        val max = limit.coerceAtLeast(1)
        while (true) {
            val current = active.get()
            if (current >= max) return false
            if (active.compareAndSet(current, current + 1)) return true
        }
    }

    /** Devuelve el hueco. Solo tras un [tryAcquire] con `true`, en `finally`. */
    fun release() {
        active.decrementAndGet()
    }
}
