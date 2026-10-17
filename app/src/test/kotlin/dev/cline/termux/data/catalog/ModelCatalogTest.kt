package dev.cline.termux.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {
    @Test
    fun `keyless providers are recognised from their config fields`() {
        val catalog = ModelCatalog.parse(
            """{"schema":1,"providers":[{"id":"ollama","name":"Ollama","configFields":[{"path":"baseUrl","label":"Base URL","type":"url","required":true}],"models":[]},
               {"id":"x","name":"X","models":[{"id":"m","name":"M","pricing":{"input":0,"output":0}}]}]}""",
        )
        val ollama = catalog.provider("ollama")!!
        assertEquals(false, ollama.needsApiKey)
        assertEquals(true, ollama.needsBaseUrl)
        assertTrue(catalog.model("x", "m")!!.isFree)
        assertEquals(true, catalog.provider("x")!!.needsApiKey)
    }

    @Test
    fun `popular providers keep their order and effort levels come from the effort option`() {
        val catalog = ModelCatalog.parse(
            """{"schema":1,"providers":[
               {"id":"b","name":"B","popularRank":2,"models":[]},
               {"id":"z","name":"Z","models":[]},
               {"id":"a","name":"A","popularRank":1,"models":[{"id":"m","name":"M","capabilities":["reasoning"],
                 "reasoningOptions":[{"type":"effort","values":["low","medium","high"]}]}]}]}""",
        )
        assertEquals(listOf("b", "a"), catalog.popular.map { it.id })
        val model = catalog.model("a", "provider/m")!!
        assertTrue(model.supportsReasoning)
        assertEquals(listOf("low", "medium", "high"), model.effortLevels)
    }
}
