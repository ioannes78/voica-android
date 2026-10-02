package io.github.ioannes78.voica.llm

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import io.github.ioannes78.voica.ai.ProviderCapabilities
import io.github.ioannes78.voica.ai.ProviderProfile
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

interface ProviderCredentialResolver {
    suspend fun resolve(credentialRef: String): String?
}

interface ProviderCredentialStore : ProviderCredentialResolver {
    suspend fun put(
        credentialRef: String,
        secret: String,
    )

    suspend fun delete(credentialRef: String)
}

class AndroidKeystoreCredentialStore(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ProviderCredentialStore {
    private val root = File(context.applicationContext.noBackupFilesDir, "llm-credentials")
    private val keyLock = Any()

    override suspend fun put(
        credentialRef: String,
        secret: String,
    ) = withContext(ioDispatcher) {
        validateRef(credentialRef)
        require(secret.isNotBlank())
        root.mkdirs()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey())
        cipher.updateAAD(credentialRef.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        require(iv.size in 1..255)
        val payload = ByteArray(2 + iv.size + encrypted.size)
        payload[0] = FORMAT_VERSION
        payload[1] = iv.size.toByte()
        iv.copyInto(payload, 2)
        encrypted.copyInto(payload, 2 + iv.size)

        val atomic = AtomicFile(fileFor(credentialRef))
        val output = atomic.startWrite()
        try {
            output.write(payload)
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    override suspend fun resolve(credentialRef: String): String? =
        withContext(ioDispatcher) {
            validateRef(credentialRef)
            val file = fileFor(credentialRef)
            if (!file.exists()) return@withContext null
            val payload = AtomicFile(file).openRead().use { it.readBytes() }
            require(payload.size >= 3 && payload[0] == FORMAT_VERSION) {
                "unsupported credential payload"
            }
            val ivLength = payload[1].toInt() and 0xff
            require(ivLength in 1 until payload.size - 1)
            val iv = payload.copyOfRange(2, 2 + ivLength)
            val encrypted = payload.copyOfRange(2 + ivLength, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                loadOrCreateKey(),
                GCMParameterSpec(128, iv),
            )
            cipher.updateAAD(credentialRef.toByteArray(Charsets.UTF_8))
            cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        }

    override suspend fun delete(credentialRef: String) =
        withContext(ioDispatcher) {
            validateRef(credentialRef)
            AtomicFile(fileFor(credentialRef)).delete()
        }

    private fun fileFor(ref: String): File {
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(ref.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        return File(root, "$digest.bin")
    }

    private fun loadOrCreateKey(): SecretKey =
        synchronized(keyLock) {
            val keyStore =
                KeyStore.getInstance(ANDROID_KEYSTORE).apply {
                    load(null)
                }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)
                ?: KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE,
                ).run {
                    init(
                        KeyGenParameterSpec.Builder(
                            KEY_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setRandomizedEncryptionRequired(true)
                            .build(),
                    )
                    generateKey()
                }
        }

    private fun validateRef(value: String) {
        require(REF_PATTERN.matches(value)) { "invalid credential ref" }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "io.github.ioannes78.voica.llm.credentials.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val FORMAT_VERSION: Byte = 1
        val REF_PATTERN = Regex("""[A-Za-z0-9._-]{1,128}""")
    }
}

data class ProviderProfileSnapshot(
    val profiles: List<ProviderProfile>,
    val defaultProfileId: String?,
)

interface ProviderProfileStore {
    suspend fun load(): ProviderProfileSnapshot

    suspend fun upsert(profile: ProviderProfile)

    suspend fun delete(providerProfileId: String)

    suspend fun setDefault(providerProfileId: String?)
}

class AppPrivateProviderProfileStore(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ProviderProfileStore {
    private val file =
        AtomicFile(
            File(
                context.applicationContext.noBackupFilesDir,
                "llm-provider-profiles.json",
            ),
        )
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(): ProviderProfileSnapshot =
        mutex.withLock {
            withContext(ioDispatcher) {
                readUnlocked()
            }
        }

    override suspend fun upsert(profile: ProviderProfile) {
        validateProfile(profile)
        mutex.withLock {
            withContext(ioDispatcher) {
                val current = readUnlocked()
                val profiles =
                    current.profiles
                        .filterNot { it.providerProfileId == profile.providerProfileId } +
                        profile
                writeUnlocked(current.copy(profiles = profiles))
            }
        }
    }

    override suspend fun delete(providerProfileId: String) {
        require(providerProfileId.isNotBlank())
        mutex.withLock {
            withContext(ioDispatcher) {
                val current = readUnlocked()
                writeUnlocked(
                    current.copy(
                        profiles =
                            current.profiles.filterNot {
                                it.providerProfileId == providerProfileId
                            },
                        defaultProfileId =
                            current.defaultProfileId.takeUnless {
                                it == providerProfileId
                            },
                    ),
                )
            }
        }
    }

    override suspend fun setDefault(providerProfileId: String?) {
        mutex.withLock {
            withContext(ioDispatcher) {
                val current = readUnlocked()
                require(
                    providerProfileId == null ||
                        current.profiles.any { it.providerProfileId == providerProfileId },
                ) { "default provider profile does not exist" }
                writeUnlocked(current.copy(defaultProfileId = providerProfileId))
            }
        }
    }

    private fun readUnlocked(): ProviderProfileSnapshot {
        if (!file.baseFile.exists()) {
            return ProviderProfileSnapshot(emptyList(), null)
        }
        val root =
            json.parseToJsonElement(
                file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() },
            ).jsonObject
        val profiles =
            root["profiles"]?.jsonArray
                ?.map { decodeProfile(it.jsonObject) }
                .orEmpty()
        val defaultId =
            root["defaultProfileId"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { id -> profiles.any { it.providerProfileId == id } }
        return ProviderProfileSnapshot(profiles, defaultId)
    }

    private fun writeUnlocked(snapshot: ProviderProfileSnapshot) {
        file.baseFile.parentFile?.mkdirs()
        val root =
            buildJsonObject {
                put("schemaVersion", 1)
                snapshot.defaultProfileId?.let { put("defaultProfileId", it) }
                put(
                    "profiles",
                    buildJsonArray {
                        snapshot.profiles
                            .sortedBy { it.providerProfileId }
                            .forEach { add(encodeProfile(it)) }
                    },
                )
            }
        val output = file.startWrite()
        try {
            val writer = output.writer(Charsets.UTF_8)
            writer.write(root.toString())
            writer.flush()
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    private fun encodeProfile(profile: ProviderProfile): JsonObject =
        buildJsonObject {
            put("providerProfileId", profile.providerProfileId)
            put("presetId", profile.presetId)
            put("displayName", profile.displayName)
            put("baseUrl", profile.baseUrl)
            put("credentialRef", profile.credentialRef)
            put("defaultModel", profile.defaultModel)
            put("timeoutMs", profile.timeoutMs)
            profile.manualContextWindowTokens?.let { put("manualContextWindowTokens", it) }
            put("enabled", profile.enabled)
            profile.capabilityOverrides?.let {
                put("capabilityOverrides", encodeCapabilities(it))
            }
            put(
                "extraParameters",
                buildJsonObject {
                    profile.extraParameters.toSortedMap().forEach { (key, value) ->
                        put(key, value)
                    }
                },
            )
        }

    private fun decodeProfile(value: JsonObject): ProviderProfile =
        ProviderProfile(
            providerProfileId = value.requiredString("providerProfileId"),
            presetId = value.requiredString("presetId"),
            displayName = value.requiredString("displayName"),
            baseUrl = value.requiredString("baseUrl"),
            credentialRef = value.requiredString("credentialRef"),
            defaultModel = value.requiredString("defaultModel"),
            timeoutMs = value["timeoutMs"]?.jsonPrimitive?.longOrNull ?: 30_000L,
            manualContextWindowTokens =
                value["manualContextWindowTokens"]?.jsonPrimitive?.intOrNull,
            capabilityOverrides =
                value["capabilityOverrides"]?.jsonObject?.let(::decodeCapabilities),
            enabled = value["enabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
            extraParameters =
                value["extraParameters"]?.jsonObject
                    ?.mapValues { it.value.jsonPrimitive.content }
                    .orEmpty(),
        ).also(::validateProfile)

    private fun encodeCapabilities(value: ProviderCapabilities): JsonObject =
        buildJsonObject {
            put("supportsModelDiscovery", value.supportsModelDiscovery)
            put("supportsStreaming", value.supportsStreaming)
            put("supportsNonStreaming", value.supportsNonStreaming)
            put("supportsJsonObject", value.supportsJsonObject)
            put("supportsJsonSchema", value.supportsJsonSchema)
            value.contextWindowTokens?.let { put("contextWindowTokens", it) }
            value.maxOutputTokens?.let { put("maxOutputTokens", it) }
            put("reportsUsage", value.reportsUsage)
            put("supportsCancellation", value.supportsCancellation)
        }

    private fun decodeCapabilities(value: JsonObject): ProviderCapabilities =
        ProviderCapabilities(
            supportsModelDiscovery = value.boolean("supportsModelDiscovery"),
            supportsStreaming = value.boolean("supportsStreaming"),
            supportsNonStreaming = value.boolean("supportsNonStreaming", true),
            supportsJsonObject = value.boolean("supportsJsonObject"),
            supportsJsonSchema = value.boolean("supportsJsonSchema"),
            contextWindowTokens = value["contextWindowTokens"]?.jsonPrimitive?.intOrNull,
            maxOutputTokens = value["maxOutputTokens"]?.jsonPrimitive?.intOrNull,
            reportsUsage = value.boolean("reportsUsage"),
            supportsCancellation = value.boolean("supportsCancellation", true),
        )

    private fun validateProfile(profile: ProviderProfile) {
        require(profile.providerProfileId.isNotBlank())
        require(profile.presetId.isNotBlank())
        require(profile.displayName.isNotBlank())
        require(profile.baseUrl.startsWith("https://")) { "provider base URL requires HTTPS" }
        require(profile.credentialRef.isNotBlank())
        require(profile.timeoutMs in 1_000L..300_000L)
        val manualContextWindowTokens = profile.manualContextWindowTokens
        require(manualContextWindowTokens == null || manualContextWindowTokens > 0)
    }

    private fun JsonObject.requiredString(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: error("missing provider profile field: $key")

    private fun JsonObject.boolean(
        key: String,
        default: Boolean = false,
    ): Boolean =
        this[key]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: default
}

class ProviderConfigurationRepository(
    private val profileStore: ProviderProfileStore,
    private val credentialStore: ProviderCredentialStore,
) {
    suspend fun save(
        profile: ProviderProfile,
        apiKey: String?,
    ) {
        if (apiKey != null) {
            credentialStore.put(profile.credentialRef, apiKey)
        } else {
            require(credentialStore.resolve(profile.credentialRef) != null) {
                "provider credential is missing"
            }
        }
        profileStore.upsert(profile)
    }

    suspend fun delete(providerProfileId: String) {
        val current = profileStore.load()
        val profile =
            current.profiles.firstOrNull { it.providerProfileId == providerProfileId }
                ?: return
        profileStore.delete(providerProfileId)
        credentialStore.delete(profile.credentialRef)
    }
}
