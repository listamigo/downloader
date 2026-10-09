package com.elimd.downloader.core.common

/**
 * Forma de un identificador de vídeo de YouTube.
 *
 * Un id real son 11 caracteres del alfabeto `[A-Za-z0-9_-]`. Aquí se acepta un
 * rango de longitud más ancho para no romper si YouTube los alarga, pero el
 * alfabeto es lo que importa: fuera de él no hay barra, ni punto, ni espacio.
 *
 * El id entra por la red y termina siendo nombre de fichero (`$videoId.jpg`) y
 * trozo de URL. Sin esta comprobación, un id como `../../x` escribiría la
 * miniatura fuera del directorio de caché. Que hoy venga de un regex de
 * NewPipe es una casualidad, no una garantía.
 */
private val YOUTUBE_VIDEO_ID = Regex("^[A-Za-z0-9_-]{1,64}$")

/**
 * Devuelve el id si tiene una forma segura para usarlo en nombres de fichero y
 * URLs, o `null` si no. Un `null` significa "no lo uses": no se intenta reparar
 * un id desconocido, porque un id "arreglado" apunta a otro vídeo.
 */
fun sanitizeVideoId(raw: String): String? =
    raw.trim().takeIf { YOUTUBE_VIDEO_ID.matches(it) }
