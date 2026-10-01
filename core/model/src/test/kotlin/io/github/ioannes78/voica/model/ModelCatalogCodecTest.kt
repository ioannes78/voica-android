package io.github.ioannes78.voica.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogCodecTest {
    @Test
    fun decodesPinnedProductionManifest() {
        val catalog =
            ModelCatalogCodec.decode(
                """
                {
                  "catalogVersion": 1,
                  "manifestVersion": 3,
                  "channel": "production",
                  "publishedAt": "2026-10-01T00:00:00Z",
                  "manifestDigest": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "signature": null,
                  "keyId": null,
                  "models": [
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
    }
}
