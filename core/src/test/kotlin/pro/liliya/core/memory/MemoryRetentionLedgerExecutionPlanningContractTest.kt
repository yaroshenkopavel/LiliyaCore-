package pro.liliya.core.memory

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class MemoryRetentionLedgerExecutionPlanningContractTest {
    private val builder = MemoryRetentionLedgerExecutionPlanBuilder()
    private val createdAt = Instant.parse("2026-09-17T12:00:00Z")

    @Test
    fun `keep-only ledger produces no execution plan`() {
        val result = builder.build(
            id = MemoryRetentionExecutionPlanId("keep-only"),
            ledger = MemoryRetentionLedger(
                listOf(
                    entry(
                        id = "keep",
                        generation = 7,
                        retentionClass = MemoryRetentionClass.WORKING,
                        disposition = MemoryRetentionDisposition.RETAINED,
                        action = MemoryRetentionShadowAction.KEEP
                    )
                )
            ),
            createdAt = createdAt
        )

        assertEquals(MemoryRetentionLedgerExecutionPlanResult.NothingToPrune, result)
    }

    @Test
    fun `one prune candidate becomes one exact single-target plan and keep entries stay out`() {
        val result = assertIs<MemoryRetentionLedgerExecutionPlanResult.SingleTarget>(
            builder.build(
                id = MemoryRetentionExecutionPlanId("single"),
                ledger = MemoryRetentionLedger(
                    listOf(
                        entry(
                            id = "kept-private-record",
                            generation = 3,
                            retentionClass = MemoryRetentionClass.WORKING,
                            disposition = MemoryRetentionDisposition.RETAINED,
                            action = MemoryRetentionShadowAction.KEEP
                        ),
                        entry(
                            id = "prune-me",
                            generation = 11,
                            retentionClass = MemoryRetentionClass.EPISODIC,
                            disposition = MemoryRetentionDisposition.RECORD_BUDGET_REJECTED,
                            action = MemoryRetentionShadowAction.PRUNE_CANDIDATE
                        )
                    )
                ),
                createdAt = createdAt
            )
        )

        assertEquals(MemoryRetentionTransactionId("single"), result.plan.id)
        assertEquals(createdAt, result.plan.createdAt)
        assertEquals(
            listOf(
                MemoryRetentionTransactionTarget(
                    recordId = MemoryRecordId("prune-me"),
                    generation = MemoryGeneration(11),
                    retentionClass = MemoryRetentionClass.EPISODIC,
                    disposition = MemoryRetentionDisposition.RECORD_BUDGET_REJECTED
                )
            ),
            result.plan.targets
        )
    }

    @Test
    fun `multiple prune candidates become deterministic canonical multi-target plan`() {
        val result = assertIs<MemoryRetentionLedgerExecutionPlanResult.MultiTarget>(
            builder.build(
                id = MemoryRetentionExecutionPlanId("multi"),
                ledger = MemoryRetentionLedger(
                    listOf(
                        entry(
                            id = "z-episodic",
                            generation = 9,
                            retentionClass = MemoryRetentionClass.EPISODIC,
                            disposition = MemoryRetentionDisposition.CONTENT_BUDGET_REJECTED,
                            action = MemoryRetentionShadowAction.PRUNE_CANDIDATE
                        ),
                        entry(
                            id = "b-working",
                            generation = 5,
                            retentionClass = MemoryRetentionClass.WORKING,
                            disposition = MemoryRetentionDisposition.RECORD_BUDGET_REJECTED,
                            action = MemoryRetentionShadowAction.PRUNE_CANDIDATE
                        ),
                        entry(
                            id = "a-working",
                            generation = 8,
                            retentionClass = MemoryRetentionClass.WORKING,
                            disposition = MemoryRetentionDisposition.DUPLICATE_SUPPRESSED,
                            action = MemoryRetentionShadowAction.PRUNE_CANDIDATE,
                            duplicateOf = MemoryRecordId("kept-source")
                        )
                    )
                ),
                createdAt = createdAt
            )
        )

        assertEquals(MemoryRetentionMultiTargetId("multi"), result.plan.id)
        assertEquals(
            listOf("a-working", "b-working", "z-episodic"),
            result.plan.targets.map { it.recordId.value }
        )
        assertEquals(listOf(8L, 5L, 9L), result.plan.targets.map { it.generation.value })
    }

    @Test
    fun `ambiguous duplicate record ids fail closed before execution planning`() {
        val duplicateId = MemoryRecordId("same")
        val ledger = MemoryRetentionLedger(
            listOf(
                MemoryRetentionLedgerEntry(
                    recordId = duplicateId,
                    generation = MemoryGeneration(1),
                    retentionClass = MemoryRetentionClass.WORKING,
                    disposition = MemoryRetentionDisposition.RETAINED,
                    action = MemoryRetentionShadowAction.KEEP
                ),
                MemoryRetentionLedgerEntry(
                    recordId = duplicateId,
                    generation = MemoryGeneration(2),
                    retentionClass = MemoryRetentionClass.WORKING,
                    disposition = MemoryRetentionDisposition.RECORD_BUDGET_REJECTED,
                    action = MemoryRetentionShadowAction.PRUNE_CANDIDATE
                )
            )
        )

        assertFailsWith<IllegalArgumentException> {
            builder.build(
                id = MemoryRetentionExecutionPlanId("duplicate"),
                ledger = ledger,
                createdAt = createdAt
            )
        }
    }

    private fun entry(
        id: String,
        generation: Long,
        retentionClass: MemoryRetentionClass,
        disposition: MemoryRetentionDisposition,
        action: MemoryRetentionShadowAction,
        duplicateOf: MemoryRecordId? = null
    ): MemoryRetentionLedgerEntry = MemoryRetentionLedgerEntry(
        recordId = MemoryRecordId(id),
        generation = MemoryGeneration(generation),
        retentionClass = retentionClass,
        disposition = disposition,
        action = action,
        duplicateOf = duplicateOf
    )
}
