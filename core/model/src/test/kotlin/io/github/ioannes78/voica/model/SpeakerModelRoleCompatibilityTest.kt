package io.github.ioannes78.voica.model

import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeakerModelRoleCompatibilityTest {
    @Test
    fun stage8ManifestWithoutSpeakerRoleStillDecodes() {
        val models =
            """
            [
              {
                "modelId": "speaker-legacy",
                "kind": "SPEAKER",
                "displayName": "Legacy Speaker",
                "version": "1",
                "revision": 1,
                "runtimeId": "sherpa-onnx",
                "runtimeVersionMin": "1.13.8",
                "runtimeVersionMax": null,
                "languages": ["zh"],
                "capabilities": {
                  "supportsStreaming": false,
                  "supportsPartial": false,
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
                  "url": "https://example.invalid/speaker.onnx",
                  "mirrors": [],
                  "sizeBytes": 10,
                  "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                },
                "installedSizeBytes": 10,
                "files": [
                  {
                    "relativePath": "speaker.onnx",
                    "sizeBytes": 10,
                    "sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
                  }
                ],
                "compatibility": {
                  "abis": ["arm64-v8a"],
                  "minSdk": 26,
                  "appVersionMin": 23,
                  "appVersionMax": null
                },
                "license": {
                  "id": "Apache-2.0",
                  "url": null,
                  "attribution": "upstream",
                  "redistributionPolicy": "UPSTREAM_ONLY"
                },
                "sourceUrl": "https://example.invalid/source",
                "homepage": null,
                "releaseChannel": "production",
                "autoUpdateEligible": false
              }
            ]
            """.trimIndent()

        val digest = ModelCatalogCodec.computeModelsDigest(models)
        val catalog =
            ModelCatalogCodec.decode(
                """
                {
                  "catalogVersion": 1,
                  "manifestVersion": 4,
                  "channel": "production",
                  "publishedAt": null,
                  "manifestDigest": "$digest",
                  "models": $models
                }
                """.trimIndent(),
            )

        assertNull(catalog.models.single().speakerRole)
    }

    @Test
    fun stage9ManifestDecodesEmbeddingRole() {
        val models =
            """
            [
              {
                "modelId": "eres2net-base-zh-stage9",
                "kind": "SPEAKER",
                "speakerRole": "EMBEDDING",
                "displayName": "ERes2Net Base 中文",
                "version": "stage9",
                "revision": 1,
                "runtimeId": "sherpa-onnx",
                "runtimeVersionMin": "1.13.8",
                "runtimeVersionMax": null,
                "languages": ["zh"],
                "capabilities": {
                  "supportsStreaming": false,
                  "supportsPartial": false,
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
                  "url": "https://example.invalid/eres2net.onnx",
                  "mirrors": [],
                  "sizeBytes": 39593761,
                  "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                },
                "installedSizeBytes": 39593761,
                "files": [
                  {
                    "relativePath": "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx",
                    "sizeBytes": 39593761,
                    "sha256": "1a331345f04805badbb495c775a6ddffcdd1a732567d5ec8b3d5749e3c7a5e4b"
                  }
                ],
                "compatibility": {
                  "abis": ["arm64-v8a"],
                  "minSdk": 26,
                  "appVersionMin": 24,
                  "appVersionMax": null
                },
                "license": {
                  "id": "Apache-2.0",
                  "url": null,
                  "attribution": "3D-Speaker upstream",
                  "redistributionPolicy": "UPSTREAM_ONLY"
                },
                "sourceUrl": "https://example.invalid/source",
                "homepage": null,
                "releaseChannel": "candidate",
                "autoUpdateEligible": false
              }
            ]
            """.trimIndent()

        val digest = ModelCatalogCodec.computeModelsDigest(models)
        val catalog =
            ModelCatalogCodec.decode(
                """
                {
                  "catalogVersion": 1,
                  "manifestVersion": 5,
                  "channel": "candidate",
                  "publishedAt": null,
                  "manifestDigest": "$digest",
                  "models": $models
                }
                """.trimIndent(),
            )

        assertEquals(SpeakerModelRole.EMBEDDING, catalog.models.single().speakerRole)
    }

    @Test
    fun descriptorSnapshotRoundTripPreservesSpeakerRole() {
        val descriptor =
            ModelDescriptor(
                modelId = "speaker-test",
                kind = ModelKind.SPEAKER,
                displayName = "Speaker Test",
                version = "1",
                revision = 1,
                runtimeId = "sherpa-onnx",
                runtimeVersionMin = "1.13.8",
                runtimeVersionMax = null,
                languages = setOf("zh"),
                capabilities = ModelCapabilities(),
                sourceType = ModelSourceType.MANAGED_DOWNLOAD,
                builtinAssetPath = null,
                packageFormat = ModelPackageFormat.SINGLE_FILE,
                downloadUrl = "https://example.invalid/model.onnx",
                downloadSizeBytes = 10,
                packageSha256 =
                    "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                installedSizeBytes = 10,
                files =
                    listOf(
                        ModelFileDescriptor(
                            relativePath = "model.onnx",
                            sizeBytes = 10,
                            sha256 =
                                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                        ),
                    ),
                abis = setOf("arm64-v8a"),
                minSdk = 26,
                appVersionMin = 24,
                appVersionMax = null,
                licenseId = "Apache-2.0",
                licenseUrl = null,
                sourceUrl = "https://example.invalid/source",
                homepage = null,
                attribution = "upstream",
                redistributionPolicy = RedistributionPolicy.UPSTREAM_ONLY,
                releaseChannel = "candidate",
                autoUpdateEligible = false,
                speakerRole = SpeakerModelRole.EMBEDDING,
            )
        val digest =
            "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"

        val encoded =
            ModelDescriptorSnapshotCodec.encode(
                ModelDescriptorSnapshot(descriptor, digest),
            )
        val decoded = ModelDescriptorSnapshotCodec.decode(Properties().apply { putAll(encoded) })

        assertEquals(SpeakerModelRole.EMBEDDING, decoded.descriptor.speakerRole)
        assertEquals(descriptor.modelId, decoded.descriptor.modelId)
        assertEquals(digest, decoded.manifestDigest)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSpeakerRoleOnNonSpeakerModel() {
        ModelDescriptor(
            modelId = "vad-test",
            kind = ModelKind.VAD,
            displayName = "VAD Test",
            version = "1",
            revision = 1,
            runtimeId = "sherpa-onnx",
            runtimeVersionMin = null,
            runtimeVersionMax = null,
            languages = setOf("zh"),
            capabilities = ModelCapabilities(),
            sourceType = ModelSourceType.MANAGED_DOWNLOAD,
            builtinAssetPath = null,
            packageFormat = ModelPackageFormat.SINGLE_FILE,
            downloadUrl = "https://example.invalid/model.onnx",
            downloadSizeBytes = 10,
            packageSha256 =
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            installedSizeBytes = 10,
            files =
                listOf(
                    ModelFileDescriptor(
                        relativePath = "model.onnx",
                        sizeBytes = 10,
                        sha256 =
                            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                    ),
                ),
            abis = setOf("arm64-v8a"),
            minSdk = 26,
            appVersionMin = null,
            appVersionMax = null,
            licenseId = "MIT",
            licenseUrl = null,
            sourceUrl = "https://example.invalid/source",
            homepage = null,
            attribution = "upstream",
            redistributionPolicy = RedistributionPolicy.UPSTREAM_ONLY,
            releaseChannel = "candidate",
            autoUpdateEligible = false,
            speakerRole = SpeakerModelRole.EMBEDDING,
        )
    }
}
