package com.elimd.downloader.core.extract

import org.json.JSONArray
import org.json.JSONObject

/**
 * Workaround para un fallo de robustez de NewPipeExtractor en la busqueda.
 *
 * YouTube omite el campo `badges` en algunos `videoRenderer`, pero la version
 * del extractor que usamos lo da por hecho y lanza `NullPointerException`,
 * descartando el resultado. El sintoma es una busqueda que devuelve 0 elementos
 * de forma intermitente, dependiendo de lo que YouTube decida incluir.
 *
 * Anadir un array vacio donde falta es semanticamente neutro: `badges` solo
 * alimenta el contador de insignias del canal.
 *
 * Es un parche local y deliberado: cuando TeamNewPipe lo corrija aguas arriba,
 * esta clase puede eliminarse sin tocar el resto.
 */
internal object SearchResponseSanitizer {

    private const val SEARCH_PATH = "/youtubei/v1/search"

    fun sanitizeIfNeeded(url: String, body: String): String {
        if (!url.contains(SEARCH_PATH) || !body.contains("videoRenderer")) return body
        return runCatching {
            val root = JSONObject(body)
            val patched = patchNode(root)
            if (patched > 0) root.toString() else body
        }.getOrDefault(body)
    }

    /**
     * Recorre el arbol JSON y anade `badges: []` a cada `videoRenderer` que
     * no lo tenga. Devuelve cuantos elementos se corrigieron.
     */
    private fun patchNode(node: Any?): Int {
        var count = 0
        when (node) {
            is JSONObject -> {
                if (node.has(VIDEO_RENDERER)) {
                    val renderer = node.optJSONObject(VIDEO_RENDERER)
                    if (renderer != null && !renderer.has(BADGES)) {
                        renderer.put(BADGES, JSONArray())
                        count++
                    }
                }
                node.keys().forEach { key -> count += patchNode(node.opt(key)) }
            }

            is JSONArray -> {
                for (i in 0 until node.length()) {
                    count += patchNode(node.opt(i))
                }
            }
        }
        return count
    }

    private const val VIDEO_RENDERER = "videoRenderer"
    private const val BADGES = "badges"
}