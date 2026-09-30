package pro.liliya.core.asf

import java.nio.charset.StandardCharsets

@JvmInline
value class AgentTeamCompositionPolicyVersion(val value: String) {
    init {
        require(value.isNotBlank()) { "team composition policy version must not be blank" }
        require(value.toByteArray(StandardCharsets.UTF_8).size <= 128) {
            "team composition policy version exceeds bounded size"
        }
    }
}

@JvmInline
value class AgentTeamCompositionDecisionId(val value: String) {
    init {
        require(value.startsWith("asf-team-composition-")) {
            "team composition decision id must use canonical prefix"
        }
    }
}

data class AgentTeamTaskShape(
    val deterministicResolutionAvailable: Boolean,
    val workerRequirements: List<AgentWorkerRequirement>
) {
    init {
        require(workerRequirements.none { it == AgentWorkerRequirement.DETERMINISTIC_CHECK }) {
            "deterministic check must be represented by deterministicResolutionAvailable"
        }
        require(workerRequirements.distinct().size == workerRequirements.size) {
            "team worker requirements must be unique"
        }
        require(workerRequirements == workerRequirements.sortedBy { it.ordinal }) {
            "team worker requirements must use canonical order"
        }
        require(!deterministicResolutionAvailable || workerRequirements.isEmpty()) {
            "deterministic resolution cannot request a worker team"
        }
        require(deterministicResolutionAvailable || workerRequirements.isNotEmpty()) {
            "team task shape must have a deterministic path or worker requirement"
        }
    }

    companion object {
        fun deterministic() = AgentTeamTaskShape(
            deterministicResolutionAvailable = true,
            workerRequirements = emptyList()
        )

        fun workers(requirements: Collection<AgentWorkerRequirement>) =
            AgentTeamTaskShape(
                deterministicResolutionAvailable = false,
                workerRequirements = requirements
                    .filterNot { it == AgentWorkerRequirement.DETERMINISTIC_CHECK }
                    .distinct()
                    .sortedBy { it.ordinal }
            )
    }
}

data class AgentTeamWorkerCandidate(
    val workerClass: AgentWorkerClass,
    val blueprint: AgentBlueprintReference,
    val cognitiveScope: AgentCognitiveScope,
    val budget: AgentWorkBudget,
    val runtime: AgentWorkerRuntimeDescriptor
) {
    init {
        if (workerClass == AgentWorkerClass.NANO) {
            require(budget.maxDescendants == 0) {
                "NANO team candidate descendants must be zero"
            }
        }
    }
}

data class AgentTeamCompositionRequest(
    val rootTaskId: AgentRootTaskId,
    val policyVersion: AgentTeamCompositionPolicyVersion,
    val taskShape: AgentTeamTaskShape,
    val aggregateBudget: AgentAggregateBudget,
    val inputReferences: List<String>,
    val candidates: List<AgentTeamWorkerCandidate>
) {
    init {
        require(inputReferences.isNotEmpty()) {
            "team composition request requires input provenance"
        }
        require(inputReferences.distinct().size == inputReferences.size) {
            "team composition input references must be unique"
        }
        require(inputReferences == inputReferences.sorted()) {
            "team composition input references must use canonical order"
        }
        require(candidates.map { it.workerClass }.distinct().size == candidates.size) {
            "team composition may contain at most one candidate per worker class"
        }
    }

    companion object {
        fun create(
            rootTaskId: AgentRootTaskId,
            policyVersion: AgentTeamCompositionPolicyVersion,
            taskShape: AgentTeamTaskShape,
            aggregateBudget: AgentAggregateBudget,
            inputReferences: Collection<String>,
            candidates: Collection<AgentTeamWorkerCandidate>
        ) = AgentTeamCompositionRequest(
            rootTaskId = rootTaskId,
            policyVersion = policyVersion,
            taskShape = taskShape,
            aggregateBudget = aggregateBudget,
            inputReferences = inputReferences.sorted(),
            candidates = candidates.sortedBy { it.workerClass.order }
        )
    }
}

enum class AgentTeamCompositionRejection {
    NO_SUITABLE_WORKER,
    AGGREGATE_BUDGET_EXCEEDED,
    ROOT_DESCENDANT_BUDGET_EXCEEDED,
    ROOT_SCOPE_TOO_NARROW,
    DIRECT_CHILD_LIMIT
}

sealed interface AgentTeamCompositionDecision {
    data class DeterministicFallback(
        val decisionId: AgentTeamCompositionDecisionId,
        val rootTaskId: AgentRootTaskId,
        val policyVersion: AgentTeamCompositionPolicyVersion,
        val inputReferences: List<String>
    ) : AgentTeamCompositionDecision

    data class Composed(
        val plan: AgentTeamCompositionPlan
    ) : AgentTeamCompositionDecision

    data class Rejected(
        val reason: AgentTeamCompositionRejection
    ) : AgentTeamCompositionDecision
}

data class AgentTeamCompositionPlan(
    val decisionId: AgentTeamCompositionDecisionId,
    val policyVersion: AgentTeamCompositionPolicyVersion,
    val inputReferences: List<String>,
    val selectedRequirements: List<AgentWorkerRequirement>,
    val selectedWorkerClasses: List<AgentWorkerClass>,
    val coordinatorPlan: AgentCoordinatorPlan
) {
    init {
        require(inputReferences.isNotEmpty()) {
            "team composition plan requires input provenance"
        }
        require(selectedRequirements.isNotEmpty()) {
            "team composition plan requires worker requirements"
        }
        require(selectedWorkerClasses.isNotEmpty()) {
            "team composition plan requires at least one worker"
        }
        require(selectedWorkerClasses.distinct().size == selectedWorkerClasses.size) {
            "team composition plan worker classes must be unique"
        }
        require(selectedWorkerClasses == selectedWorkerClasses.sortedByDescending { it.order }) {
            "team composition plan must list root-first worker classes"
        }
        require(coordinatorPlan.steps.size == selectedWorkerClasses.size) {
            "team composition plan worker count must match coordinator steps"
        }
    }
}
