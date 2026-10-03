package com.elimd.downloader.core.extract

import com.elimd.downloader.domain.model.DownloadQuality
import com.elimd.downloader.domain.model.DownloadType

/**
 * Un stream descargable, ya desligado de NewPipeExtractor.
 *
 * Existe para que la politica de eleccion de calidad (que stream sirve para
 * que peticion, si hay que unir pistas, cual se muestra en el selector) se
 * pueda probar en la JVM sin montar un `StreamInfo` ni hablar con YouTube.
 * Traducir de `VideoStream`/`AudioStream` a esto es responsabilidad del
 * resolutor, que es la unica pieza que depende del extractor.
 */
internal data class StreamCandidate(
    val id: String,
    val label: String,
    val url: String,
    val mimeType: String,
    val format: String,
    val height: Int? = null,
    val fps: Int? = null,
    val bitrate: Int = 0,
    val isMp4: Boolean = false,
    val isVideoOnly: Boolean = false,
    val isAudioOnly: Boolean = false,
    val videoCodec: String? = null,
    val audioCodec: String? = null
) {
    fun toDownloadQuality(requiresMuxing: Boolean = false) = DownloadQuality(
        id = id,
        label = label,
        format = format,
        height = height,
        fps = fps,
        videoCodec = videoCodec,
        audioCodec = audioCodec,
        isAudioOnly = isAudioOnly,
        isVideoOnly = isVideoOnly,
        requiresMuxing = requiresMuxing && isVideoOnly
    )

    fun toMedia() = ResolvedMedia(
        videoUrl = url,
        audioUrl = null,
        videoMimeType = mimeType,
        audioMimeType = null,
        isAudioOnly = isAudioOnly
    )
}

/**
 * Politica de seleccion de streams.
 *
 * YouTube entrega video y audio juntos (progresivo) hasta ~720p; por encima solo
 * hay pistas separadas, asi que la descarga necesita dos ficheros y unirlos.
 * Estas funciones deciden que hacer en cada caso y con que preferencia.
 */
internal object StreamSelection {

    /**
     * YouTube solo ofrece streams con audio integrado hasta esta resolucion.
     * Es el dato que separa "una descarga" de "dos descargas y un multiplexado".
     */
    const val MAX_PROGRESSIVE_HEIGHT = 720

    /**
     * Que hay que bajar y como.
     *
     * Se decide **antes** de tocar la red para que quede aislado y probado: el
     * error que duele es pedir 1080p y recibir un 360p sin que nadie diga nada.
     */
    sealed interface Plan {
        /** Un solo fichero va directo al destino. */
        data class Single(val stream: StreamCandidate) : Plan

        /** Hay que unir pistas: video de un lado, audio de otro. */
        data class Join(val video: StreamCandidate, val audio: StreamCandidate?) : Plan

        /** Audio nada mas. */
        data class AudioOnly(val stream: StreamCandidate) : Plan

        /** Ningun stream encaja con lo pedido. */
        data object None : Plan
    }

    /**
     * Elige el plan de descarga para una calidad y un tipo.
     *
     * Regla que atraviesa todo esto: **no se baja una resolución distinta de la
     * pedida**. Si el vídeo no tiene lo que se pide, se devuelve [Plan.None] y
     * el usuario ve un error, no un fichero de otra calidad con el nombre de la
     * que pidió.
     */
    fun resolve(
        progressive: List<StreamCandidate>,
        videoOnly: List<StreamCandidate>,
        audio: List<StreamCandidate>,
        quality: DownloadQuality,
        type: DownloadType
    ): Plan {
        if (type == DownloadType.AUDIO) {
            return bestAudio(audio)?.let { Plan.AudioOnly(it) } ?: Plan.None
        }

        val wantsAudio = type == DownloadType.BOTH
        val height = quality.height

        // Camino barato: un progresivo ya trae audio y video juntos, asi que no
        // hay que unir nada ni recodificar. YouTube solo los ofrece hasta 720p, y
        // "Mejor" no cuenta: pedir la maxima calidad implica necesariamente unir.
        if (wantsAudio && height != null && height <= MAX_PROGRESSIVE_HEIGHT) {
            pickFor(progressive, quality)?.let { return Plan.Single(it) }
        }

        // Camino alto: video por un lado, audio por otro, y el motor los une.
        // Es lo que habilita 1080p y superiores.
        pickFor(videoOnly, quality)?.let { video ->
            val bestAudio = if (wantsAudio) bestAudio(audio) else null
            return Plan.Join(video, bestAudio)
        }

        // Ultimo recurso: un video que solo ofrezca progresivos. Si el usuario
        // pidio "solo video", el archivo llevara audio porque YouTube no da
        // ninguna otra opcion.
        pickFor(progressive, quality)?.let { return Plan.Single(it) }

        return Plan.None
    }

    /**
     * El stream que encaja con la calidad pedida, o `null` si no hay ninguno.
     *
     * Sin altura en la calidad ([DownloadQuality.isBest]) encaja cualquier
     * resolucion, y entonces gana la mas alta. A igualdad de resolucion se
     * prefiere H.264 (`mp4`): se puede meter en el MP4 final sin recodificar,
     * mientras que un VP9 obligaria a recodificar en el dispositivo, que es
     * lento y puede fallar.
     *
     * La altura va primero a proposito: si el criterio de H.264 mandara, "Mejor"
     * devolveria un 1080p en lugar del 2160p disponible solo en VP9.
     */
    fun pickFor(candidates: List<StreamCandidate>, quality: DownloadQuality): StreamCandidate? =
        candidates
            .filter { candidate ->
                val matchesHeight = quality.height == null || candidate.height == quality.height
                val matchesFps = quality.fps == null || quality.fps <= 0 || candidate.fps == quality.fps
                matchesHeight && matchesFps
            }
            .sortedWith(
                compareByDescending<StreamCandidate> { it.height ?: 0 }
                    .thenByDescending { it.isMp4 }
                    .thenByDescending { it.bitrate }
            )
            .firstOrNull()

    /**
     * La pista de audio que acompana al video, o la mejor suelta si el usuario
     * solo quiere audio.
     *
     * Un m4a (AAC) va primero aunque pese algo menos que un Opus: es el unico
     * que se copia tal cual dentro del MP4 final, sin decodificar ni recodificar.
     * Un Opus llega en WebM y hay que convertirlo a AAC en el dispositivo, que es
     * justo el paso que se ha visto fallar en el multiplexado. Para una descarga
     * de solo audio el mismo criterio vale: el fichero se guarda con extension
     * `.m4a`, y un WebM con nombre de m4a no lo abre ni el propio Android.
     */
    fun bestAudio(candidates: List<StreamCandidate>): StreamCandidate? =
        candidates
            .sortedWith(
                compareByDescending<StreamCandidate> { it.isMp4 }
                    .thenByDescending { it.bitrate }
            )
            .firstOrNull()

    /**
     * Las opciones de video del selector: una por resolucion y fps.
     *
     * InnerTube devuelve el mismo formato varias veces (distinto bitrate o
     * codec); offering todas ensuciaria la lista sin ganar nada. Gana la que no
     * necesita multiplexado y, a igualdad de eso, la de mayor bitrate.
     */
    fun videoOptions(
        progressive: List<StreamCandidate>,
        videoOnly: List<StreamCandidate>
    ): List<DownloadQuality> {
        val byResolution = LinkedHashMap<String, Pair<StreamCandidate, Boolean>>()

        fun offer(candidate: StreamCandidate, requiresMuxing: Boolean) {
            val key = "${candidate.height}x${candidate.fps}"
            val current = byResolution[key]
            byResolution[key] = when {
                current == null -> candidate to requiresMuxing
                !requiresMuxing && current.second -> candidate to requiresMuxing
                requiresMuxing == current.second && candidate.bitrate > current.first.bitrate ->
                    candidate to requiresMuxing
                else -> current
            }
        }

        progressive.forEach { offer(it, requiresMuxing = false) }
        videoOnly.forEach { offer(it, requiresMuxing = true) }

        return byResolution.values
            .map { (candidate, requiresMuxing) -> candidate.toDownloadQuality(requiresMuxing) }
            .sortedWith(
                compareByDescending<DownloadQuality> { it.height ?: 0 }
                    .thenByDescending { it.fps ?: 0 }
            )
    }

    fun audioOptions(audio: List<StreamCandidate>, limit: Int): List<DownloadQuality> =
        audio.sortedByDescending { it.bitrate }
            .take(limit)
            .map { it.toDownloadQuality() }
}
