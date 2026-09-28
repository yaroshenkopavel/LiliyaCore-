package pro.liliya.core.asf

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

private const val MAX_ID_BYTES = 128
private const val MAX_TEXT_BYTES = 4096
private const val MAX_LIST_ITEMS = 64

private fun bounded(value: String, label: String, maxBytes: Int = MAX_TEXT_BYTES): String {
    require(value.isNotBlank()) { "$label must not be blank" }
    require(value.toByteArray(StandardCharsets.UTF_8).size <= maxBytes) { "$label exceeds bounded size" }
    return value
}

@JvmInline value class AgentBlueprintId(val value: String) { init { bounded(value, "agent blueprint id", MAX_ID_BYTES) } }
@JvmInline value class AgentBlueprintVersion(val value: Int) { init { require(value > 0) { "agent blueprint version must be positive" } } }
@JvmInline value class AgentSpawnRequestId(val value: String) { init { bounded(value, "agent spawn request id", MAX_ID_BYTES) } }
@JvmInline value class AgentAdmissionId(val value: String) { init { bounded(value, "agent admission id", MAX_ID_BYTES) } }
@JvmInline value class AgentInstanceId(val value: String) { init { bounded(value, "agent instance id", MAX_ID_BYTES) } }
@JvmInline value class AgentInstanceGeneration(val value: Long) { init { require(value > 0L) { "agent instance generation must be positive" } } }
@JvmInline value class AgentRootTaskId(val value: String) { init { bounded(value, "agent root task id", MAX_ID_BYTES) } }
@JvmInline value class AgentArtifactId(val value: String) { init { bounded(value, "agent artifact id", MAX_ID_BYTES) } }

data class AgentBlueprintReference(val id: AgentBlueprintId, val version: AgentBlueprintVersion)

data class AgentCognitiveScope(val domains: List<String>) {
    init {
        require(domains.isNotEmpty()) { "agent cognitive scope must not be empty" }
        require(domains.size <= MAX_LIST_ITEMS) { "too many agent cognitive scope domains" }
        require(domains.distinct().size == domains.size) { "agent cognitive scope domains must be unique" }
        require(domains == domains.sorted()) { "agent cognitive scope domains must use canonical order" }
        domains.forEach { bounded(it, "agent cognitive scope domain", 128) }
    }
    fun isWithin(parent: AgentCognitiveScope): Boolean = domains.all { it in parent.domains }
    companion object {
        fun create(domains: Collection<String>) = AgentCognitiveScope(domains.distinct().sorted())
    }
}

data class AgentWorkBudget(
    val maxWallClockMillis: Long,
    val maxInferenceUnits: Long,
    val maxContextBytes: Int,
    val maxRetrievalItems: Int,
    val maxArtifacts: Int,
    val maxDescendants: Int
) {
    init {
        require(maxWallClockMillis > 0L) { "agent wall clock budget must be positive" }
        require(maxInferenceUnits > 0L) { "agent inference budget must be positive" }
        require(maxContextBytes > 0) { "agent context budget must be positive" }
        require(maxRetrievalItems >= 0) { "agent retrieval budget must not be negative" }
        require(maxArtifacts > 0) { "agent artifact budget must be positive" }
        require(maxDescendants >= 0) { "agent descendant budget must not be negative" }
    }
    fun isWithin(parent: AgentWorkBudget): Boolean =
        maxWallClockMillis <= parent.maxWallClockMillis &&
            maxInferenceUnits <= parent.maxInferenceUnits &&
            maxContextBytes <= parent.maxContextBytes &&
            maxRetrievalItems <= parent.maxRetrievalItems &&
            maxArtifacts <= parent.maxArtifacts &&
            maxDescendants <= parent.maxDescendants
}

data class AgentBlueprint(
    val id: AgentBlueprintId,
    val version: AgentBlueprintVersion,
    val role: String,
    val objectiveClass: String,
    val cognitiveScope: AgentCognitiveScope
) {
    init {
        bounded(role, "agent blueprint role", 256)
        bounded(objectiveClass, "agent blueprint objective class", 256)
        require(id == deterministicId(version, role, objectiveClass, cognitiveScope)) {
            "agent blueprint id does not match deterministic content identity"
        }
    }
    companion object {
        fun create(version: AgentBlueprintVersion, role: String, objectiveClass: String, cognitiveScope: AgentCognitiveScope) =
            AgentBlueprint(deterministicId(version, role, objectiveClass, cognitiveScope), version, role, objectiveClass, cognitiveScope)
        private fun deterministicId(version: AgentBlueprintVersion, role: String, objectiveClass: String, scope: AgentCognitiveScope) =
            AgentBlueprintId("asf-blueprint-" + AsfIdentity.sha256("blueprint-v1", version.value.toString(), role, objectiveClass, *scope.domains.toTypedArray()))
    }
}

data class AgentSpawnProvenance(
    val rootTaskId: AgentRootTaskId,
    val parentAgentId: AgentInstanceId?,
    val parentGeneration: AgentInstanceGeneration?,
    val depth: Int
) {
    init {
        require(depth >= 0) { "agent spawn depth must not be negative" }
        require((parentAgentId == null) == (parentGeneration == null)) { "parent agent id and generation must be present together" }
        require((depth == 0) == (parentAgentId == null)) { "root spawn must have no parent and child spawn must have a parent" }
    }
}

data class AgentSpawnRequest(
    val id: AgentSpawnRequestId,
    val blueprint: AgentBlueprintReference,
    val provenance: AgentSpawnProvenance,
    val cognitiveScope: AgentCognitiveScope,
    val budget: AgentWorkBudget,
    val logicalRoleAttempt: Int
) {
    init {
        require(logicalRoleAttempt >= 0) { "logical role attempt must not be negative" }
        require(id == deterministicId(blueprint, provenance, cognitiveScope, budget, logicalRoleAttempt)) {
            "agent spawn request id does not match deterministic content identity"
        }
    }
    companion object {
        fun create(
            blueprint: AgentBlueprintReference,
            provenance: AgentSpawnProvenance,
            cognitiveScope: AgentCognitiveScope,
            budget: AgentWorkBudget,
            logicalRoleAttempt: Int = 0
        ) = AgentSpawnRequest(
            deterministicId(blueprint, provenance, cognitiveScope, budget, logicalRoleAttempt),
            blueprint, provenance, cognitiveScope, budget, logicalRoleAttempt
        )
        private fun deterministicId(
            blueprint: AgentBlueprintReference,
            provenance: AgentSpawnProvenance,
            scope: AgentCognitiveScope,
            budget: AgentWorkBudget,
            attempt: Int
        ) = AgentSpawnRequestId(
            "asf-spawn-" + AsfIdentity.sha256(
                "spawn-v1", blueprint.id.value, blueprint.version.value.toString(),
                provenance.rootTaskId.value, provenance.parentAgentId?.value ?: "",
                provenance.parentGeneration?.value?.toString() ?: "", provenance.depth.toString(),
                attempt.toString(), budget.maxWallClockMillis.toString(), budget.maxInferenceUnits.toString(),
                budget.maxContextBytes.toString(), budget.maxRetrievalItems.toString(),
                budget.maxArtifacts.toString(), budget.maxDescendants.toString(),
                *scope.domains.toTypedArray()
            )
        )
    }
}

data class AgentAdmission(
    val id: AgentAdmissionId,
    val requestId: AgentSpawnRequestId,
    val generation: AgentInstanceGeneration,
    val admittedScope: AgentCognitiveScope,
    val admittedBudget: AgentWorkBudget,
    val admittedAt: Instant
) {
    init {
        require(id == deterministicId(requestId, generation, admittedScope, admittedBudget, admittedAt)) {
            "agent admission id does not match deterministic content identity"
        }
    }
    fun instanceId() = AgentInstanceId("asf-agent-" + AsfIdentity.sha256("instance-v1", id.value, generation.value.toString()))
    companion object {
        fun create(
            requestId: AgentSpawnRequestId,
            generation: AgentInstanceGeneration,
            admittedScope: AgentCognitiveScope,
            admittedBudget: AgentWorkBudget,
            admittedAt: Instant
        ) = AgentAdmission(
            deterministicId(requestId, generation, admittedScope, admittedBudget, admittedAt),
            requestId, generation, admittedScope, admittedBudget, admittedAt
        )
        private fun deterministicId(
            requestId: AgentSpawnRequestId,
            generation: AgentInstanceGeneration,
            scope: AgentCognitiveScope,
            budget: AgentWorkBudget,
            admittedAt: Instant
        ) = AgentAdmissionId(
            "asf-admission-" + AsfIdentity.sha256(
                "admission-v1", requestId.value, generation.value.toString(),
                admittedAt.epochSecond.toString(), admittedAt.nano.toString(),
                budget.maxWallClockMillis.toString(), budget.maxInferenceUnits.toString(),
                budget.maxContextBytes.toString(), budget.maxRetrievalItems.toString(),
                budget.maxArtifacts.toString(), budget.maxDescendants.toString(),
                *scope.domains.toTypedArray()
            )
        )
    }
}

enum class AgentRecoveryDisposition {
    TERMINAL_RETAINED,
    NEW_ADMISSION_REQUIRED
}

enum class AgentLifecycleState {
    PROPOSED, ADMITTED, SPAWNED, RUNNING, COMPLETED, PARTIAL, FAILED, CANCELLED, EXPIRED, BUDGET_EXHAUSTED;
    val terminal: Boolean get() = this in setOf(COMPLETED, PARTIAL, FAILED, CANCELLED, EXPIRED, BUDGET_EXHAUSTED)

    fun canTransitionTo(next: AgentLifecycleState): Boolean = when (this) {
        PROPOSED -> next == ADMITTED
        ADMITTED -> next == SPAWNED
        SPAWNED -> next == RUNNING || next == CANCELLED || next == EXPIRED
        RUNNING -> next in setOf(COMPLETED, PARTIAL, FAILED, CANCELLED, EXPIRED, BUDGET_EXHAUSTED)
        COMPLETED, PARTIAL, FAILED, CANCELLED, EXPIRED, BUDGET_EXHAUSTED -> false
    }

    fun recoveryDisposition(): AgentRecoveryDisposition =
        if (terminal) {
            AgentRecoveryDisposition.TERMINAL_RETAINED
        } else {
            AgentRecoveryDisposition.NEW_ADMISSION_REQUIRED
        }
}

class AgentInstance private constructor(
    val id: AgentInstanceId,
    val admissionId: AgentAdmissionId,
    val generation: AgentInstanceGeneration,
    val provenance: AgentSpawnProvenance,
    val lifecycle: AgentLifecycleState
) {
    fun transition(next: AgentLifecycleState): AgentInstance {
        require(lifecycle.canTransitionTo(next)) { "invalid agent lifecycle transition: $lifecycle -> $next" }
        return AgentInstance(id, admissionId, generation, provenance, next)
    }

    override fun equals(other: Any?): Boolean =
        other is AgentInstance &&
            id == other.id &&
            admissionId == other.admissionId &&
            generation == other.generation &&
            provenance == other.provenance &&
            lifecycle == other.lifecycle

    override fun hashCode(): Int =
        listOf(id, admissionId, generation, provenance, lifecycle).hashCode()

    companion object {
        fun fromAdmission(admission: AgentAdmission, provenance: AgentSpawnProvenance): AgentInstance =
            AgentInstance(
                id = admission.instanceId(),
                admissionId = admission.id,
                generation = admission.generation,
                provenance = provenance,
                lifecycle = AgentLifecycleState.ADMITTED
            )
    }
}

data class AgentArtifact(
    val id: AgentArtifactId,
    val producerId: AgentInstanceId,
    val producerGeneration: AgentInstanceGeneration,
    val rootTaskId: AgentRootTaskId,
    val kind: String,
    val payloadDigest: String,
    val provenanceReferences: List<String>,
    val createdAt: Instant
) {
    init {
        bounded(kind, "agent artifact kind", 128)
        bounded(payloadDigest, "agent artifact payload digest", 256)
        require(provenanceReferences.isNotEmpty()) { "agent artifact must retain provenance" }
        require(provenanceReferences.size <= MAX_LIST_ITEMS) { "too many agent artifact provenance references" }
        require(provenanceReferences.distinct().size == provenanceReferences.size) { "agent artifact provenance must be unique" }
        require(provenanceReferences == provenanceReferences.sorted()) { "agent artifact provenance must use canonical order" }
        provenanceReferences.forEach { bounded(it, "agent artifact provenance reference", 256) }
        require(id == deterministicId(producerId, producerGeneration, rootTaskId, kind, payloadDigest, provenanceReferences, createdAt)) {
            "agent artifact id does not match deterministic content identity"
        }
    }
    companion object {
        fun create(
            producerId: AgentInstanceId,
            producerGeneration: AgentInstanceGeneration,
            rootTaskId: AgentRootTaskId,
            kind: String,
            payloadDigest: String,
            provenanceReferences: Collection<String>,
            createdAt: Instant
        ): AgentArtifact {
            val canonical = provenanceReferences.distinct().sorted()
            return AgentArtifact(
                deterministicId(producerId, producerGeneration, rootTaskId, kind, payloadDigest, canonical, createdAt),
                producerId, producerGeneration, rootTaskId, kind, payloadDigest, canonical, createdAt
            )
        }
        private fun deterministicId(
            producerId: AgentInstanceId,
            producerGeneration: AgentInstanceGeneration,
            rootTaskId: AgentRootTaskId,
            kind: String,
            payloadDigest: String,
            provenance: List<String>,
            createdAt: Instant
        ) = AgentArtifactId(
            "asf-artifact-" + AsfIdentity.sha256(
                "artifact-v1", producerId.value, producerGeneration.value.toString(), rootTaskId.value,
                kind, payloadDigest, createdAt.epochSecond.toString(), createdAt.nano.toString(),
                *provenance.toTypedArray()
            )
        )
    }
}

internal object AsfIdentity {
    fun sha256(vararg values: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEach { value ->
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
