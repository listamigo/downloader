package com.elimd.downloader.core.download

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El peso del fichero se compara con el que dice el explorador de archivos del
 * telefono, asi que la cuenta y las unidades tienen que ser las mismas.
 */
class ByteFormatTest {

    private val spain = Locale("es", "ES")
    private val unitedStates = Locale.US

    @Test
    fun `sin dato no se pinta un cero`() {
        // 0 significa "el motor aun no sabe el tamaño", no "fichero vacio".
        assertNull(formatBytes(0L, spain))
        assertNull(formatBytes(-1L, spain))
    }

    @Test
    fun `por debajo de un kilobyte van bytes`() {
        assertEquals("512 B", formatBytes(512L, spain))
        assertEquals("1023 B", formatBytes(1023L, spain))
    }

    @Test
    fun `los kilobytes no llevan decimales`() {
        assertEquals("1 KB", formatBytes(1024L, spain))
        assertEquals("879 KB", formatBytes(900_000L, spain))
    }

    @Test
    fun `los megabytes con un decimal`() {
        assertEquals("1,0 MB", formatBytes(1024L * 1024, spain))
        assertEquals("28,6 MB", formatBytes(30_000_000L, spain))
    }

    @Test
    fun `a partir de un gigabyte cambia la unidad`() {
        assertEquals("2,8 GB", formatBytes(3_000_000_000L, spain))
    }

    @Test
    fun `el separador decimal es el del idioma`() {
        assertEquals("28.6 MB", formatBytes(30_000_000L, unitedStates))
    }
}
