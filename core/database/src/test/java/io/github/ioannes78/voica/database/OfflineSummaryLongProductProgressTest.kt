package io.github.ioannes78.voica.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineSummaryLongProductProgressTest {
    @Test
    fun mappingUsesDurableMapNodePositionForOneBasedProgress() {
        val task = task(status = OfflineSummaryTaskStateValue.MAPPING, currentLevel = 0, totalLeafChunks = 3)
        val progress =
            buildOfflineSummaryLongProductProgress(
                task,
                listOf(
                    node("map-1", OfflineSummaryNodeTypeValue.MAP, 0, 0, OfflineSummaryNodeStateValue.VALIDATED),
                    node("map-2", OfflineSummaryNodeTypeValue.MAP, 0, 1, OfflineSummaryNodeStateValue.PENDING),
                    node("map-3", OfflineSummaryNodeTypeValue.MAP, 0, 2, OfflineSummaryNodeStateValue.PENDING),
                ),
            )

        assertEquals(OfflineSummaryLongProductStage.MAPPING, progress?.stage)
        assertEquals(2, progress?.current)
        assertEquals(3, progress?.total)
        assertNull(progress?.level)
    }

    @Test
    fun reducingUsesCurrentDurableLevelAndRunningNodePosition() {
        val task = task(status = OfflineSummaryTaskStateValue.REDUCING, currentLevel = 2, totalLeafChunks = 4)
        val progress =
            buildOfflineSummaryLongProductProgress(
                task,
                listOf(
                    node("old", OfflineSummaryNodeTypeValue.REDUCE, 1, 0, OfflineSummaryNodeStateValue.VALIDATED),
                    node("reduce-1", OfflineSummaryNodeTypeValue.REDUCE, 2, 0, OfflineSummaryNodeStateValue.VALIDATED),
                    node("reduce-2", OfflineSummaryNodeTypeValue.REDUCE, 2, 1, OfflineSummaryNodeStateValue.RUNNING),
                    node("reduce-3", OfflineSummaryNodeTypeValue.REDUCE, 2, 2, OfflineSummaryNodeStateValue.PENDING),
                ),
            )

        assertEquals(OfflineSummaryLongProductStage.REDUCING, progress?.stage)
        assertEquals(2, progress?.level)
        assertEquals(2, progress?.current)
        assertEquals(3, progress?.total)
    }

    @Test
    fun planningAndValidatingAreStructuredWhileTerminalTasksDoNotProjectAsRunning() {
        assertEquals(
            OfflineSummaryLongProductStage.PLANNING,
            buildOfflineSummaryLongProductProgress(
                task(OfflineSummaryTaskStateValue.PLANNING, 0, 2),
                emptyList(),
            )?.stage,
        )
        assertEquals(
            OfflineSummaryLongProductStage.VALIDATING,
            buildOfflineSummaryLongProductProgress(
                task(OfflineSummaryTaskStateValue.VALIDATING, 1, 2),
                emptyList(),
            )?.stage,
        )
        assertNull(
            buildOfflineSummaryLongProductProgress(
                task(OfflineSummaryTaskStateValue.COMPLETED, 1, 2),
                emptyList(),
            ),
        )
    }

    private fun task(
        status: String,
        currentLevel: Int,
        totalLeafChunks: Int,
    ): OfflineSummaryTaskEntity =
        OfflineSummaryTaskEntity(
            aiSummaryId = "summary",
            pipelineVersion = 1,
            plannerVersion = 1,
            mapPromptVersion = 1,
            reducePromptVersion = 1,
            finalPromptVersion = 1,
            intermediateSchemaVersion = 1,
            inputContentDigest = "a".repeat(64),
            route = OfflineSummaryTaskRouteValue.LONG,
            directPromptEstimatedTokens = 3_000,
            status = status,
            currentLevel = currentLevel,
            totalLeafChunks = totalLeafChunks,
            completedLeafChunks = 0,
            createdAtMs = 1L,
            updatedAtMs = 1L,
        )

    private fun node(
        id: String,
        type: String,
        level: Int,
        ordinal: Int,
        status: String,
    ): OfflineSummaryNodeEntity =
        OfflineSummaryNodeEntity(
            id = id,
            aiSummaryId = "summary",
            nodeType = type,
            level = level,
            ordinal = ordinal,
            sourceStartUnitOrdinal = ordinal,
            sourceEndUnitOrdinalExclusive = ordinal + 1,
            inputNodeIdsJson = "[]",
            sourceRefsJson = "[\"U00001\"]",
            evidenceRefsJson = "[\"S00001\"]",
            inputDigest = "b".repeat(64),
            estimatedPromptTokens = 100,
            status = status,
            attemptCount = 0,
            validatedPayloadJson = null,
            errorCode = null,
            sanitizedErrorMessage = null,
            createdAtMs = 1L,
            updatedAtMs = 1L,
            completedAtMs = null,
        )
}
