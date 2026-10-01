package io.github.ioannes78.voica.model

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelCatalogProviderTest {
    @Test
    fun decodingProviderCachesUntilForcedRefresh() = runBlocking {
        var calls = 0
        val source =
            ModelCatalogTextSource {
                calls += 1
                manifest(calls)
            }
        val provider = DecodingModelCatalogProvider(source)

        assertEquals(1, provider.load(force = false).manifestVersion)
        assertEquals(1, provider.load(force = false).manifestVersion)
        assertEquals(1, calls)

        assertEquals(2, provider.load(force = true).manifestVersion)
        assertEquals(2, calls)
    }

    @Test(expected = IllegalArgumentException::class)
    fun httpCatalogUrlIsRejected() {
        HttpsModelCatalogTextSource(
            catalogUrl = "http://example.invalid/catalog.json",
        )
    }

    private fun manifest(version: Int): String {
        val models =
            """
            [
              {
                "modelId": "fixture",
                "kind": "ASR_STREAMING",
                "displayName": "Fixture",
                "version": "v1",
                "revision": 1,
                "runtimeId": "sherpa-onnx",
                "runtimeVersionMin": "1.13.8",
                "runtimeVersionMax": null,
                "languages": ["zh"],
                "capabilities": {
                  "supportsStreaming": true,
                  "supportsPartial": true,
                  "supportsTokenTiming": false,
                  "supportsLanguageDetection": false,
                  "supportsConfidence": false,
                  "supportsInverseTextNormalization": false,
                  "supportsSecondPass": false,
                  "supportsHotwords": false
                },
                "sourceType": "MANAGED_DOWNLOAD",
                "builtinAssetPath": null,
                "download": {
                  "packageFormat": "SINGLE_FILE",
                  "url": "https://example.invalid/model.bin",
                  "mirrors": [],
                  "sizeBytes": 1,
                  "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                },
                "installedSizeBytes": 1,
                "files": [
                  {
                    "relativePath": "model.bin",
                    "sizeBytes": 1,
                    "sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                    "packagePath": "model.bin"
                  }
                ],
                "compatibility": {
                  "abis": ["arm64-v8a"],
                  "minSdk": 26,
                  "appVersionMin": 20,
                  "appVersionMax": null
                },
                "license": {
                  "id": "Apache-2.0",
                  "url": null,
                  "attribution": "fixture",
                  "redistributionPolicy": "UPSTREAM_ONLY"
                },
                "sourceUrl": "https://example.invalid/source",
                "homepage": null,
                "releaseChannel": "production",
                "autoUpdateEligible": false,
                "deprecated": false,
                "criticalUpdate": false
              }
            ]
            """.trimIndent()
        val digest = ModelCatalogCodec.computeModelsDigest(models)
        return """
            {
              "catalogVersion": 1,
              "manifestVersion": $version,
              "channel": "production",
              "publishedAt": null,
              "manifestDigest": "$digest",
              "signature": null,
              "keyId": null,
              "models": $models
            }
        """.trimIndent()
    }
}
