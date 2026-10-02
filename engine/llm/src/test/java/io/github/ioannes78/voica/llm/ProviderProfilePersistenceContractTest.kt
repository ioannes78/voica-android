package io.github.ioannes78.voica.llm

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderProfilePersistenceContractTest {
    @Test
    fun blankDefaultModelRemainsValidWhenProfileIsReloaded() {
        val profileJson =
            buildJsonObject {
                put("defaultModel", "")
            }

        assertEquals("", decodePersistedDefaultModel(profileJson))
    }

    @Test
    fun missingDefaultModelFromEarlyStage11ProfileRecoversAsBlank() {
        val profileJson = buildJsonObject {}

        assertEquals("", decodePersistedDefaultModel(profileJson))
    }

    @Test
    fun selectedDefaultModelIsPreserved() {
        val profileJson =
            buildJsonObject {
                put("defaultModel", "Qwen/Qwen3-8B")
            }

        assertEquals(
            "Qwen/Qwen3-8B",
            decodePersistedDefaultModel(profileJson),
        )
    }
}
