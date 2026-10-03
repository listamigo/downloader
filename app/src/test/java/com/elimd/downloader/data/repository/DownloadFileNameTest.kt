package com.elimd.downloader.data.repository

import com.elimd.downloader.domain.model.DownloadType
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El nombre de fichero se construye a partir del titulo del video, que viene de
 * YouTube y es entrada no confiable. Estos tests fijan la propiedad que de
 * verdad importa: el nombre resultante debe ser un unico componente de ruta que
 * no puede salir del directorio de descargas.
 */
class DownloadFileNameTest {

    private val downloadDir = File("/data/user/0/com.elimd.downloader/files/descargas")

    @Test
    fun `el titulo con intentos de traversal se queda dentro del directorio`() {
        val attacks = listOf(
            "../../etc/passwd",
            "..\\..\\windows\\system32",
            "/etc/shadow",
            "....//....//etc",
            "..",
            ".",
            "~/../../root"
        )

        attacks.forEach { attack ->
            val name = sanitizeFileName(attack)
            val resolved = File(downloadDir, name).canonicalFile

            assertEquals(
                "el titulo '$attack' escapo del directorio de descargas",
                downloadDir.canonicalFile,
                resolved.parentFile
            )
        }
    }

    @Test
    fun `el nombre resultante no contiene separadores de ruta`() {
        listOf(
            "AC/DC: Thor? *Marvel* <b> \"fin\"",
            "video | con \\ todos los \\ caracteres",
            "pais: españa/verano"
        ).forEach { title ->
            val name = sanitizeFileName(title)
            assertTrue("'$name' contiene '/'", !name.contains('/'))
            assertTrue("'$name' contiene '\\'", !name.contains('\\'))
        }
    }

    @Test
    fun `colapsa espacios repetidos y recorta extremos`() {
        assertEquals("hola mundo", sanitizeFileName("  hola     mundo  "))
    }

    @Test
    fun `usa un nombre por defecto si el titulo queda vacio`() {
        assertEquals("descarga", sanitizeFileName(""))
        assertEquals("descarga", sanitizeFileName("     "))
    }

    @Test
    fun `acorta titulos muy largos`() {
        val name = sanitizeFileName("a".repeat(500))

        assertEquals(80, name.length)
    }

    @Test
    fun `el mime coincide con el tipo de descarga`() {
        assertEquals("audio/mp4", mimeFor(DownloadType.AUDIO))
        assertEquals("video/mp4", mimeFor(DownloadType.VIDEO))
        assertEquals("video/mp4", mimeFor(DownloadType.BOTH))
    }
}