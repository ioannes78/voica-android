package io.github.ioannes78.voica.llm

import io.github.ioannes78.voica.ai.ProviderPresetIds
import io.github.ioannes78.voica.ai.ProviderProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderProfilePersistenceContractTest {
    @Test
    fun blankDefaultModelIsAValidProviderProfileState() {
        val profile =
            ProviderProfile(
                providerProfileId = "profile-blank-model",
                presetId = ProviderPresetIds.SILICONFLOW,
                displayName = "硅基流动",
                baseUrl = "https://api.siliconflow.cn/v1",
                credentialRef = "credential-blank-model",
                defaultModel = "",
                timeoutMs = 60_000,
                manualContextWindowTokens = null,
                capabilityOverrides = null,
                enabled = true,
            )

        assertEquals("", profile.defaultModel)
        assertTrue(profile.baseUrl.startsWith("https://"))
    }
}
