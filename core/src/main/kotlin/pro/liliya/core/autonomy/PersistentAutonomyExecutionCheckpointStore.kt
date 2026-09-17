package pro.liliya.core.autonomy

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.decision.DecisionGeneration
import pro.liliya.core.decision.DecisionId
import pro.liliya.core.execution.ExecutionActionId
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.orchestration.OrchestrationGeneration
import pro.liliya.core.orchestration.OrchestrationIntentId
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentGeneration
import pro.liliya.core.persistence.PersistentInstallResult
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentRecordBackend
import pro.liliya.core.persistence.PersistentRecordStore
import pro.liliya.core.persistence.PersistentRecordTransitionResult
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion
import pro.liliya.core.persistence.PersistentStoreId
import pro.liliya.core.persistence.PersistentStoreOpenResult
import pro.liliya.core.planning.PlanningGeneration
import pro.liliya.core.planning.PlanningProposalId
import pro.liliya.core.reasoning.ReasoningArtifactId
import pro.liliya.core.reasoning.ReasoningGeneration

/**
 * Durable, non-authoritative checkpoint for an autonomy execution request.
 *
 * A checkpoint is provenance only. It deliberately persists no prompt/objective/description,
 * Capability grant, Authority grant/token, ExecutionGrant, or executor state. Restoring a PENDING
 * checkpoint therefore cannot resume execution by itself: the caller must submit the restored
 * [ControlledAutonomyExecutionRequest] through [ControlledAutonomyExecution], which revalidates the
 * live cognitive/orchestration chain and fresh Authority before any executor is reachable.
 */
enum class AutonomyExecutionCheckpointState {
    PENDING,
    COMPLETED,
    CANCELLED
}

data class AutonomyExecutionCheckpoint(
    val request: ControlledAutonomyExecutionRequest,
    val state: AutonomyExecutionCheckpointState,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class AutonomyExecutionCheckpointSnapshot(
    val checkpoint: AutonomyExecutionCheckpoint,
    val generation: PersistentGeneration
)

sealed interface AutonomyExecutionCheckpointWriteResult {
    data class Written(val snapshot: AutonomyExecutionCheckpointSnapshot) : AutonomyExecutionCheckpointWriteResult
    data class Rejected(val reason: String) : AutonomyExecutionCheckpointWriteResult
    data class Failed(val reason: String, val throwable: Throwable? = null) : AutonomyExecutionCheckpointWriteResult
}

sealed interface PersistentAutonomyExecutionCheckpointOpenResult {
    data class Opened(val store: PersistentAutonomyExecutionCheckpointStore) : PersistentAutonomyExecutionCheckpointOpenResult
    data object Corrupt : PersistentAutonomyExecutionCheckpointOpenResult
    data class Incompatible(val reason: String) : PersistentAutonomyExecutionCheckpointOpenResult
    data class Failed(val reason: String, val throwable: Throwable? = null) : PersistentAutonomyExecutionCheckpointOpenResult
}

class PersistentAutonomyExecutionCheckpointStore private constructor(
    private val store: PersistentRecordStore
) {
    fun prepare(
        request: ControlledAutonomyExecutionRequest,
        now: Instant
    ): AutonomyExecutionCheckpointWriteResult {
        val checkpoint = AutonomyExecutionCheckpoint(
            request = request,
            state = AutonomyExecutionCheckpointState.PENDING,
            createdAt = now,
            updatedAt = now
        )
        return when (val installed = store.install(Codec.encode(checkpoint))) {
            is PersistentInstallResult.Installed -> AutonomyExecutionCheckpointWriteResult.Written(
                AutonomyExecutionCheckpointSnapshot(checkpoint, installed.ownership.generation)
            )
            is PersistentInstallResult.Rejected -> AutonomyExecutionCheckpointWriteResult.Rejected(installed.reason)
            is PersistentInstallResult.Failed -> AutonomyExecutionCheckpointWriteResult.Failed(
                installed.reason,
                installed.throwable
            )
        }
    }

    fun inspect(intentId: OrchestrationIntentId): AutonomyExecutionCheckpointSnapshot? {
        val snapshot = store.inspect(entityId(intentId)) ?: return null
        val decoded = Codec.decode(snapshot.record) as? DecodeResult.Decoded ?: return null
        return AutonomyExecutionCheckpointSnapshot(decoded.checkpoint, snapshot.generation)
    }

    fun pending(): List<AutonomyExecutionCheckpointSnapshot> = store.snapshotEntries().mapNotNull { snapshot ->
        val decoded = Codec.decode(snapshot.record) as? DecodeResult.Decoded ?: return@mapNotNull null
        decoded.checkpoint
            .takeIf { it.state == AutonomyExecutionCheckpointState.PENDING }
            ?.let { AutonomyExecutionCheckpointSnapshot(it, snapshot.generation) }
    }

    fun markCompleted(
        intentId: OrchestrationIntentId,
        generation: PersistentGeneration,
        now: Instant
    ): AutonomyExecutionCheckpointWriteResult = transition(
        intentId,
        generation,
        AutonomyExecutionCheckpointState.COMPLETED,
        now
    )

    fun cancel(
        intentId: OrchestrationIntentId,
        generation: PersistentGeneration,
        now: Instant
    ): AutonomyExecutionCheckpointWriteResult = transition(
        intentId,
        generation,
        AutonomyExecutionCheckpointState.CANCELLED,
        now
    )

    private fun transition(
        intentId: OrchestrationIntentId,
        generation: PersistentGeneration,
        target: AutonomyExecutionCheckpointState,
        now: Instant
    ): AutonomyExecutionCheckpointWriteResult {
        val currentRecord = store.inspect(entityId(intentId))
            ?: return AutonomyExecutionCheckpointWriteResult.Rejected("autonomy execution checkpoint is not live")
        if (currentRecord.generation != generation) {
            return AutonomyExecutionCheckpointWriteResult.Rejected("autonomy execution checkpoint generation is stale")
        }
        val decoded = when (val result = Codec.decode(currentRecord.record)) {
            is DecodeResult.Decoded -> result.checkpoint
            DecodeResult.Corrupt -> return AutonomyExecutionCheckpointWriteResult.Failed("autonomy execution checkpoint is corrupt")
            is DecodeResult.Incompatible -> return AutonomyExecutionCheckpointWriteResult.Failed(result.reason)
        }
        if (decoded.state != AutonomyExecutionCheckpointState.PENDING) {
            return AutonomyExecutionCheckpointWriteResult.Rejected("autonomy execution checkpoint is not pending")
        }
        val replacement = decoded.copy(state = target, updatedAt = now)
        return when (
            val transitioned = store.transitionExact(
                currentRecord.record.id,
                generation,
                Codec.encode(replacement)
            )
        ) {
            is PersistentRecordTransitionResult.Committed -> AutonomyExecutionCheckpointWriteResult.Written(
                AutonomyExecutionCheckpointSnapshot(replacement, transitioned.ownership.generation)
            )
            is PersistentRecordTransitionResult.Rejected -> AutonomyExecutionCheckpointWriteResult.Rejected(transitioned.reason)
            is PersistentRecordTransitionResult.Failed -> AutonomyExecutionCheckpointWriteResult.Failed(
                transitioned.reason,
                transitioned.throwable
            )
        }
    }

    private sealed interface DecodeResult {
        data class Decoded(val checkpoint: AutonomyExecutionCheckpoint) : DecodeResult
        data object Corrupt : DecodeResult
        data class Incompatible(val reason: String) : DecodeResult
    }

    private object Codec {
        private val schemaId = PersistentSchemaId("autonomy-execution-checkpoint")
        private val schemaVersion = PersistentSchemaVersion(1)

        fun encode(checkpoint: AutonomyExecutionCheckpoint): PersistentRecord {
            val bytes = ByteArrayOutputStream().use { output ->
                DataOutputStream(output).use { data ->
                    with(checkpoint.request) {
                        data.writeUTF(deliberationRequestId.value)
                        data.writeLong(deliberationGeneration.value)
                        data.writeUTF(planningProposalId.value)
                        data.writeLong(planningGeneration.value)
                        data.writeUTF(reasoningArtifactId.value)
                        data.writeLong(reasoningGeneration.value)
                        data.writeUTF(decisionId.value)
                        data.writeLong(decisionGeneration.value)
                        data.writeUTF(orchestrationIntentId.value)
                        data.writeLong(orchestrationGeneration.value)
                        data.writeUTF(principal.value)
                        data.writeUTF(actionId.value)
                    }
                    data.writeUTF(checkpoint.state.name)
                    data.writeUTF(checkpoint.createdAt.toString())
                    data.writeUTF(checkpoint.updatedAt.toString())
                }
                output.toByteArray()
            }
            return PersistentRecord(
                id = entityId(checkpoint.request.orchestrationIntentId),
                schemaId = schemaId,
                schemaVersion = schemaVersion,
                payload = PersistentPayload(bytes),
                createdAt = checkpoint.createdAt
            )
        }

        fun decode(record: PersistentRecord): DecodeResult {
            if (record.schemaId != schemaId) {
                return DecodeResult.Incompatible("autonomy execution checkpoint schema id is incompatible")
            }
            if (record.schemaVersion != schemaVersion) {
                return DecodeResult.Incompatible("autonomy execution checkpoint schema version is incompatible")
            }
            return try {
                val checkpoint = DataInputStream(ByteArrayInputStream(record.payload.copyBytes())).use { data ->
                    val request = ControlledAutonomyExecutionRequest(
                        deliberationRequestId = AutonomyDeliberationRequestId(data.readUTF()),
                        deliberationGeneration = AutonomyDeliberationGeneration(data.readLong()),
                        planningProposalId = PlanningProposalId(data.readUTF()),
                        planningGeneration = PlanningGeneration(data.readLong()),
                        reasoningArtifactId = ReasoningArtifactId(data.readUTF()),
                        reasoningGeneration = ReasoningGeneration(data.readLong()),
                        decisionId = DecisionId(data.readUTF()),
                        decisionGeneration = DecisionGeneration(data.readLong()),
                        orchestrationIntentId = OrchestrationIntentId(data.readUTF()),
                        orchestrationGeneration = OrchestrationGeneration(data.readLong()),
                        principal = AuthorityPrincipal(data.readUTF()),
                        actionId = ExecutionActionId(data.readUTF())
                    )
                    val state = AutonomyExecutionCheckpointState.valueOf(data.readUTF())
                    val createdAt = Instant.parse(data.readUTF())
                    val updatedAt = Instant.parse(data.readUTF())
                    if (data.available() != 0) return DecodeResult.Corrupt
                    AutonomyExecutionCheckpoint(request, state, createdAt, updatedAt)
                }
                if (entityId(checkpoint.request.orchestrationIntentId) != record.id) {
                    DecodeResult.Corrupt
                } else {
                    DecodeResult.Decoded(checkpoint)
                }
            } catch (_: Throwable) {
                DecodeResult.Corrupt
            }
        }
    }

    companion object {
        private val DEFAULT_STORE_ID = PersistentStoreId("autonomy-execution-checkpoints")

        fun open(
            foundation: FoundationComposition,
            backend: PersistentRecordBackend,
            storeId: PersistentStoreId = DEFAULT_STORE_ID
        ): PersistentAutonomyExecutionCheckpointOpenResult = when (
            val opened = PersistentRecordStore.open(foundation, storeId, backend)
        ) {
            is PersistentStoreOpenResult.Opened -> {
                for (snapshot in opened.store.snapshotEntries()) {
                    when (val decoded = Codec.decode(snapshot.record)) {
                        is DecodeResult.Decoded -> Unit
                        DecodeResult.Corrupt -> return PersistentAutonomyExecutionCheckpointOpenResult.Corrupt
                        is DecodeResult.Incompatible ->
                            return PersistentAutonomyExecutionCheckpointOpenResult.Incompatible(decoded.reason)
                    }
                }
                PersistentAutonomyExecutionCheckpointOpenResult.Opened(
                    PersistentAutonomyExecutionCheckpointStore(opened.store)
                )
            }
            PersistentStoreOpenResult.Corrupt -> PersistentAutonomyExecutionCheckpointOpenResult.Corrupt
            is PersistentStoreOpenResult.Incompatible ->
                PersistentAutonomyExecutionCheckpointOpenResult.Incompatible(opened.reason)
            is PersistentStoreOpenResult.Failed ->
                PersistentAutonomyExecutionCheckpointOpenResult.Failed(opened.reason, opened.throwable)
        }

        private fun entityId(intentId: OrchestrationIntentId): PersistentEntityId =
            PersistentEntityId("autonomy-execution:${intentId.value}")
    }
}
