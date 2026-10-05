package pro.liliya.core.memory

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import pro.liliya.core.persistence.PersistentSchemaVersion

class MemoryRetentionMultiTargetSchemaCompatibilityContractTest {
    private val createdAt = Instant.parse("2026-09-17T14:45:00Z")

    @Test
    fun version_two_encodes_single_target_parent_and_round_trips() {
        val plan = plan("v2-single", listOf(target("one", 1)))
        val encoded = MemoryRetentionMultiTargetPersistentCodec.encode(
            plan,
            nextIndex = 0,
            state = MemoryRetentionMultiTargetState.ACTIVE
        )

        assertEquals(PersistentSchemaVersion(2), encoded.schemaVersion)
        val decoded = assertIs<MemoryRetentionMultiTargetPersistentDecodeResult.Decoded>(
            MemoryRetentionMultiTargetPersistentCodec.decode(encoded)
        )
        assertEquals(plan, decoded.plan)
        assertEquals(0, decoded.nextIndex)
        assertEquals(MemoryRetentionMultiTargetState.ACTIVE, decoded.state)
    }

    @Test
    fun legacy_version_one_two_target_record_remains_readable() {
        val plan = plan(
            "legacy-v1",
            listOf(target("a", 2), target("b", 3))
        )
        val v2 = MemoryRetentionMultiTargetPersistentCodec.encode(
            plan,
            nextIndex = 1,
            state = MemoryRetentionMultiTargetState.ACTIVE
        )
        val legacy = v2.copy(schemaVersion = PersistentSchemaVersion(1))

        val decoded = assertIs<MemoryRetentionMultiTargetPersistentDecodeResult.Decoded>(
            MemoryRetentionMultiTargetPersistentCodec.decode(legacy)
        )
        assertEquals(plan, decoded.plan)
        assertEquals(1, decoded.nextIndex)
    }

    @Test
    fun version_one_never_accepts_new_single_target_semantics() {
        val plan = plan("invalid-legacy-single", listOf(target("one", 4)))
        val v2 = MemoryRetentionMultiTargetPersistentCodec.encode(
            plan,
            nextIndex = 0,
            state = MemoryRetentionMultiTargetState.ACTIVE
        )
        val forgedLegacy = v2.copy(schemaVersion = PersistentSchemaVersion(1))

        assertIs<MemoryRetentionMultiTargetPersistentDecodeResult.Corrupt>(
            MemoryRetentionMultiTargetPersistentCodec.decode(forgedLegacy)
        )
    }

    private fun plan(
        id: String,
        targets: List<MemoryRetentionTransactionTarget>
    ): MemoryRetentionMultiTargetPlan = MemoryRetentionMultiTargetPlan(
        id = MemoryRetentionMultiTargetId(id),
        targets = targets,
        createdAt = createdAt
    )

    private fun target(id: String, generation: Long): MemoryRetentionTransactionTarget =
        MemoryRetentionTransactionTarget(
            recordId = MemoryRecordId(id),
            generation = MemoryGeneration(generation),
            retentionClass = MemoryRetentionClass.EPISODIC,
            disposition = MemoryRetentionDisposition.RECORD_BUDGET_REJECTED
        )
}
