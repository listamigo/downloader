package com.elimd.downloader.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El id de vídeo es entrada de la red que acaba en un nombre de fichero y en
 * una URL. Estos tests fijan que solo pasan los ids con forma de id de YouTube:
 * lo que no la tiene se descarta en lugar de "arreglarse", porque un id
 * reparado apunta a otro vídeo.
 */
class VideoIdTest {

    @Test
    fun `acepta un id real de once caracteres`() {
        assertEquals("dQw4w9WgXcQ", sanitizeVideoId("dQw4w9WgXcQ"))
    }

    @Test
    fun `acepta guiones y guiones bajos`() {
        assertEquals("a_b-cD1", sanitizeVideoId("a_b-cD1"))
    }

    @Test
    fun `recorta espacios de los extremos`() {
        assertEquals("dQw4w9WgXcQ", sanitizeVideoId("  dQw4w9WgXcQ\n"))
    }

    @Test
    fun `descarta un intento de path traversal`() {
        assertNull(sanitizeVideoId("../../etc/passwd"))
    }

    @Test
    fun `descarta barras y puntos`() {
        assertNull(sanitizeVideoId("foo/bar"))
        assertNull(sanitizeVideoId("foo.bar"))
        assertNull(sanitizeVideoId(".."))
    }

    @Test
    fun `descarta espacios internos y simbolos de URL`() {
        assertNull(sanitizeVideoId("a b"))
        assertNull(sanitizeVideoId("id?x=1"))
        assertNull(sanitizeVideoId("id#frag"))
    }

    @Test
    fun `descarta vacio`() {
        assertNull(sanitizeVideoId(""))
        assertNull(sanitizeVideoId("   "))
    }

    @Test
    fun `descarta un id desmesuradamente largo`() {
        assertNull(sanitizeVideoId("a".repeat(65)))
    }
}
