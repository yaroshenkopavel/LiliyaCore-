package pro.liliya.core.asf

import java.nio.charset.StandardCharsets
import java.time.Instant

enum class AgentWorkspaceState { ACTIVE, DISPOSED }

class AgentWorkspace private constructor(
    val instanceId: AgentInstanceId,
    val inputReferences: List<String>,
    val contextBytes: Int,
    val state: AgentWorkspaceState
) {
    init {
        require(inputReferences.isNotEmpty()) { "agent workspace requires input provenance" }
        require(inputReferences.distinct().size == inputReferences.size) { "workspace input references must be unique" }
        require(inputReferences == inputReferences.sorted()) { "workspace input references must use canonical order" }
        require(contextBytes > 0) { "workspace context bytes must be positive" }
        inputReferences.forEach {
            require(it.isNotBlank()) { "workspace input reference must not be blank" }
            require(it.toByteArray(StandardCharsets.UTF_8).size <= 1024) {
                "workspace input reference exceeds bounded size"
            }
        }
    }

    fun dispose(): AgentWorkspace =
        if (state == AgentWorkspaceState.DISPOSED) this
        else AgentWorkspace(instanceId, inputReferences, contextBytes, AgentWorkspaceState.DISPOSED)

    companion object {
        fun create(
            instanceId: AgentInstanceId,
            inputReferences: Collection<String>,
            maxContextBytes: Int
        ): AgentWorkspace {
            require(maxContextBytes > 0) { "workspace max context bytes must be positive" }
            val canonical = inputReferences.sorted()
            val bytes = canonical.sumOf { it.toByteArray(StandardCharsets.UTF_8).size }
            require(bytes in 1..maxContextBytes) { "workspace input exceeds context budget" }
            return AgentWorkspace(instanceId, canonical, bytes, AgentWorkspaceState.ACTIVE)
        }
    }
}

data class AgentRuntimeUsage(
    val wallClockMillis: Long,
    val inferenceUnits: Long,
    val contextBytes: Int,
    val retrievalItems: Int,
    val artifactCount: Int
) {
    init {
        require(wallClockMillis >= 0)
        require(inferenceUnits >= 0)
        require(contextBytes >= 0)
        require(retrievalItems >= 0)
        require(artifactCount >= 0)
    }

    fun exceeds(budget: AgentWorkBudget): Boolean =
        wallClockMillis > budget.maxWallClockMillis ||
            inferenceUnits > budget.maxInferenceUnits ||
            contextBytes > budget.maxContextBytes ||
            retrievalItems > budget.maxRetrievalItems ||
            artifactCount > budget.maxArtifacts
}

data class AgentRuntimeContext(
    val instanceId: AgentInstanceId,
    val generation: AgentInstanceGeneration,
    val blueprint: AgentBlueprintReference,
    val scope: AgentCognitiveScope,
    val budget: AgentWorkBudget,
    val workspace: AgentWorkspace,
    val startedAt: Instant,
    val expiresAt: Instant,
    val workerRuntime: AgentWorkerRuntimeDescriptor? = null
)

sealed interface AgentRuntimeOutcome {
    val usage: AgentRuntimeUsage

    data class Completed(
        val kind: String,
        val payloadDigest: String,
        val provenanceReferences: List<String>,
        override val usage: AgentRuntimeUsage
    ) : AgentRuntimeOutcome

    data class Failed(
        val reason: String,
        override val usage: AgentRuntimeUsage
    ) : AgentRuntimeOutcome {
        init {
            require(reason.isNotBlank()) { "agent runtime failure reason must not be blank" }
            require(reason.toByteArray(StandardCharsets.UTF_8).size <= 4096) {
                "agent runtime failure reason exceeds bounded size"
            }
        }
    }
}

fun interface AgentRuntimeAdapter {
    fun run(context: AgentRuntimeContext): AgentRuntimeOutcome
}

enum class AgentAuditEventKind {
    ADMITTED, SPAWNED, RUNNING, COMPLETED, FAILED, CANCELLED, EXPIRED, BUDGET_EXHAUSTED, WORKSPACE_DISPOSED
}

data class AgentAuditEvent(
    val kind: AgentAuditEventKind,
    val requestId: AgentSpawnRequestId,
    val admissionId: AgentAdmissionId,
    val instanceId: AgentInstanceId,
    val generation: AgentInstanceGeneration,
    val rootTaskId: AgentRootTaskId,
    val artifactId: AgentArtifactId? = null,
    val usage: AgentRuntimeUsage? = null,
    val occurredAt: Instant
)

fun interface AgentAuditLedger {
    fun append(event: AgentAuditEvent)
}
