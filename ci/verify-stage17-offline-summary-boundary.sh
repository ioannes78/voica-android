#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

formal_files=(
  "app/src/main/java/io/github/ioannes78/voica/OfflineSummaryCoordinator.kt"
  "app/src/main/java/io/github/ioannes78/voica/ui/ai/OfflineSummaryProductViewModel.kt"
  "core/ai/src/main/kotlin/io/github/ioannes78/voica/ai/OfflineSummaryContracts.kt"
  "core/ai/src/main/kotlin/io/github/ioannes78/voica/ai/OfflineSummaryPrompt.kt"
  "core/ai/src/main/kotlin/io/github/ioannes78/voica/ai/OfflineSummaryRepairPrompt.kt"
  "core/ai/src/main/kotlin/io/github/ioannes78/voica/ai/OfflineSummaryResponseFormat.kt"
  "core/ai/src/main/kotlin/io/github/ioannes78/voica/ai/OfflineSummaryBudget.kt"
  "core/database/src/main/java/io/github/ioannes78/voica/database/OfflineSummaryPersistenceRepository.kt"
  "core/database/src/main/java/io/github/ioannes78/voica/database/OfflineSummaryEvidenceMapper.kt"
)

for file in "${formal_files[@]}"; do
  test -f "$file" || {
    echo "::error file=$file::Stage 17.2 formal path file is missing"
    exit 1
  }
done

forbidden='TextLlmProvider|OpenAI|OpenRouter|Volcengine|SiliconFlow|Gemini|Grok|OkHttp|Retrofit|HttpClient|HttpURLConnection|https?://|api[_A-Za-z0-9]*[Kk]ey|multipart|upload(Audio|File|Transcript)?|cloudFallback|cloud_fallback|CoroutineWorker|ListenableWorker|WorkManager|ForegroundService'
if grep -En "$forbidden" "${formal_files[@]}"; then
  echo "::error::Stage 17.2 formal inference path contains a cloud/network/upload/background-executor dependency"
  exit 1
fi

coordinator="app/src/main/java/io/github/ioannes78/voica/OfflineSummaryCoordinator.kt"
contracts="core/ai/src/main/kotlin/io/github/ioannes78/voica/ai/OfflineSummaryContracts.kt"
prompt="core/ai/src/main/kotlin/io/github/ioannes78/voica/ai/OfflineSummaryPrompt.kt"
repair_prompt="core/ai/src/main/kotlin/io/github/ioannes78/voica/ai/OfflineSummaryRepairPrompt.kt"
response_format="core/ai/src/main/kotlin/io/github/ioannes78/voica/ai/OfflineSummaryResponseFormat.kt"
budget="core/ai/src/main/kotlin/io/github/ioannes78/voica/ai/OfflineSummaryBudget.kt"
local_llm_contracts="engine/local-llm/src/main/kotlin/io/github/ioannes78/voica/localllm/LocalLlmContracts.kt"
local_llm_runtime="engine/local-llm/src/main/kotlin/io/github/ioannes78/voica/localllm/LiteRtLocalLlmRuntime.kt"
model_manager="app/src/main/java/io/github/ioannes78/voica/AppModelManager.kt"
room_db="core/database/src/main/java/io/github/ioannes78/voica/database/VoicaDatabase.kt"

grep -Fq 'container.localLlmRuntime(application)' "$coordinator"
grep -Fq 'localLlmRuntime.generate(prompt = prompt, config = config)' "$coordinator"
grep -Fq 'backendPolicy = LocalLlmBackendPolicy.AUTO' "$coordinator"
if grep -Eq 'LiteRtLocalLlmRuntime\s*\(' "$coordinator"; then
  echo "::error::$coordinator must not create a parallel LiteRT-LM runtime"
  exit 1
fi

grep -Fq 'const val PROMPT_VERSION: Int = 170204' "$contracts"
grep -Fq 'val runtimeMaxTokens: Int = 4_000' "$budget"
grep -Fq 'val outputReserveTokens: Int = 1_024' "$budget"
grep -Fq 'val runtimeTemplateReserveTokens: Int = 192' "$budget"
grep -Fq 'val safetyReserveTokens: Int = 256' "$budget"
grep -Fq 'requireFits(prompt)' "$coordinator"
grep -Fq 'repairPromptRenderer.render' "$coordinator"

grep -Fq 'constrained-decoding schema' "$prompt"
grep -Fq '只保留最重要的信息，宁少勿滥' "$prompt"
grep -Fq 'Evidence Catalogue' "$repair_prompt"
grep -Fq '未列出的 ref 不得作为新的替换值' "$repair_prompt"
grep -Fq 'val evidence = unit.evidence ?: return@mapIndexedNotNull null' "$repair_prompt"
grep -Fq 'estimator.estimateTokens(trialPrompt) <= budgetSpec.maxEstimatedPromptTokens' "$repair_prompt"
grep -Fq 'BEGIN_INVALID_JSON_DATA' "$repair_prompt"
grep -Fq 'validationError.code == SummaryStructuredOutputErrorCode.TRUNCATED_JSON' "$repair_prompt"

grep -Fq 'OfflineSummaryResponseFormat.jsonSchema(allowedEvidenceRefs)' "$coordinator"
grep -Fq 'const val MAX_SECTIONS = 4' "$response_format"
grep -Fq 'const val MAX_ITEMS_PER_SECTION = 2' "$response_format"
grep -Fq 'const val MAX_EVIDENCE_REFS_PER_ITEM = 3' "$response_format"
grep -Fq 'const val MAX_OVERVIEW_CHARS = 120' "$response_format"
grep -Fq 'const val MAX_ITEM_TEXT_CHARS = 80' "$response_format"
grep -Fq '"x-guidance":{"whitespace_flexible":false}' "$response_format"
grep -Fq '"evidenceRefs":{"type":"array",$evidenceItems}' "$response_format"
grep -Fq 'enumJson(allowedEvidenceRefs.toList())' "$response_format"
grep -Fq '"attributes":{"type":"object","additionalProperties":false}' "$response_format"
grep -Fq 'val responseJsonSchema: String? = null' "$local_llm_contracts"
grep -Fq 'ResponseFormat.json(schema)' "$local_llm_runtime"
grep -Fq 'enableResponseFormat = config.responseJsonSchema != null' "$local_llm_runtime"
grep -Fq 'responseFormat = responseFormat' "$local_llm_runtime"
grep -Fq 'maxOutputToken = config.maxOutputTokens' "$local_llm_runtime"
grep -Fq 'maxOutputTokens = null' "$local_llm_contracts"
grep -Fq 'responseJsonSchema = null' "$local_llm_contracts"

grep -Fq 'STAGE17_GEMMA_4_E2B_CANDIDATE_ID' "$model_manager"
grep -Fq 'DEBUG_CANDIDATE_MODEL_IDS' "$model_manager"
product_block="$(sed -n '/private val PRODUCT_MODEL_IDS/,/private val DEBUG_CANDIDATE_MODEL_IDS/p' "$model_manager")"
if grep -Fq 'STAGE17_GEMMA_4_E2B_CANDIDATE_ID' <<<"$product_block"; then
  echo "::error::Stage 17.2 must not promote Gemma into PRODUCT_MODEL_IDS"
  exit 1
fi

grep -Eq 'version\s*=\s*17' "$room_db"
grep -Fq '.addMigrations(MIGRATION_16_17)' "$room_db"
test -f 'core/database/schemas/io.github.ioannes78.voica.database.VoicaDatabase/16.json'
test -f 'core/database/schemas/io.github.ioannes78.voica.database.VoicaDatabase/17.json'

generate_calls="$(grep -c 'generateLocal(' "$coordinator")"
if [[ "$generate_calls" -ne 2 ]]; then
  echo "::error::$coordinator must contain exactly two Stage 17.2 generateLocal call sites (initial + one repair); found $generate_calls"
  exit 1
fi

grep -Fq 'persistence.interruptAllActive()' "$coordinator"

printf '%s\n' \
  'Verified Stage 17.2 offline-summary boundary carried into Stage 17.3 P2:' \
  '  - no cloud/HTTP/API-key/upload/FGS/WorkManager dependency in the frozen direct inference path' \
  '  - P1 AppLocalLlmRuntime + AUTO remains sole LLM execution boundary' \
  '  - direct budget and at-most-one repair structure are frozen' \
  '  - prompt 04 requires concise output while native JSON Schema owns structural syntax' \
  '  - native response format constrains JSON shape, document size and request-specific Evidence Ref allowlist' \
  '  - truncated JSON is never sent through a second long LLM repair' \
  '  - strict SummaryResultCodec validation remains downstream of constrained decoding' \
  '  - 1,024 output-token reserve remains enforced in LiteRT-LM while P0 baseline remains uncapped' \
  '  - Gemma remains candidate-only' \
  '  - Room v17 adds only the Stage 17.3 additive checkpoint boundary over frozen v16 data' \
  '  - Stage 17.2 direct restart recovery remains interrupt-only at P2'
