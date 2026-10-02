package io.github.ioannes78.voica.model

import java.util.Properties

data class ModelDescriptorSnapshot(
    val descriptor: ModelDescriptor,
    val manifestDigest: String,
)

object ModelDescriptorSnapshotCodec {
    private const val FORMAT_VERSION = 1

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
        }
    }

    fun decode(properties: Properties): ModelDescriptorSnapshot {
        require(properties.requiredInt("formatVersion") == FORMAT_VERSION) {
            "unsupported model descriptor snapshot format"
        }
        val manifestDigest = properties.required("manifestDigest").lowercase()
        require(SHA256_REGEX.matches(manifestDigest))

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
                        supportsStreaming = properties.requiredBoolean("cap.streaming"),
                        supportsPartial = properties.requiredBoolean("cap.partial"),
                        supportsTokenTiming = properties.requiredBoolean("cap.tokenTiming"),
                        supportsLanguageDetection =
                            properties.requiredBoolean("cap.languageDetection"),
                        supportsConfidence = properties.requiredBoolean("cap.confidence"),
                        supportsInverseTextNormalization =
                            properties.requiredBoolean("cap.itn"),
                        supportsSecondPass = properties.requiredBoolean("cap.secondPass"),
                        supportsHotwords = properties.requiredBoolean("cap.hotwords"),
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
}
