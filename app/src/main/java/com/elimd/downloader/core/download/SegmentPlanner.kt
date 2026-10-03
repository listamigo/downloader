package com.elimd.downloader.core.download

/**
 * Reparto de una descarga por segmentos.
 *
 * Es pura aritmetica de intervalos a proposito: quien decide por donde cortar no
 * sabe nada de HTTP, y se puede probar en la JVM sin red. Lo que hace falta es
 * exactamente que se prueba aqui, porque un fallo aqui produce ficheros con
 * huecos que luego el reproductor no sabe explicar.
 *
 * Un intervalo es inclusivo por los dos lados, como las cabeceras `Range` de
 * HTTP (`bytes=0-99` son 100 bytes).
 */
internal data class Range(val start: Long, val endInclusive: Long) {

    init {
        require(endInclusive >= start) { "Rango invertido: $start-$endInclusive" }
    }

    val size: Long get() = endInclusive - start + 1

    val endExclusive: Long get() = endInclusive + 1
}

/**
 * Las partes de [total] que todavia no se han bajado.
 *
 * Es la operacion que hace posible reanudar: de lo ya descargado sale lo que
 * falta, no "cuanto mide el fichero", porque con descargas en paralelo el
 * fichero tiene el tamaño final desde el principio y esta lleno de huecos.
 */
internal fun missingRanges(completed: List<Range>, total: Long): List<Range> {
    if (total <= 0L) return emptyList()

    val merged = completed
        .filter { it.start < total }
        .sortedBy { it.start }
        .fold(mutableListOf<Range>()) { acc, range ->
            val last = acc.lastOrNull()
            when {
                last == null -> acc += range
                range.start <= last.endExclusive -> {
                    acc[acc.lastIndex] = Range(last.start, maxOf(last.endInclusive, range.endInclusive))
                }
                else -> acc += range
            }
            acc
        }

    val missing = mutableListOf<Range>()
    var cursor = 0L
    merged.forEach { range ->
        if (range.start > cursor) missing += Range(cursor, range.start - 1)
        cursor = maxOf(cursor, range.endExclusive)
    }
    if (cursor < total) missing += Range(cursor, total - 1)

    return missing
}

/**
 * Los huecos de [missing] se reparten en, como mucho, [maxSegments] trozos.
 *
 * Tres reglas, y las tres importan:
 *  - un hueco mas pequeño que [minSegmentBytes] no se parte: por debajo de eso
 *    el ahorro de tiempo no paga las cuatro peticiones extra;
 *  - los trozos de un hueco son iguales, para que la barra no de un salto al
 *    terminar el mas corto;
 *  - el presupuesto se gasta en el orden de los huecos, que van de menor a mayor
 *    posicion: el primero es el que antes se puede cerrar. Un hueco siempre se
 *    baja entero, asi que si son mas huecos que presupuesto, el exceso son
 *    trozos que no se pueden evitar.
 */
internal fun planSegments(
    missing: List<Range>,
    maxSegments: Int,
    minSegmentBytes: Long
): List<Range> {
    if (missing.isEmpty() || maxSegments <= 0) return emptyList()

    val segments = mutableListOf<Range>()
    var budget = maxSegments

    missing.forEach { hole ->
        val wanted = maxOf(1L, hole.size / minSegmentBytes)
        val parts = maxOf(1, minOf(budget.toLong(), wanted).toInt())
        budget = maxOf(0, budget - parts)

        val chunk = hole.size / parts
        var start = hole.start
        repeat(parts) { index ->
            val size = if (index == parts - 1) hole.endExclusive - start else chunk
            segments += Range(start, start + size - 1)
            start += size
        }
    }

    return segments
}

/**
 * Los segmentos completados se guardan en un fichero al lado del parcial.
 *
 * Sin este registro, reanudar tendria que suponer que el fichero esta lleno
 * hasta donde ocupa, y con descargas en paralelo esa suposicion es falsa: el
 * fichero mide el total desde el primer instante, con huecos.
 */
internal fun serializeRanges(ranges: List<Range>): String =
    ranges.sortedBy { it.start }.joinToString(separator = "\n", postfix = "\n") { "${it.start}-${it.endInclusive}" }

/** Un registro incompleto o corrupto se ignora: se vuelve a bajar ese trozo. */
internal fun parseRanges(text: String): List<Range> =
    text.lineSequence()
        .mapNotNull { line ->
            val parts = line.trim().split('-')
            if (parts.size != 2) return@mapNotNull null
            val start = parts[0].toLongOrNull() ?: return@mapNotNull null
            val end = parts[1].toLongOrNull() ?: return@mapNotNull null
            if (end < start) null else Range(start, end)
        }
        .toList()