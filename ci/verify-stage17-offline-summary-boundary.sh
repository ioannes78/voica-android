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

# The formal inference path must stay local. ModelManager's network-backed install/update lifecycle is
# intentionally outside this list and is not an inference dependency.
forbidden='TextLlmProvider|OpenAI|OpenRouter|Volcengine|SiliconFlow|Gemini|Grok|OkHttp|Retrofit|HttpClient|HttpURLConnection|https?://|api[_A-Za-z0-9]*[Kk]ey|multipart|upload(Audio|File|Transcript)?|cloudFallback|cloud_fallback|CoroutineWorker|ListenableWorker|WorkManager|ForegroundService'
if grep -En "$forbidden" "${formal_files[@]}"; then
  echo "::error::Stage 17.2 formal inference path contains a cloud/network/upload/background-executor dependency"
  exit 1
fi

coordinator="app/src/main/java/io/github/ioannes78/voica/OfflineSummaryCoordinator.kt"
contracts="core/ai/src/main/kotlin/io/github/ioannes78/voica/ai/OfflineSummaryContracts.kt"
repair_prompt="core/ai/src/main/kotlin/io/github/ioannes78/voica/ai/OfflineSummaryRepairPrompt.kt"
budget="core/ai/src/main/kotlin/io/github/ioannes78/voica/ai/OfflineSummaryBudget.kt"
local_llm_contracts="engine/local-llm/src/main/kotlin/io/github/ioannes78/voica/localllm/LocalLlmContracts.kt"
local_llm_runtime="engine/local-llm/src/main/kotlin/io/github/ioannes78/voica/localllm/LiteRtLocalLlmRuntime.kt"
model_manager="app/src/main/java/io/github/ioannes78/voica/AppModelManager.kt"
room_db="core/database/src/main/java/io/github/ioannes78/voica/database/VoicaDatabase.kt"

# Runtime ownership and backend policy must stay on the P1 application boundary.
grep -Fq 'container.localLlmRuntime(application)' "$coordinator"
grep -Fq 'localLlmRuntime.generate(prompt = prompt, config = config)' "$coordinator"
grep -Fq 'backendPolicy = LocalLlmBackendPolicy.AUTO' "$coordinator"
if grep -Eq 'LiteRtLocalLlmRuntime\s*\(' "$coordinator"; then
  echo "::error::$coordinator must not create a parallel LiteRT-LM runtime"
  exit 1
fi

# Frozen Stage 17.2 prompt/model/direct-budget identity.
grep -Fq 'const val PROMPT_VERSION: Int = 170202' "$contracts"
grep -Fq 'val runtimeMaxTokens: Int = 4_000' "$budget"
grep -Fq 'val outputReserveTokens: Int = 1_024' "$budget"
grep -Fq 'val runtimeTemplateReserveTokens: Int = 192' "$budget"
grep -Fq 'val safetyReserveTokens: Int = 256' "$budget"
grep -Fq 'requireFits(prompt)' "$coordinator"
grep -Fq 'repairPromptRenderer.render' "$coordinator"

# Repair must remain grounded in the frozen evidence catalogue; invalid refs are never guessed/replaced.
grep -Fq 'Evidence Catalogue' "$repair_prompt"
grep -Fq '不得猜 ref' "$repair_prompt"
grep -Fq 'unit.evidence ?: return@mapNotNull null' "$repair_prompt"

# The 1,024-token output reserve must be enforced by LiteRT-LM, not merely reserved in estimation.
grep -Fq 'val maxOutputTokens: Int? = 1_024' "$local_llm_contracts"
grep -Fq 'maxOutputToken = config.maxOutputTokens' "$local_llm_runtime"
grep -Fq 'maxOutputTokens = null' "$local_llm_contracts"

# Gemma remains candidate-only until a separately authorized promotion.
grep -Fq 'STAGE17_GEMMA_4_E2B_CANDIDATE_ID' "$model_manager"
grep -Fq 'DEBUG_CANDIDATE_MODEL_IDS' "$model_manager"
product_block="$(sed -n '/private val PRODUCT_MODEL_IDS/,/private val DEBUG_CANDIDATE_MODEL_IDS/p' "$model_manager")"
if grep -Fq 'STAGE17_GEMMA_4_E2B_CANDIDATE_ID' <<<"$product_block"; then
  echo "::error::Stage 17.2 must not promote Gemma into PRODUCT_MODEL_IDS"
  exit 1
fi

# Room v16 is frozen for Stage 17.2.
grep -Eq 'version\s*=\s*16' "$room_db"
test -f 'core/database/schemas/io.github.ioannes78.voica.database.VoicaDatabase/16.json'

# The one-repair rule must remain structurally bounded: one initial call and one repair call site.
generate_calls="$(grep -c 'generateLocal(' "$coordinator")"
if [[ "$generate_calls" -ne 2 ]]; then
  echo "::error::$coordinator must contain exactly two generateLocal call sites (initial + one repair); found $generate_calls"
  exit 1
fi

# Restart recovery must interrupt, never resume/regenerate.
grep -Fq 'persistence.interruptAllActive()' "$coordinator"

printf '%s\n' \
  'Verified Stage 17.2 offline-summary boundary:' \
  '  - no cloud/HTTP/API-key/upload/FGS/WorkManager dependency in formal inference path' \
  '  - P1 AppLocalLlmRuntime + AUTO remains sole LLM execution boundary' \
  '  - direct budget and exactly-one repair structure are frozen' \
  '  - prompt 02 repair is grounded in frozen evidence catalogue; invalid refs are not guessed' \
  '  - 1,024 output-token reserve is enforced in LiteRT-LM while P0 baseline remains uncapped' \
  '  - Gemma remains candidate-only' \
  '  - Room remains v16' \
  '  - restart recovery is interrupt-only'
