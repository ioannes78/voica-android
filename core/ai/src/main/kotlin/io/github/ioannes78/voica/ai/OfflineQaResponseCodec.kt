package io.github.ioannes78.voica.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class OfflineQaOutputError {
    INVALID_JSON, INVALID_SHAPE, INVALID_STATUS, INVALID_EVIDENCE, NUMERIC_MISMATCH, TOO_LARGE,
}

class OfflineQaOutputException(val code: OfflineQaOutputError) : IllegalArgumentException(code.name)

/** No extraction, coercion, silent deduplication or acceptance of extra fields. */
object OfflineQaResponseCodec {
    const val MAX_RAW_CHARS = 8192
    private val json = Json { isLenient = false; ignoreUnknownKeys = false }

    fun decode(raw: String, context: OfflineQaContext): OfflineQaResult {
        if (raw.length > MAX_RAW_CHARS) fail(OfflineQaOutputError.TOO_LARGE)
        rejectDuplicateKeysAndDeepNesting(raw)
        val root = try {
            json.parseToJsonElement(raw) as? JsonObject ?: fail(OfflineQaOutputError.INVALID_SHAPE)
        } catch (error: OfflineQaOutputException) {
            throw error
        } catch (_: IllegalArgumentException) {
            fail(OfflineQaOutputError.INVALID_JSON)
        }
        if (root.keys != setOf("schemaVersion", "status", "claims")) fail(OfflineQaOutputError.INVALID_SHAPE)
        val version = root["schemaVersion"] as? JsonPrimitive ?: fail(OfflineQaOutputError.INVALID_SHAPE)
        if (version.isString || version.content != OfflineQaPromptContract.SCHEMA_VERSION.toString()) fail(OfflineQaOutputError.INVALID_SHAPE)
        val status = OfflineQaStatus.entries.firstOrNull { it.name == string(root["status"]) }
            ?: fail(OfflineQaOutputError.INVALID_STATUS)
        val array = root["claims"] as? JsonArray ?: fail(OfflineQaOutputError.INVALID_SHAPE)
        if (array.size > OfflineQaPromptContract.MAX_CLAIMS) fail(OfflineQaOutputError.INVALID_SHAPE)
        if ((status == OfflineQaStatus.INSUFFICIENT_EVIDENCE) != array.isEmpty()) fail(OfflineQaOutputError.INVALID_STATUS)
        val claims = array.map { element ->
            val claim = element as? JsonObject ?: fail(OfflineQaOutputError.INVALID_SHAPE)
            if (claim.keys != setOf("text", "evidenceIds")) fail(OfflineQaOutputError.INVALID_SHAPE)
            val text = string(claim["text"])
            if (text.isBlank() || text.codePointCount(0, text.length) > OfflineQaPromptContract.MAX_CLAIM_CHARS) fail(OfflineQaOutputError.INVALID_SHAPE)
            val refs = (claim["evidenceIds"] as? JsonArray ?: fail(OfflineQaOutputError.INVALID_SHAPE)).map { string(it) }
            if (refs.size !in 1..OfflineQaPromptContract.MAX_REFS_PER_CLAIM ||
                refs.distinct().size != refs.size || refs.any { it !in context.allowedRefs }
            ) fail(OfflineQaOutputError.INVALID_EVIDENCE)
            OfflineQaClaim(text, refs)
        }
        val result = OfflineQaResult(context, status, claims)
        OfflineQaEvidenceValidator.validate(result)
        return result
    }

    private fun string(value: kotlinx.serialization.json.JsonElement?): String {
        val primitive = value as? JsonPrimitive ?: fail(OfflineQaOutputError.INVALID_SHAPE)
        if (!primitive.isString) fail(OfflineQaOutputError.INVALID_SHAPE)
        return primitive.content
    }

    /** JsonObject normally collapses duplicate keys; detect them before parsing. */
    private fun rejectDuplicateKeysAndDeepNesting(raw: String) {
        val stack = mutableListOf<MutableSet<String>?>()
        var i = 0
        while (i < raw.length) {
            when (raw[i]) {
                '{', '[' -> {
                    stack.add(if (raw[i] == '{') mutableSetOf() else null)
                    if (stack.size > 8) fail(OfflineQaOutputError.INVALID_SHAPE)
                    i++
                }
                '}', ']' -> {
                    if (stack.isEmpty()) fail(OfflineQaOutputError.INVALID_JSON)
                    stack.removeAt(stack.lastIndex)
                    i++
                }
                '"' -> {
                    val start = i++
                    while (i < raw.length && raw[i] != '"') {
                        if (raw[i] == '\\') i++
                        i++
                    }
                    if (i >= raw.length) fail(OfflineQaOutputError.INVALID_JSON)
                    i++
                    var next = i
                    while (next < raw.length && raw[next].isWhitespace()) next++
                    if (next < raw.length && raw[next] == ':') {
                        val key = try {
                            (json.parseToJsonElement(raw.substring(start, i)) as JsonPrimitive).content
                        } catch (_: IllegalArgumentException) {
                            fail(OfflineQaOutputError.INVALID_JSON)
                        }
                        val keys = stack.lastOrNull() ?: fail(OfflineQaOutputError.INVALID_JSON)
                        if (!keys.add(key)) fail(OfflineQaOutputError.INVALID_SHAPE)
                    }
                }
                else -> i++
            }
        }
        if (stack.isNotEmpty()) fail(OfflineQaOutputError.INVALID_JSON)
    }

    private fun fail(code: OfflineQaOutputError): Nothing = throw OfflineQaOutputException(code)
}

/** Only one repair for structural failures; no automatic retry for evidence/numeric errors. */
object OfflineQaRepairPolicy {
    fun mayRepair(error: OfflineQaOutputError, attempts: Int, nativeReleaseConfirmed: Boolean): Boolean =
        attempts == 0 && nativeReleaseConfirmed && error in setOf(
            OfflineQaOutputError.INVALID_JSON, OfflineQaOutputError.INVALID_SHAPE, OfflineQaOutputError.INVALID_STATUS,
        )

    // Fixed instruction only. Never insert untrusted raw model output into a new prompt.
    const val INSTRUCTION = "上一次输出结构不合法。重新根据原始证据生成符合 Schema 的唯一完整 JSON；不要补造事实或引用。"
}
