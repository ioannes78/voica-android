package io.github.ioannes78.voica.model

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

object ModelCatalogCodec {
    private val json = Json {
        isLenient = false
        allowTrailingComma = false
    }

    fun decode(text: String): ModelCatalog {
        val root = json.parseToJsonElement(text).jsonObject
        val models = root.requiredArray("models")
        val declaredDigest = root.requiredString("manifestDigest").lowercase()
        val actualDigest = digestModels(models)
        require(declaredDigest == actualDigest) {
            "model manifest digest mismatch"
        }

        return ModelCatalog(
            catalogVersion = root.requiredInt("catalogVersion"),
            manifestVersion = root.requiredInt("manifestVersion"),
            channel = root.requiredString("channel"),
            publishedAt = root.optionalString("publishedAt"),
            manifestDigest = declaredDigest,
            signature = root.optionalString("signature"),
            keyId = root.optionalString("keyId"),
            models = models.map { decodeModel(it.jsonObject) },
        )
    }

    internal fun computeModelsDigest(modelsJson: String): String =
        digestModels(json.parseToJsonElement(modelsJson).jsonArray)

    private fun digestModels(models: JsonArray): String {
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(models.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte ->
            "%02x".format(byte)
        }
    }

    private fun decodeModel(obj: JsonObject): ModelDescriptor {
        val download = obj["download"] as? JsonObject
        val compatibility = obj.requiredObject("compatibility")
        val license = obj.requiredObject("license")
        val capabilities = obj.requiredObject("capabilities")

        return ModelDescriptor(
            modelId = obj.requiredString("modelId"),
            kind = enumValueOf(obj.requiredString("kind")),
            displayName = obj.requiredString("displayName"),
            version = obj.requiredString("version"),
            revision = obj.requiredLong("revision"),
            runtimeId = obj.requiredString("runtimeId"),
            runtimeVersionMin = obj.optionalString("runtimeVersionMin"),
            runtimeVersionMax = obj.optionalString("runtimeVersionMax"),
            languages = obj.requiredArray("languages").mapTo(linkedSetOf()) {
                it.jsonPrimitive.content
            },
            capabilities =
                ModelCapabilities(
                    supportsStreaming = capabilities.boolean("supportsStreaming"),
                    supportsPartial = capabilities.boolean("supportsPartial"),
                    supportsTokenTiming = capabilities.boolean("supportsTokenTiming"),
                    supportsLanguageDetection = capabilities.boolean("supportsLanguageDetection"),
                    supportsConfidence = capabilities.boolean("supportsConfidence"),
                    supportsInverseTextNormalization =
                        capabilities.boolean("supportsInverseTextNormalization"),
                    supportsSecondPass = capabilities.boolean("supportsSecondPass"),
                    supportsHotwords = capabilities.boolean("supportsHotwords"),
                ),
            sourceType = enumValueOf(obj.requiredString("sourceType")),
            builtinAssetPath = obj.optionalString("builtinAssetPath"),
            packageFormat =
                download?.optionalString("packageFormat")?.let {
                    enumValueOf<ModelPackageFormat>(it)
                },
            downloadUrl = download?.optionalString("url"),
            mirrors =
                download?.get("mirrors")?.jsonArray?.map {
                    it.jsonPrimitive.content
                } ?: emptyList(),
            downloadSizeBytes = download?.optionalLong("sizeBytes"),
            installedSizeBytes = obj.requiredLong("installedSizeBytes"),
            packageSha256 = download?.optionalString("sha256"),
            files =
                obj.requiredArray("files").map { fileElement ->
                    val file = fileElement.jsonObject
                    ModelFileDescriptor(
                        relativePath = file.requiredString("relativePath"),
                        sizeBytes = file.requiredLong("sizeBytes"),
                        sha256 = file.requiredString("sha256"),
                        packagePath =
                            file.optionalString("packagePath")
                                ?: file.requiredString("relativePath"),
                    )
                },
            abis =
                compatibility.requiredArray("abis").mapTo(linkedSetOf()) {
                    it.jsonPrimitive.content
                },
            minSdk = compatibility.requiredInt("minSdk"),
            appVersionMin = compatibility.optionalInt("appVersionMin"),
            appVersionMax = compatibility.optionalInt("appVersionMax"),
            licenseId = license.requiredString("id"),
            licenseUrl = license.optionalString("url"),
            sourceUrl = obj.requiredString("sourceUrl"),
            homepage = obj.optionalString("homepage"),
            attribution = license.requiredString("attribution"),
            redistributionPolicy =
                enumValueOf(license.requiredString("redistributionPolicy")),
            releaseChannel = obj.requiredString("releaseChannel"),
            autoUpdateEligible = obj.requiredBoolean("autoUpdateEligible"),
            deprecated = obj.optionalBoolean("deprecated") ?: false,
            criticalUpdate = obj.optionalBoolean("criticalUpdate") ?: false,
        )
    }

    private fun JsonObject.requiredString(name: String): String =
        this[name]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("missing string: $name")

    private fun JsonObject.optionalString(name: String): String? =
        (this[name] as? JsonPrimitive)?.content?.takeUnless { it == "null" }

    private fun JsonObject.requiredInt(name: String): Int =
        this[name]?.jsonPrimitive?.int
            ?: throw IllegalArgumentException("missing int: $name")

    private fun JsonObject.optionalInt(name: String): Int? =
        (this[name] as? JsonPrimitive)?.content?.toIntOrNull()

    private fun JsonObject.requiredLong(name: String): Long =
        this[name]?.jsonPrimitive?.long
            ?: throw IllegalArgumentException("missing long: $name")

    private fun JsonObject.optionalLong(name: String): Long? =
        (this[name] as? JsonPrimitive)?.content?.toLongOrNull()

    private fun JsonObject.requiredBoolean(name: String): Boolean =
        this[name]?.jsonPrimitive?.boolean
            ?: throw IllegalArgumentException("missing boolean: $name")

    private fun JsonObject.optionalBoolean(name: String): Boolean? =
        (this[name] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    private fun JsonObject.boolean(name: String): Boolean =
        requiredBoolean(name)

    private fun JsonObject.requiredArray(name: String): JsonArray =
        this[name]?.jsonArray
            ?: throw IllegalArgumentException("missing array: $name")

    private fun JsonObject.requiredObject(name: String): JsonObject =
        this[name]?.jsonObject
            ?: throw IllegalArgumentException("missing object: $name")
}
