package com.elimd.downloader.core.download

import java.util.Locale

/**
 * El peso de un fichero en la unidad que se lee de un vistazo.
 *
 * Base 1024 y no 1000, que es como mide el propio Android: mezclar bases hacia
 * que "30 MB" del explorador de ficheros no cuadre con lo que ve el usuario en
 * la app. El separador decimal lo pone el idioma del telefono, "28,6 MB" en
 * Espana y "28.6 MB" en uno configurado en ingles.
 *
 * Devuelve `null` cuando no hay dato: 0 bytes aqui significa "el motor aun no
 * sabe cuanto pesa", no "un fichero vacio".
 */
internal fun formatBytes(bytes: Long, locale: Locale = Locale.getDefault()): String? {
    if (bytes <= 0L) return null

    val kilobytes = bytes / 1024.0
    val megabytes = kilobytes / 1024.0
    val gigabytes = megabytes / 1024.0

    return when {
        bytes < 1024 -> "$bytes B"
        megabytes < 1 -> String.format(locale, "%.0f KB", kilobytes)
        gigabytes < 1 -> String.format(locale, "%.1f MB", megabytes)
        else -> String.format(locale, "%.1f GB", gigabytes)
    }
}
