package io.github.ioannes78.voica.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogCodecTest {
    @Test
    fun decodesPinnedProductionManifestWithVerifiedDigest() {
        val models =
            """
            [
              {
                "modelId": "zipformer-small-bilingual",
                "kind": "ASR_STREAMING",
                "displayName": "基础中英识别",
                "version": "2023-02-16",
                "revision": 1,
                "runtimeId": "sherpa-onnx",
                "runtimeVersionMin": "1.13.8",
                "runtimeVersionMax": null,
                "languages": ["zh", "en"],
                "capabilities": {
                  "supportsStreaming": true,
                  "supportsPartial": true,
                  "supportsTokenTiming": true,
                  "supportsLanguageDetection": false,
                  "supportsConfidence": false,
                  "supportsInverseTextNormalization": false,
                  "supportsSecondPass": false,
                  "supportsHotwords": false
                },
                "sourceType": "MANAGED_DOWNLOAD",
                "builtinAssetPath": null,
                "download": {
                  "packageFormat": "ZIP",
                  "url": "https://example.invalid/model.zip",
                  "mirrors": [],
                  "sizeBytes": 123,
                  "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                },
                "installedSizeBytes": 456,
                "files": [
                  {
                    "relativePath": "encoder.int8.onnx",
                    "sizeBytes": 456,
                    "sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                    "packagePath": "bundle/encoder.int8.onnx"
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
                  "url": "https://example.invalid/license",
                  "attribution": "upstream",
                  "redistributionPolicy": "VOICA_MIRROR_ALLOWED"
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
        val catalog =
            ModelCatalogCodec.decode(
                """
                {
                  "catalogVersion": 1,
                  "manifestVersion": 3,
                  "channel": "production",
                  "publishedAt": "2026-10-01T00:00:00Z",
                  "manifestDigest": "$digest",
                  "signature": null,
                  "keyId": null,
                  "models": $models
                }
                """.trimIndent(),
            )

        assertEquals(3, catalog.manifestVersion)
        assertEquals(1L, catalog.models.single().revision)
        assertEquals(ModelPackageFormat.ZIP, catalog.models.single().packageFormat)
        assertEquals(
            "bundle/encoder.int8.onnx",
            catalog.models.single().files.single().packagePath,
        )
        assertTrue(catalog.models.single().capabilities.supportsStreaming)
        assertEquals(
            AsrExecutionMode.TRUE_STREAMING,
            catalog.models.single().capabilities.executionMode,
        )
        assertEquals(
            TimestampCapability.TOKEN,
            catalog.models.single().capabilities.timestampCapability,
        )
    }

    @Test
    fun decodesCapabilityV2FieldsWhenPresent() {
        val models =
            """
            [
              {
                "modelId": "zipformer-zh-large",
                "kind": "ASR_STREAMING",
                "displayName": "中文流式识别",
                "version": "2025-06-30",
                "revision": 1,
                "runtimeId": "sherpa-onnx",
                "runtimeVersionMin": "1.13.8",
                "runtimeVersionMax": null,
                "languages": ["zh"],
                "capabilities": {
                  "supportsStreaming": true,
                  "supportsPartial": true,
                  "supportsTokenTiming": true,
                  "supportsLanguageDetection": false,
                  "supportsConfidence": false,
                  "supportsInverseTextNormalization": false,
                  "supportsSecondPass": false,
                  "supportsHotwords": true,
                  "executionMode": "TRUE_STREAMING",
                  "timestampCapability": "TOKEN",
                  "supportsLanguageForcing": true,
                  "punctuationMode": "EXTERNAL",
                  "supportedParameters": ["numThreads", "decodingMethod", "maxActivePaths"]
                },
                "sourceType": "MANAGED_DOWNLOAD",
                "builtinAssetPath": null,
                "download": {
                  "packageFormat": "ZIP",
                  "url": "https://example.invalid/model.zip",
                  "mirrors": [],
                  "sizeBytes": 123,
                  "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                },
                "installedSizeBytes": 456,
                "files": [
                  {
                    "relativePath": "encoder.int8.onnx",
                    "sizeBytes": 456,
                    "sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
                  }
                ],
                "compatibility": {
                  "abis": ["arm64-v8a"],
                  "minSdk": 26,
                  "appVersionMin": 41,
                  "appVersionMax": null
                },
                "license": {
                  "id": "Apache-2.0",
                  "url": "https://example.invalid/license",
                  "attribution": "upstream",
                  "redistributionPolicy": "UPSTREAM_ONLY"
                },
                "sourceUrl": "https://example.invalid/source",
                "homepage": null,
                "releaseChannel": "candidate",
                "autoUpdateEligible": false,
                "deprecated": false,
                "criticalUpdate": false,
                "quantization": "INT8",
                "recommendedDeviceTier": "MID",
                "estimatedPeakRamBytes": 536870912,
                "recommendedProfile": "BALANCED"
              }
            ]
            """.trimIndent()
        val digest = ModelCatalogCodec.computeModelsDigest(models)
        val catalog =
            ModelCatalogCodec.decode(
                """
                {
                  "catalogVersion": 1,
                  "manifestVersion": 7,
                  "channel": "candidate",
                  "publishedAt": "2026-10-04T00:00:00Z",
                  "manifestDigest": "$digest",
                  "signature": null,
                  "keyId": null,
                  "models": $models
                }
                """.trimIndent(),
            )

        val model = catalog.models.single()
        assertEquals(AsrExecutionMode.TRUE_STREAMING, model.capabilities.executionMode)
        assertEquals(TimestampCapability.TOKEN, model.capabilities.timestampCapability)
        assertEquals(ModelPunctuationMode.EXTERNAL, model.capabilities.punctuationMode)
        assertTrue(model.capabilities.supportsLanguageForcing)
        assertEquals(
            setOf("numThreads", "decodingMethod", "maxActivePaths"),
            model.capabilities.supportedParameters,
        )
        assertEquals("INT8", model.quantization)
        assertEquals("MID", model.recommendedDeviceTier)
        assertEquals(536870912L, model.estimatedPeakRamBytes)
        assertEquals("BALANCED", model.recommendedProfile)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDigestMismatch() {
        val models = "[]"
        ModelCatalogCodec.decode(
            """
            {
              "catalogVersion": 1,
              "manifestVersion": 1,
              "channel": "production",
              "publishedAt": null,
              "manifestDigest": "0000000000000000000000000000000000000000000000000000000000000000",
              "signature": null,
              "keyId": null,
              "models": $models
            }
            """.trimIndent(),
        )
    }
}
