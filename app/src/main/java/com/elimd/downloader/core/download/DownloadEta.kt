package com.elimd.downloader.core.download

/**
 * Cuanto queda de una descarga, medido sobre el progreso global.
 *
 * El motor va en tres fases posibles (bajar video, bajar audio y unirlos) y cada
 * una tiene su propio peso en la barra. Estimar sobre el porcentaje global, en
 * lugar de sobre los bytes de la fase actual, es lo unico que da un numero
 * honesto en los tres casos: durante el multiplexado casi no se descargan bytes,
 * pero al usuario le queda lo que le queda.
 *
 * Se mide sobre la media desde el inicio, no sobre el ultimo segundo: la
 * velocidad instantanea de OkHttp oscila tanto que un contador de cuenta atras
 * daria saltos de varios minutos de un tick a otro.
 */
internal class DownloadEta(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {

    private var startedAt = -1L
    private var bytesAtStart = 0L

    /**
     * Registra un tick de progreso y devuelve la velocidad y el tiempo restante.
     *
     * El primer tick solo fija el punto de partida: sin al menos un intervalo
     * medible no hay nada que estimar, y devolver un numero en ese instante
     * seria inventarlo.
     */
    fun sample(percent: Float, downloaded: Long): Sample {
        val now = nowMs()
        if (startedAt < 0) {
            startedAt = now
            bytesAtStart = downloaded
            return Sample.NONE
        }

        val elapsedMs = now - startedAt
        val transferred = downloaded - bytesAtStart
        val bytesPerSecond = if (elapsedMs > 0) transferred * 1000 / elapsedMs else 0L
        val remainingSeconds = remainingSeconds(percent, elapsedMs)

        return Sample(bytesPerSecond = bytesPerSecond, remainingSeconds = remainingSeconds)
    }

    /** Sin dato todavia: 0 significa "no se sabe" en toda la app. */
    private fun remainingSeconds(percent: Float, elapsedMs: Long): Long = when {
        percent <= 0f || percent >= 100f -> 0L
        elapsedMs < MIN_ELAPSED_MS -> 0L
        else -> elapsedMs * (100L - percent.toLong()) / percent.toLong() / 1000L
    }

    data class Sample(
        val bytesPerSecond: Long,
        val remainingSeconds: Long
    ) {
        companion object {
            val NONE = Sample(bytesPerSecond = 0L, remainingSeconds = 0L)
        }
    }

    private companion object {
        /**
         * Por debajo de este tiempo la estimacion es ruido: en el primer segundo
         * la conexion aun no ha cogido velocidad y "quedan 40 min" asusta mas de
         * lo que informa.
         */
        const val MIN_ELAPSED_MS = 2_000L
    }
}
