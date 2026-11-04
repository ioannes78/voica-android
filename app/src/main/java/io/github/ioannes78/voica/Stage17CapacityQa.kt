package io.github.ioannes78.voica

import io.github.ioannes78.voica.ai.ConservativeOfflineSummaryTokenEstimator
import io.github.ioannes78.voica.localllm.LocalLlmBackendPolicy
import io.github.ioannes78.voica.localllm.LocalLlmRuntimeConfig
import io.github.ioannes78.voica.localllm.LocalLlmValidatedPresets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * QA-only Stage 17.3 capacity matrix. It never alters Direct/MAP/REDUCE/FINAL,
 * Room checkpoints, product model identity, production budgets, or release schemas.
 * Each native attempt is user-triggered individually in the existing .qa-only P1 screen.
 */
internal object Stage17CapacityQa {
    const val BASE_CONTEXT_TOKENS = 4_000
    const val EXTENDED_CONTEXT_TOKENS = 8_000
    const val EXPERIMENTAL_CONTEXT_TOKENS = 16_000
    const val BASE_OUTPUT_TOKENS = 1_024
    const val EXPERIMENTAL_OUTPUT_TOKENS = 2_048
    const val OUTPUT_SAMPLE_FACTS = 16

    data class Request(
        val label: String,
        val prompt: String,
        val config: LocalLlmRuntimeConfig,
        val estimatedPromptTokens: Int,
        val factIds: Set<String> = emptySet(),
        val expectedRecord: String? = null,
    )

    fun contextProbe(maxContextTokens: Int, longInput: Boolean): Request {
        require(maxContextTokens in setOf(BASE_CONTEXT_TOKENS, EXTENDED_CONTEXT_TOKENS, EXPERIMENTAL_CONTEXT_TOKENS))
        val target =
            when {
                maxContextTokens == BASE_CONTEXT_TOKENS -> 2_200
                !longInput -> 2_200 // same prompt size isolates KV-cache configuration cost
                maxContextTokens == EXTENDED_CONTEXT_TOKENS -> 5_000
                else -> 12_500
            }
        val builder = StringBuilder(
            "你是端侧运行时容量诊断助手。这是合成测试数据，不是真实转写。" +
                "请阅读全部内容，只返回一句中文，包含最后一条测试记录的编号。\n" +
                "<SYNTHETIC_TRANSCRIPT>\n",
        )
        var count = 0
        while (ConservativeOfflineSummaryTokenEstimator.estimateTokens(builder.toString()) < target) {
            count++
            require(count <= 500) { "QA synthetic capacity prompt did not converge" }
            builder.append("测试记录")
                .append(count.toString().padStart(4, '0'))
                .append("：本段讨论人工智能在研发、医疗、能源和制造业的应用；" +
                    "仅供上下文长度验证，不能作为真实结论。\n")
        }
        builder.append("</SYNTHETIC_TRANSCRIPT>\n请回答最后一条测试记录的编号。")
        val prompt = builder.toString()
        val estimate = ConservativeOfflineSummaryTokenEstimator.estimateTokens(prompt)
        require(estimate + BASE_OUTPUT_TOKENS + 192 + 256 <= maxContextTokens) {
            "capacity probe would exceed runtime budget"
        }
        return Request(
            label = "容量 ${maxContextTokens / 1_000}K / 输入≈$estimate",
            prompt = prompt,
            config = LocalLlmValidatedPresets.P0_BASELINE.copy(
                backendPolicy = LocalLlmBackendPolicy.CPU,
                maxNumTokens = maxContextTokens,
                maxOutputTokens = BASE_OUTPUT_TOKENS,
            ),
            estimatedPromptTokens = estimate,
            expectedRecord = count.toString().padStart(4, '0'),
        )
    }

    fun outputProbe(expanded: Boolean): Request =
        if (expanded) outputProbe(16, EXPERIMENTAL_OUTPUT_TOKENS, EXTENDED_CONTEXT_TOKENS)
        else outputProbe(8, BASE_OUTPUT_TOKENS, BASE_CONTEXT_TOKENS)

    fun outputProbe(maxItems: Int, outputTokens: Int, contextTokens: Int): Request {
        require(maxItems in setOf(8, 16))
        require(outputTokens in setOf(BASE_OUTPUT_TOKENS, EXPERIMENTAL_OUTPUT_TOKENS))
        require(contextTokens in setOf(BASE_CONTEXT_TOKENS, EXTENDED_CONTEXT_TOKENS))
        val context = contextTokens
        val output = outputTokens
        val facts = (1..OUTPUT_SAMPLE_FACTS).map { n ->
            "F" + n.toString().padStart(2, '0')
        }
        val schema = outputSchema(maxItems, facts)
        val source = facts.mapIndexed { index, id ->
            "$id | ${SAMPLE_TOPICS[index % SAMPLE_TOPICS.size]}：这是第${index + 1}条互不相同的合成事实，" +
                "用于验证输出容量，不能当作真实统计数据。"
        }.joinToString("\n")
        val prompt =
            "闻知本地总结容量 QA。下面是16条合成测试事实，不是真实转写；" +
                "不得添写资料外数字。按重要性摘录，尽量保留不同 factId，" +
                "每个 factId 只能使用一次。最多输出 $maxItems 条。只输出符合 schema 的 JSON。\n" +
                "<QA_FACTS>\n$source\n</QA_FACTS>"
        val estimate = ConservativeOfflineSummaryTokenEstimator.estimateTokens(prompt)
        require(estimate + output + 192 + 256 <= context)
        return Request(
            label = "输出 ${maxItems}条 / ${output}token / ${context / 1_000}K",
            prompt = prompt,
            config = LocalLlmValidatedPresets.P0_BASELINE.copy(
                backendPolicy = LocalLlmBackendPolicy.CPU,
                maxNumTokens = context,
                maxOutputTokens = output,
                responseJsonSchema = schema,
            ),
            estimatedPromptTokens = estimate,
            factIds = facts.toSet(),
        )
    }

    fun evaluateOutput(raw: String, allowedFacts: Set<String>): String {
        return try {
            val root = Json.parseToJsonElement(raw).jsonObject
            val items = root["items"]?.jsonArray.orEmpty()
            val ids = items.mapNotNull { it.jsonObject["factId"]?.jsonPrimitive?.content }
            val valid = ids.filter(allowedFacts::contains)
            val distinct = valid.toSet()
            val invalid = ids.filterNot(allowedFacts::contains)
            "capacityJson=PASS; items=${items.size}; validIds=${valid.size}; " +
                "distinctFacts=${distinct.size}/${allowedFacts.size}; duplicates=${valid.size - distinct.size}; " +
                "unknownIds=${invalid.size}; sourceCoverage=${distinct.size * 100 / allowedFacts.size}%"
        } catch (error: Exception) {
            "capacityJson=FAIL:${error::class.java.simpleName}; ${error.message?.take(100)}"
        }
    }

    private fun outputSchema(maxItems: Int, facts: List<String>): String {
        require(maxItems in setOf(8, 16))
        val ids = facts.joinToString(",") { "\"${it}\"" }
        return """{"type":"object","additionalProperties":false,
          "properties":{"items":{"type":"array","minItems":1,"maxItems":$maxItems,
          "items":{"type":"object","additionalProperties":false,
          "properties":{"factId":{"type":"string","enum":[$ids]},
          "text":{"type":"string","minLength":1,"maxLength":120}},
          "required":["factId","text"]}}},
          "required":["items"]}""".trimIndent()
    }

    private val SAMPLE_TOPICS = listOf(
        "AI应用渗透", "研发投入", "能源消耗", "企业生产率",
        "医疗设备", "算力建设", "技术治理", "自动驾驶",
    )
}
