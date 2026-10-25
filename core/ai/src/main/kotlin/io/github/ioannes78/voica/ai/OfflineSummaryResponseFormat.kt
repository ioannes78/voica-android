package io.github.ioannes78.voica.ai

/**
 * LiteRT-LM response-format schema for Stage 17.2 structured summary generation.
 *
 * JSON syntax is constrained in native decoding. Semantic truth remains application-owned:
 * [SummaryResultCodec] still validates enums, evidence/epistemic rules and all downstream fields.
 * Evidence refs are request-specific and come only from the frozen transcript snapshot.
 */
object OfflineSummaryResponseFormat {
    fun jsonSchema(allowedEvidenceRefs: Set<String>): String {
        require(allowedEvidenceRefs.all(EVIDENCE_REF_PATTERN::matches)) {
            "response-format evidence refs must be application-issued Sxxxxx values"
        }

        val contentTypes = enumJson(enumValues<AiContentType>().map { it.name })
        val sectionTypes = enumJson(enumValues<AiSummarySectionType>().map { it.name })
        val epistemicStatuses = enumJson(enumValues<AiEpistemicStatus>().map { it.name })
        val evidenceItems =
            if (allowedEvidenceRefs.isEmpty()) {
                "\"maxItems\":0,\"items\":{\"type\":\"string\"}"
            } else {
                "\"items\":{\"type\":\"string\",\"enum\":" +
                    enumJson(allowedEvidenceRefs.toList()) +
                    "}"
            }

        return """
            {
              "type":"object",
              "additionalProperties":false,
              "properties":{
                "schemaVersion":{"type":"integer","const":${OfflineSummaryPromptContract.RESULT_SCHEMA_VERSION}},
                "contentType":{"type":"string","enum":$contentTypes},
                "classificationConfidence":{"type":["number","null"],"minimum":0,"maximum":1},
                "title":{"type":"string","minLength":1},
                "overview":{"type":"string","minLength":1},
                "sections":{
                  "type":"array",
                  "minItems":1,
                  "items":{
                    "type":"object",
                    "additionalProperties":false,
                    "properties":{
                      "id":{"type":"string","minLength":1},
                      "type":{"type":"string","enum":$sectionTypes},
                      "label":{"type":"string","minLength":1},
                      "items":{
                        "type":"array",
                        "minItems":1,
                        "items":{
                          "type":"object",
                          "additionalProperties":false,
                          "properties":{
                            "id":{"type":"string","minLength":1},
                            "text":{"type":"string","minLength":1},
                            "evidenceRefs":{"type":"array",$evidenceItems},
                            "epistemicStatus":{"type":"string","enum":$epistemicStatuses},
                            "attributes":{"type":"object","additionalProperties":{"type":"string"}}
                          },
                          "required":["id","text","evidenceRefs","epistemicStatus","attributes"]
                        }
                      }
                    },
                    "required":["id","type","label","items"]
                  }
                }
              },
              "required":["schemaVersion","contentType","classificationConfidence","title","overview","sections"]
            }
        """.trimIndent()
    }

    private fun enumJson(values: List<String>): String =
        values.joinToString(prefix = "[\"", separator = "\",\"", postfix = "\"]")

    private val EVIDENCE_REF_PATTERN = Regex("""S\d{5,}""")
}
