package io.github.ioannes78.voica.ai

/**
 * LiteRT-LM response-format schema for Stage 17.2 structured summary generation.
 *
 * JSON syntax is constrained in native decoding. Semantic truth remains application-owned:
 * [SummaryResultCodec] still validates enums, evidence/epistemic rules and all downstream fields.
 * Evidence refs are request-specific and come only from the frozen transcript snapshot.
 *
 * Stage 17.2 deliberately bounds the generated document. Constrained decoding must never leave an
 * unbounded string/array branch that can consume the entire output-token envelope before closing
 * the JSON object.
 */
object OfflineSummaryResponseFormat {
    const val MAX_SECTIONS = 4
    const val MAX_ITEMS_PER_SECTION = 2
    const val MAX_EVIDENCE_REFS_PER_ITEM = 3
    const val MAX_TITLE_CHARS = 32
    const val MAX_OVERVIEW_CHARS = 120
    const val MAX_SECTION_ID_CHARS = 16
    const val MAX_SECTION_LABEL_CHARS = 16
    const val MAX_ITEM_ID_CHARS = 16
    const val MAX_ITEM_TEXT_CHARS = 80

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
                "\"maxItems\":$MAX_EVIDENCE_REFS_PER_ITEM,\"items\":{\"type\":\"string\",\"enum\":" +
                    enumJson(allowedEvidenceRefs.toList()) +
                    "}"
            }

        return """
            {
              "x-guidance":{"whitespace_flexible":false},
              "type":"object",
              "additionalProperties":false,
              "properties":{
                "schemaVersion":{"type":"integer","const":${OfflineSummaryPromptContract.RESULT_SCHEMA_VERSION}},
                "contentType":{"type":"string","enum":$contentTypes},
                "classificationConfidence":{"type":["number","null"],"minimum":0,"maximum":1},
                "title":{"type":"string","minLength":1,"maxLength":$MAX_TITLE_CHARS},
                "overview":{"type":"string","minLength":1,"maxLength":$MAX_OVERVIEW_CHARS},
                "sections":{
                  "type":"array",
                  "minItems":1,
                  "maxItems":$MAX_SECTIONS,
                  "items":{
                    "type":"object",
                    "additionalProperties":false,
                    "properties":{
                      "id":{"type":"string","minLength":1,"maxLength":$MAX_SECTION_ID_CHARS},
                      "type":{"type":"string","enum":$sectionTypes},
                      "label":{"type":"string","minLength":1,"maxLength":$MAX_SECTION_LABEL_CHARS},
                      "items":{
                        "type":"array",
                        "minItems":1,
                        "maxItems":$MAX_ITEMS_PER_SECTION,
                        "items":{
                          "type":"object",
                          "additionalProperties":false,
                          "properties":{
                            "id":{"type":"string","minLength":1,"maxLength":$MAX_ITEM_ID_CHARS},
                            "text":{"type":"string","minLength":1,"maxLength":$MAX_ITEM_TEXT_CHARS},
                            "evidenceRefs":{"type":"array",$evidenceItems},
                            "epistemicStatus":{"type":"string","enum":$epistemicStatuses},
                            "attributes":{"type":"object","additionalProperties":false}
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
