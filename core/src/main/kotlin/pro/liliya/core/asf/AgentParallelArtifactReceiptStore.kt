package pro.liliya.core.asf

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
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

    /**
     * Only publish a receipt when the owner can independently read and verify
     * the durable payload both before install and after receipt readback.
     *
     * The verifier must reject missing bytes, wrong digest, and non-durable
     * storage. This method does not make two independent stores atomic.
     */
    fun commitAndVerifyWithPayload(
        stepId: AgentCoordinatorStepId,
        artifact: AgentArtifact,
        verifyDurablePayload: (AgentArtifact) -> Boolean
    ): Boolean {
        val before = try { verifyDurablePayload(artifact) } catch (_: Exception) { false }
        if (!before) return false
        if (!commitAndVerify(stepId, artifact)) return false
        return try { verifyDurablePayload(artifact) } catch (_: Exception) { false }
    }

    /**
     * Atomically publish payload bytes and their artifact receipt in ONE
     * PersistentRecord. The owner must supply a crash-durable backend.
     * Does not grant execution authority or commit external side effects.
     */
    fun commitPayloadAndReceipt(
        stepId: AgentCoordinatorStepId,
        artifact: AgentArtifact,
        payloadBytes: ByteArray
    ): Boolean {
        if (payloadBytes.size > 512 * 1024) return false
        val actualDigest = MessageDigest.getInstance("SHA-256")
            .digest(payloadBytes)
            .joinToString("") { "%02x".format(it) }
        if (artifact.payloadDigest != "sha256:$actualDigest") return false
        val receipt = try { record(stepId, artifact) } catch (_: Exception) { return false }
        val metadata = receipt.payload.copyBytes()
        val combined = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(2)
                output.writeInt(metadata.size)
                output.write(metadata)
                output.writeInt(payloadBytes.size)
                output.write(payloadBytes)
            }
            bytes.toByteArray()
        }
        val atomicRecord = receipt.copy(
            schemaVersion = PersistentSchemaVersion(2),
            payload = PersistentPayload(combined)
        )
        val result = try { store.install(atomicRecord) } catch (_: Exception) { return false }
        if (result !is PersistentInstallResult.Installed &&
            result !is PersistentInstallResult.Rejected) return false
        return try {
            when (val read = store.inspectResult(atomicRecord.id)) {
                is PersistentRecordLookupResult.Found -> read.snapshot.record == atomicRecord
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
