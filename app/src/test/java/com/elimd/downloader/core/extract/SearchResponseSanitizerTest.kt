package com.elimd.downloader.core.extract

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El parche de `badges` es la pieza mas fragil del extractor: si deja de
 * aplicarse, la busqueda devuelve 0 resultados de forma intermitente. Estos
 * tests fijan el comportamiento para que una regresion se detecte aqui y no
 * en un dispositivo.
 */
class SearchResponseSanitizerTest {

    private val searchUrl = "https://www.youtube.com/youtubei/v1/search?prettyPrint=false"

    @Test
    fun `anade badges cuando el videoRenderer no lo tiene`() {
        val body = """
            {"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{
              "sectionListRenderer":{"contents":[
                {"itemSectionRenderer":{"contents":[
                  {"videoRenderer":{"videoId":"abc123","title":{"runs":[{"text":"Uno"}]}}}
                ]}}]}}}}}
        """.trimIndent()

        val patched = SearchResponseSanitizer.sanitizeIfNeeded(searchUrl, body)
        val renderer = extractFirstVideoRenderer(JSONObject(patched))

        assertTrue("debe contener badges", renderer.has("badges"))
        assertEquals(0, renderer.getJSONArray("badges").length())
    }

    @Test
    fun `respeta un badges ya presente`() {
        val body = """
            {"videoRenderer":{"videoId":"abc123","badges":[{"metadataBadgeRenderer":{}}]}}
        """.trimIndent()

        val patched = SearchResponseSanitizer.sanitizeIfNeeded(searchUrl, body)
        val renderer = extractFirstVideoRenderer(JSONObject(patched))

        assertEquals("no debe duplicar insignias", 1, renderer.getJSONArray("badges").length())
    }

    @Test
    fun `corrige varios videoRenderer en la misma respuesta`() {
        val body = """
            {"contents":[
              {"videoRenderer":{"videoId":"a"}},
              {"videoRenderer":{"videoId":"b"}},
              {"videoRenderer":{"videoId":"c","badges":[]}}
            ]}
        """.trimIndent()

        val patched = SearchResponseSanitizer.sanitizeIfNeeded(searchUrl, body)
        val items = JSONObject(patched).getJSONArray("contents")

        assertTrue(items.getJSONObject(0).getJSONObject("videoRenderer").has("badges"))
        assertTrue(items.getJSONObject(1).getJSONObject("videoRenderer").has("badges"))
        assertEquals(0, items.getJSONObject(2).getJSONObject("videoRenderer").getJSONArray("badges").length())
    }

    @Test
    fun `no toca respuestas de otros endpoints`() {
        val body = """{"videoRenderer":{"videoId":"abc123"}}"""

        val result = SearchResponseSanitizer.sanitizeIfNeeded(
            "https://www.youtube.com/youtubei/v1/player",
            body
        )

        assertEquals(body, result)
    }

    @Test
    fun `ignora cuerpos que no son busqueda`() {
        val body = "<html>no es json</html>"

        val result = SearchResponseSanitizer.sanitizeIfNeeded(searchUrl, body)

        assertEquals(body, result)
    }

    private fun extractFirstVideoRenderer(root: JSONObject): JSONObject {
        var found: JSONObject? = null

        fun walk(node: Any?) {
            when (node) {
                is JSONObject -> {
                    if (node.has("videoRenderer") && found == null) {
                        found = node.getJSONObject("videoRenderer")
                    }
                    node.keys().forEach { walk(node.opt(it)) }
                }
                is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i))
            }
        }

        walk(root)
        return requireNotNull(found) { "no se encontro ningun videoRenderer en el cuerpo" }
    }
}