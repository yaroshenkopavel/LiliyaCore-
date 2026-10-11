package pro.liliya.core.asf

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentInstallResult
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentRecordLookupResult
import pro.liliya.core.persistence.PersistentRecordStore
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion

/**
 * Persistent metadata receipt for an ASF step. The supplied store MUST be backed by
 * a crash-durable backend; an in-memory backend cannot provide durability.
 * This receipt protects exact step/artifact identity but does NOT prove that
 * the separately referenced artifact payload bytes have been persisted.
 */
class AgentParallelArtifactReceiptStore(private val store: PersistentRecordStore) {
    fun commitAndVerify(stepId: AgentCoordinatorStepId, artifact: AgentArtifact): Boolean {
        val record = record(stepId, artifact)
        val committed = try { store.install(record) } catch (_: Exception) { return false }
        if (committed !is PersistentInstallResult.Installed &&
            committed !is PersistentInstallResult.Rejected) return false
        // Always independently read back; a rejection is acceptable only for an
        // identical previously committed receipt (idempotent crash recovery).
        return try {
            when (val read = store.inspectResult(record.id)) {
                is PersistentRecordLookupResult.Found -> read.snapshot.record == record
                else -> false
            }
        } catch (_: Exception) { false }
    }

    private fun record(stepId: AgentCoordinatorStepId, artifact: AgentArtifact): PersistentRecord {
        val id = PersistentEntityId(
            "asf-step-receipt-" + AsfIdentity.sha256(
                "asf-step-receipt-v1", artifact.rootTaskId.value, stepId.value
            )
        )
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(1)
                output.writeUTF(stepId.value)
                output.writeUTF(artifact.id.value)
                output.writeUTF(artifact.producerId.value)
                output.writeLong(artifact.producerGeneration.value)
                output.writeUTF(artifact.rootTaskId.value)
                output.writeUTF(artifact.kind)
                output.writeUTF(artifact.payloadDigest)
                output.writeLong(artifact.createdAt.epochSecond)
                output.writeInt(artifact.createdAt.nano)
                output.writeInt(artifact.provenanceReferences.size)
                artifact.provenanceReferences.forEach(output::writeUTF)
            }
            bytes.toByteArray()
        }
        return PersistentRecord(
            id = id,
            schemaId = PersistentSchemaId("asf-step-artifact-receipt"),
            schemaVersion = PersistentSchemaVersion(1),
            payload = PersistentPayload(payload),
            createdAt = artifact.createdAt
        )
    }
}
