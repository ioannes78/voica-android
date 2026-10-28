#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

formal_files=(
  "app/src/main/java/io/github/ioannes78/voica/OfflineSummaryCoordinator.kt"
  "app/src/main/java/io/github/ioannes78/voica/OfflineSummaryLongRunner.kt"
  "app/src/main/java/io/github/ioannes78/voica/ui/ai/OfflineSummaryProductViewModel.kt"
  "core/ai/src/main/kotlin/io/github/ioannes78/voica/ai/OfflineSummaryContracts.kt"
  "core/ai/src/main/kotlin/io/github/ioannes78/voica/ai/OfflineSummaryPrompt.kt"
  "core/ai/src/main/kotlin/io/github/ioannes78/voica/ai/OfflineSummaryRepairPrompt.kt"
  "core/ai/src/main/kotlin/io/github/ioannes78/voica/ai/OfflineSummaryResponseFormat.kt"
  "core/ai/src/main/kotlin/io/github/ioannes78/voica/ai/OfflineSummaryBudget.kt"
  "core/ai/src/main/kotlin/io/github/ioannes78/voica/ai/OfflineSummaryLongContracts.kt"
  "core/ai/src/main/kotlin/io/github/ioannes78/voica/ai/OfflineSummaryChunkPlanner.kt"
  "core/ai/src/main/kotlin/io/github/ioannes78/voica/ai/OfflineSummaryLongPrompts.kt"
  "core/ai/src/main/kotlin/io/github/ioannes78/voica/ai/OfflineSummaryIntermediate.kt"
  "core/database/src/main/java/io/github/ioannes78/voica/database/OfflineSummaryPersistenceRepository.kt"
  "core/database/src/main/java/io/github/ioannes78/voica/database/OfflineSummaryLongPersistenceRepository.kt"
  "core/database/src/main/java/io/github/ioannes78/voica/database/OfflineSummaryLongEntities.kt"
  "core/database/src/main/java/io/github/ioannes78/voica/database/OfflineSummaryLongDao.kt"
  "core/database/src/main/java/io/github/ioannes78/voica/database/OfflineSummaryEvidenceMapper.kt"
)

for file in "${formal_files[@]}"; do
  test -f "$file" || {
    echo "::error file=$file::Stage 17 formal offline-summary path file is missing"
    exit 1
  }
done

# Core inference/checkpoint path must stay fully local and must not gain a second background executor.
forbidden='TextLlmProvider|OpenAI|OpenRouter|Volcengine|SiliconFlow|Gemini|Grok|OkHttp|Retrofit|HttpClient|HttpURLConnection|https?://|api[_A-Za-z0-9]*[Kk]ey|multipart|upload(Audio|File|Transcript)?|cloudFallback|cloud_fallback|CoroutineWorker|ListenableWorker|WorkManager'
if grep -En "$forbidden" "${formal_files[@]}"; then
  echo "::error::Stage 17 offline-summary path contains a cloud/network/upload/WorkManager dependency"
  exit 1
fi

coordinator="app/src/main/java/io/github/ioannes78/voica/OfflineSummaryCoordinator.kt"
long_runner="app/src/main/java/io/github/ioannes78/voica/OfflineSummaryLongRunner.kt"
contracts="core/ai/src/main/kotlin/io/github/ioannes78/voica/ai/OfflineSummaryContracts.kt"
long_contracts="core/ai/src/main/kotlin/io/github/ioannes78/voica/ai/OfflineSummaryLongContracts.kt"
chunk_planner="core/ai/src/main/kotlin/io/github/ioannes78/voica/ai/OfflineSummaryChunkPlanner.kt"
long_prompts="core/ai/src/main/kotlin/io/github/ioannes78/voica/ai/OfflineSummaryLongPrompts.kt"
intermediate="core/ai/src/main/kotlin/io/github/ioannes78/voica/ai/OfflineSummaryIntermediate.kt"
long_entities="core/database/src/main/java/io/github/ioannes78/voica/database/OfflineSummaryLongEntities.kt"
long_persistence="core/database/src/main/java/io/github/ioannes78/voica/database/OfflineSummaryLongPersistenceRepository.kt"
prompt="core/ai/src/main/kotlin/io/github/ioannes78/voica/ai/OfflineSummaryPrompt.kt"
repair_prompt="core/ai/src/main/kotlin/io/github/ioannes78/voica/ai/OfflineSummaryRepairPrompt.kt"
response_format="core/ai/src/main/kotlin/io/github/ioannes78/voica/ai/OfflineSummaryResponseFormat.kt"
budget="core/ai/src/main/kotlin/io/github/ioannes78/voica/ai/OfflineSummaryBudget.kt"
local_llm_contracts="engine/local-llm/src/main/kotlin/io/github/ioannes78/voica/localllm/LocalLlmContracts.kt"
local_llm_runtime="engine/local-llm/src/main/kotlin/io/github/ioannes78/voica/localllm/LiteRtLocalLlmRuntime.kt"
model_manager="app/src/main/java/io/github/ioannes78/voica/AppModelManager.kt"
room_db="core/database/src/main/java/io/github/ioannes78/voica/database/VoicaDatabase.kt"
product_card="app/src/main/java/io/github/ioannes78/voica/ui/ai/AiSummaryProductCard.kt"
content_controls="app/src/main/java/io/github/ioannes78/voica/ui/ai/AiSummaryContentControls.kt"

# One application runtime owner. Long execution receives the same generateLocal function.
grep -Fq 'container.localLlmRuntime(application)' "$coordinator"
grep -Fq 'localLlmRuntime.generate(prompt = prompt, config = config)' "$coordinator"
grep -Fq 'backendPolicy = LocalLlmBackendPolicy.AUTO' "$coordinator"
grep -Fq 'generateLocal = generateLocal' "$coordinator"
if grep -Eq 'LiteRtLocalLlmRuntime\s*\(' "$coordinator" "$long_runner"; then
  echo "::error::Stage 17 summary code must not create a parallel LiteRT-LM runtime"
  exit 1
fi
if grep -Eq 'LocalLlmBackendPolicy\.(GPU_ONLY|CPU_ONLY)' "$long_runner"; then
  echo "::error::$long_runner must not override the shared AUTO backend policy"
  exit 1
fi

# Direct Stage 17.2 contract remains frozen.
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

# Stage 17.3 frozen hierarchical contract and budget identity.
grep -Fq 'const val PIPELINE_VERSION = 173001' "$long_contracts"
grep -Fq 'const val PLANNER_VERSION = 173001' "$long_contracts"
grep -Fq 'const val MAP_PROMPT_VERSION = 173101' "$long_contracts"
grep -Fq 'const val REDUCE_PROMPT_VERSION = 173201' "$long_contracts"
grep -Fq 'const val FINAL_PROMPT_VERSION = 173301' "$long_contracts"
grep -Fq 'const val INTERMEDIATE_SCHEMA_VERSION = 1' "$long_contracts"
grep -Fq 'const val PLANNER_TARGET_ESTIMATED_PROMPT_TOKENS = 2_048' "$long_contracts"
grep -Fq 'const val HARD_MAX_ESTIMATED_PROMPT_TOKENS = 2_528' "$long_contracts"
grep -Fq 'hardMaxEstimatedPromptTokens == OfflineSummaryDirectBudgetSpec().maxEstimatedPromptTokens' "$chunk_planner"
grep -Fq 'OfflineSummaryRoute.LONG' "$chunk_planner"
grep -Fq 'MAP 引擎' "$long_prompts"
grep -Fq 'REDUCE 引擎' "$long_prompts"
grep -Fq 'FINAL 引擎' "$long_prompts"
grep -Fq 'OfflineSummaryIntermediateResponseFormat.jsonSchema' "$coordinator"
grep -Fq 'OfflineSummaryIntermediateCodec.decode' "$long_runner"
grep -Fq 'SummaryResultCodec.decode' "$long_runner"

# Long nodes are deliberately serial. No coroutine fan-out is allowed around MAP/REDUCE.
if grep -En '\b(async|launch)\s*\(' "$long_runner"; then
  echo "::error::$long_runner must keep MAP/REDUCE execution serial"
  exit 1
fi
long_generate_calls="$(grep -c 'generateLocal(' "$long_runner")"
if [[ "$long_generate_calls" -ne 2 ]]; then
  echo "::error::$long_runner must contain exactly two structured generateLocal call sites (initial + one repair); found $long_generate_calls"
  exit 1
fi

direct_generate_calls="$(grep -c 'generateLocal(' "$coordinator")"
if [[ "$direct_generate_calls" -ne 2 ]]; then
  echo "::error::$coordinator must retain exactly two Direct generateLocal call sites (initial + one repair); found $direct_generate_calls"
  exit 1
fi

# Durable restart semantics: startup reconciliation cannot generate; Resume is explicit and checked.
grep -Fq 'suspend fun reconcileOnStartup(): Int = store.reconcileOnStartup()' "$long_runner"
grep -Fq 'suspend fun prepareResume(' "$long_runner"
grep -Fq 'suspend fun resume(' "$coordinator"
grep -Fq 'longRunner.prepareResume' "$coordinator"
grep -Fq 'longRunner.executeResume' "$coordinator"
grep -Fq 'longPersistence.prepareResume' "$long_runner"
grep -Fq 'VALIDATED MAP/REDUCE checkpoints are retained exactly' "$long_persistence"
grep -Fq 'explicit [prepareResume] is required' "$long_persistence"
if grep -A8 -F 'suspend fun reconcileOnStartup()' "$long_persistence" | grep -Eq 'generate|infer|conversation|engine'; then
  echo "::error::$long_persistence startup reconciliation must never invoke inference"
  exit 1
fi

# Room v17 remains the sole additive checkpoint schema boundary.
grep -Fq 'tableName = "offline_summary_tasks"' "$long_entities"
grep -Fq 'tableName = "offline_summary_nodes"' "$long_entities"
grep -Fq 'validatedPayloadJson' "$long_entities"
grep -Eq 'version\s*=\s*17' "$room_db"
grep -Fq '.addMigrations(MIGRATION_16_17)' "$room_db"
test -f 'core/database/schemas/io.github.ioannes78.voica.database.VoicaDatabase/16.json'
test -f 'core/database/schemas/io.github.ioannes78.voica.database.VoicaDatabase/17.json'

# Long FINAL deliberately collapses back into the existing summary/product surface.
grep -Fq 'directPersistence.complete(summaryId, result, evidence)' "$long_runner"
grep -Fq 'longPersistence.markCompletedAndCleanup(summaryId)' "$long_runner"
grep -Fq 'container.stage12CContentRepository.onAiSummaryCompleted(recordingId, summaryId)' "$coordinator"
grep -Fq 'AiSummaryContentExportFormatter.build(' "$product_card"
grep -Fq 'textExport.copyToClipboard(document.baseName, document.plainText)' "$product_card"
grep -Fq 'textExport.prepareShare(' "$product_card"
grep -Fq 'TextExportFormat.TXT' "$product_card"
grep -Fq 'TextExportFormat.MARKDOWN' "$product_card"
grep -Fq 'onSeekEvidence(' "$content_controls"
grep -Fq 'evidence.startSampleIndex' "$content_controls"

# Product model remains candidate-only; Stage 17.3 does not promote or swap model/runtime identity.
grep -Fq 'STAGE17_GEMMA_4_E2B_CANDIDATE_ID' "$model_manager"
grep -Fq 'DEBUG_CANDIDATE_MODEL_IDS' "$model_manager"
product_block="$(sed -n '/private val PRODUCT_MODEL_IDS/,/private val DEBUG_CANDIDATE_MODEL_IDS/p' "$model_manager")"
if grep -Fq 'STAGE17_GEMMA_4_E2B_CANDIDATE_ID' <<<"$product_block"; then
  echo "::error::Stage 17.3 must not promote Gemma into PRODUCT_MODEL_IDS"
  exit 1
fi

grep -Fq 'persistence.interruptAllActive()' "$coordinator"

printf '%s\n' \
  'Verified Stage 17.2 + 17.3 offline-summary boundary:' \
  '  - no cloud/HTTP/API-key/upload/WorkManager dependency in Direct or Long inference/checkpoint paths' \
  '  - P1 AppLocalLlmRuntime + AUTO remains the sole LLM execution boundary' \
  '  - Direct budget and at-most-one repair contract remain frozen' \
  '  - Long pipeline/planner/MAP/REDUCE/FINAL/schema versions are frozen' \
  '  - Long hard prompt budget remains exactly the Direct prompt budget' \
  '  - MAP/REDUCE execution remains serial; no coroutine fan-out is allowed' \
  '  - each Long structured node has at most one repair generation' \
  '  - startup reconciliation is persistence-only; Resume remains explicit and identity-checked' \
  '  - Room v17 remains the additive durable checkpoint boundary' \
  '  - Long FINAL reuses the existing summary/history/revision/export/evidence product surface' \
  '  - Gemma remains candidate-only and Long does not force a GPU/CPU backend'
