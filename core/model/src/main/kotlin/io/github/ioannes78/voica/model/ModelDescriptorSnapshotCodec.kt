package io.github.ioannes78.voica.model

import java.util.Properties

data class ModelDescriptorSnapshot(
    val descriptor: ModelDescriptor,
    val manifestDigest: String,
)

object ModelDescriptorSnapshotCodec {
    private const val FORMAT_VERSION = 2
    private const val MIN_SUPPORTED_FORMAT_VERSION = 1

    fun encode(snapshot: ModelDescriptorSnapshot): Properties {
        require(SHA256_REGEX.matches(snapshot.manifestDigest))
        val d = snapshot.descriptor
        return Properties().apply {
            setProperty("formatVersion", FORMAT_VERSION.toString())
            setProperty("manifestDigest", snapshot.manifestDigest.lowercase())
            setProperty("modelId", d.modelId)
            setProperty("kind", d.kind.name)
            setProperty("displayName", d.displayName)
            setProperty("version", d.version)
            setProperty("revision", d.revision.toString())
            setProperty("runtimeId", d.runtimeId)
            d.runtimeVersionMin?.let { setProperty("runtimeVersionMin", it) }
            d.runtimeVersionMax?.let { setProperty("runtimeVersionMax", it) }
            writeList("languages", d.languages.sorted())
            setProperty("cap.streaming", d.capabilities.supportsStreaming.toString())
            setProperty("cap.partial", d.capabilities.supportsPartial.toString())
            setProperty("cap.tokenTiming", d.capabilities.supportsTokenTiming.toString())
            setProperty("cap.languageDetection", d.capabilities.supportsLanguageDetection.toString())
            setProperty("cap.confidence", d.capabilities.supportsConfidence.toString())
            setProperty("cap.itn", d.capabilities.supportsInverseTextNormalization.toString())
            setProperty("cap.secondPass", d.capabilities.supportsSecondPass.toString())
            setProperty("cap.hotwords", d.capabilities.supportsHotwords.toString())
            setProperty("cap.executionMode", d.capabilities.executionMode.name)
            setProperty("cap.timestampCapability", d.capabilities.timestampCapability.name)
            setProperty("cap.languageForcing", d.capabilities.supportsLanguageForcing.toString())
            setProperty("cap.punctuationMode", d.capabilities.punctuationMode.name)
            writeList("cap.supportedParameters", d.capabilities.supportedParameters.sorted())
            setProperty("sourceType", d.sourceType.name)
            d.builtinAssetPath?.let { setProperty("builtinAssetPath", it) }
            d.packageFormat?.let { setProperty("packageFormat", it.name) }
            d.downloadUrl?.let { setProperty("downloadUrl", it) }
            writeList("mirrors", d.mirrors)
            d.downloadSizeBytes?.let { setProperty("downloadSizeBytes", it.toString()) }
            setProperty("installedSizeBytes", d.installedSizeBytes.toString())
            d.packageSha256?.let { setProperty("packageSha256", it) }
            setProperty("files.count", d.files.size.toString())
            d.files.forEachIndexed { index, file ->
                setProperty("files.$index.relativePath", file.relativePath)
                setProperty("files.$index.sizeBytes", file.sizeBytes.toString())
                setProperty("files.$index.sha256", file.sha256)
                setProperty("files.$index.packagePath", file.packagePath)
            }
            writeList("abis", d.abis.sorted())
            setProperty("minSdk", d.minSdk.toString())
            d.appVersionMin?.let { setProperty("appVersionMin", it.toString()) }
            d.appVersionMax?.let { setProperty("appVersionMax", it.toString()) }
            setProperty("licenseId", d.licenseId)
            d.licenseUrl?.let { setProperty("licenseUrl", it) }
            setProperty("sourceUrl", d.sourceUrl)
            d.homepage?.let { setProperty("homepage", it) }
            setProperty("attribution", d.attribution)
            setProperty("redistributionPolicy", d.redistributionPolicy.name)
            setProperty("releaseChannel", d.releaseChannel)
            setProperty("autoUpdateEligible", d.autoUpdateEligible.toString())
            setProperty("deprecated", d.deprecated.toString())
            setProperty("criticalUpdate", d.criticalUpdate.toString())
            d.speakerRole?.let { setProperty("speakerRole", it.name) }
            d.quantization?.let { setProperty("quantization", it) }
            d.recommendedDeviceTier?.let { setProperty("recommendedDeviceTier", it) }
            d.estimatedPeakRamBytes?.let { setProperty("estimatedPeakRamBytes", it.toString()) }
            d.recommendedProfile?.let { setProperty("recommendedProfile", it) }
            d.runtimeModelType?.let { setProperty("runtimeModelType", it) }
        }
    }

    fun decode(properties: Properties): ModelDescriptorSnapshot {
        val formatVersion = properties.requiredInt("formatVersion")
        require(formatVersion in MIN_SUPPORTED_FORMAT_VERSION..FORMAT_VERSION) {
            "unsupported model descriptor snapshot format"
        }
        val manifestDigest = properties.required("manifestDigest").lowercase()
        require(SHA256_REGEX.matches(manifestDigest))

        val supportsStreaming = properties.requiredBoolean("cap.streaming")
        val supportsSecondPass = properties.requiredBoolean("cap.secondPass")
        val supportsTokenTiming = properties.requiredBoolean("cap.tokenTiming")

        val descriptor =
            ModelDescriptor(
                modelId = properties.required("modelId"),
                kind = enumValueOf(properties.required("kind")),
                displayName = properties.required("displayName"),
                version = properties.required("version"),
                revision = properties.requiredLong("revision"),
                runtimeId = properties.required("runtimeId"),
                runtimeVersionMin = properties.optional("runtimeVersionMin"),
                runtimeVersionMax = properties.optional("runtimeVersionMax"),
                languages = properties.readList("languages").toSet(),
                capabilities =
                    ModelCapabilities(
                        supportsStreaming = supportsStreaming,
                        supportsPartial = properties.requiredBoolean("cap.partial"),
                        supportsTokenTiming = supportsTokenTiming,
                        supportsLanguageDetection =
                            properties.requiredBoolean("cap.languageDetection"),
                        supportsConfidence = properties.requiredBoolean("cap.confidence"),
                        supportsInverseTextNormalization =
                            properties.requiredBoolean("cap.itn"),
                        supportsSecondPass = supportsSecondPass,
                        supportsHotwords = properties.requiredBoolean("cap.hotwords"),
                        executionMode =
                            properties.optional("cap.executionMode")?.let {
                                enumValueOf<AsrExecutionMode>(it)
                            } ?: when {
                                supportsStreaming -> AsrExecutionMode.TRUE_STREAMING
                                supportsSecondPass -> AsrExecutionMode.SECOND_PASS
                                else -> AsrExecutionMode.OFFLINE
                            },
                        timestampCapability =
                            properties.optional("cap.timestampCapability")?.let {
                                enumValueOf<TimestampCapability>(it)
                            } ?: if (supportsTokenTiming) {
                                TimestampCapability.TOKEN
                            } else {
                                TimestampCapability.NONE
                            },
                        supportsLanguageForcing =
                            properties.optionalBoolean("cap.languageForcing") ?: false,
                        punctuationMode =
                            properties.optional("cap.punctuationMode")?.let {
                                enumValueOf<ModelPunctuationMode>(it)
                            } ?: ModelPunctuationMode.NONE,
                        supportedParameters =
                            properties.readOptionalList("cap.supportedParameters").toSet(),
                    ),
                sourceType = enumValueOf(properties.required("sourceType")),
                builtinAssetPath = properties.optional("builtinAssetPath"),
                packageFormat =
                    properties.optional("packageFormat")?.let {
                        enumValueOf<ModelPackageFormat>(it)
                    },
                downloadUrl = properties.optional("downloadUrl"),
                mirrors = properties.readList("mirrors"),
                downloadSizeBytes = properties.optionalLong("downloadSizeBytes"),
                installedSizeBytes = properties.requiredLong("installedSizeBytes"),
                packageSha256 = properties.optional("packageSha256"),
                files =
                    List(properties.requiredInt("files.count")) { index ->
                        ModelFileDescriptor(
                            relativePath = properties.required("files.$index.relativePath"),
                            sizeBytes = properties.requiredLong("files.$index.sizeBytes"),
                            sha256 = properties.required("files.$index.sha256"),
                            packagePath = properties.required("files.$index.packagePath"),
                        )
                    },
                abis = properties.readList("abis").toSet(),
                minSdk = properties.requiredInt("minSdk"),
                appVersionMin = properties.optionalInt("appVersionMin"),
                appVersionMax = properties.optionalInt("appVersionMax"),
                licenseId = properties.required("licenseId"),
                licenseUrl = properties.optional("licenseUrl"),
                sourceUrl = properties.required("sourceUrl"),
                homepage = properties.optional("homepage"),
                attribution = properties.required("attribution"),
                redistributionPolicy =
                    enumValueOf(properties.required("redistributionPolicy")),
                releaseChannel = properties.required("releaseChannel"),
                autoUpdateEligible = properties.requiredBoolean("autoUpdateEligible"),
                deprecated = properties.requiredBoolean("deprecated"),
                criticalUpdate = properties.requiredBoolean("criticalUpdate"),
                speakerRole =
                    properties.optional("speakerRole")?.let {
                        enumValueOf<SpeakerModelRole>(it)
                    },
                quantization = properties.optional("quantization"),
                recommendedDeviceTier = properties.optional("recommendedDeviceTier"),
                estimatedPeakRamBytes = properties.optionalLong("estimatedPeakRamBytes"),
                recommendedProfile = properties.optional("recommendedProfile"),
                runtimeModelType = properties.optional("runtimeModelType"),
            )

        return ModelDescriptorSnapshot(
            descriptor = descriptor,
            manifestDigest = manifestDigest,
        )
    }

    private fun Properties.writeList(
        prefix: String,
        values: List<String>,
    ) {
        setProperty("$prefix.count", values.size.toString())
        values.forEachIndexed { index, value ->
            setProperty("$prefix.$index", value)
        }
    }

    private fun Properties.readList(prefix: String): List<String> =
        List(requiredInt("$prefix.count")) { index ->
            required("$prefix.$index")
        }

    private fun Properties.readOptionalList(prefix: String): List<String> =
        if (getProperty("$prefix.count") == null) {
            emptyList()
        } else {
            readList(prefix)
        }

    private fun Properties.required(key: String): String =
        getProperty(key)?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("missing model metadata field: $key")

    private fun Properties.optional(key: String): String? =
        getProperty(key)?.takeIf { it.isNotEmpty() }

    private fun Properties.requiredLong(key: String): Long =
        required(key).toLong()

    private fun Properties.optionalLong(key: String): Long? =
        optional(key)?.toLong()

    private fun Properties.requiredInt(key: String): Int =
        required(key).toInt()

    private fun Properties.optionalInt(key: String): Int? =
        optional(key)?.toInt()

    private fun Properties.requiredBoolean(key: String): Boolean =
        required(key).toBooleanStrict()

    private fun Properties.optionalBoolean(key: String): Boolean? =
        optional(key)?.toBooleanStrictOrNull()
}
