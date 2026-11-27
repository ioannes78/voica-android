#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RUNNER = ROOT / "app/src/main/java/io/github/ioannes78/voica/OfflineSummaryLongRunner.kt"


def replace_once(old: str, new: str) -> None:
    text = RUNNER.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(
            f"{RUNNER}: expected exactly one failure-diagnostic injection match, found {count}"
        )
    RUNNER.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(
    '''                recordDiagnostic(summaryId, "MAP ${index + 1}/${plan.chunks.size} OUTPUT", generated.text)
                anyRepair = anyRepair || generated.repairAttempted
                store.checkpointIntermediate(node.id, generated.text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                runCatching { store.markNodeFailed(node.id, nodeErrorCode(error), sanitize(error.message)) }
                throw error
            }''',
    '''                recordDiagnostic(summaryId, "MAP ${index + 1}/${plan.chunks.size} OUTPUT", generated.text)
                anyRepair = anyRepair || generated.repairAttempted
                store.checkpointIntermediate(node.id, generated.text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                recordFailureDiagnostic(
                    summaryId = summaryId,
                    stage = "MAP ${index + 1}/${plan.chunks.size}",
                    nodeId = node.id,
                    error = error,
                )
                runCatching { store.markNodeFailed(node.id, nodeErrorCode(error), sanitize(error.message)) }
                publishDiagnostic(summaryId)
                throw error
            }''',
)

replace_once(
    '''                    recordDiagnostic(
                        summaryId,
                        "REDUCE level=$level ${index + 1}/${groups.size} OUTPUT",
                        generated.text,
                    )
                    anyRepair = anyRepair || generated.repairAttempted
                    store.checkpointIntermediate(node.id, generated.text)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    runCatching { store.markNodeFailed(node.id, nodeErrorCode(error), sanitize(error.message)) }
                    throw error
                }''',
    '''                    recordDiagnostic(
                        summaryId,
                        "REDUCE level=$level ${index + 1}/${groups.size} OUTPUT",
                        generated.text,
                    )
                    anyRepair = anyRepair || generated.repairAttempted
                    store.checkpointIntermediate(node.id, generated.text)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    recordFailureDiagnostic(
                        summaryId = summaryId,
                        stage = "REDUCE level=$level ${index + 1}/${groups.size}",
                        nodeId = node.id,
                        error = error,
                    )
                    runCatching { store.markNodeFailed(node.id, nodeErrorCode(error), sanitize(error.message)) }
                    publishDiagnostic(summaryId)
                    throw error
                }''',
)

replace_once(
    '''        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            runCatching { store.markNodeFailed(finalNode.id, nodeErrorCode(error), sanitize(error.message)) }
            throw error
        }
    }

    private data class GeneratedStructured(''',
    '''        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            recordFailureDiagnostic(
                summaryId = summaryId,
                stage = "FINAL",
                nodeId = finalNode.id,
                error = error,
            )
            runCatching { store.markNodeFailed(finalNode.id, nodeErrorCode(error), sanitize(error.message)) }
            publishDiagnostic(summaryId)
            throw error
        }
    }

    private data class GeneratedStructured(''',
)

replace_once(
    '''    private suspend fun publishDiagnostic(summaryId: String): String? =
        try {
            diagnostics.publish(summaryId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }

    private fun buildPlanDiagnostic(''',
    '''    private suspend fun publishDiagnostic(summaryId: String): String? =
        try {
            diagnostics.publish(summaryId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }

    private suspend fun recordFailureDiagnostic(
        summaryId: String,
        stage: String,
        nodeId: String,
        error: Throwable,
    ) {
        val code = nodeErrorCode(error)
        val message = sanitize(error.message).orEmpty()
        recordDiagnostic(
            summaryId = summaryId,
            section = "$stage FAILURE",
            body = buildString {
                appendLine("nodeId=$nodeId")
                appendLine("errorCode=$code")
                appendLine("exceptionClass=${error::class.java.name}")
                appendLine("message=$message")
            },
        )
    }

    private fun buildPlanDiagnostic(''',
)
