package pro.liliya.core.asf

import java.nio.charset.StandardCharsets

enum class AgentWorkerClass(val order: Int) {
    NANO(0),
    MICRO(1),
    FULL(2)
}

enum class AgentWorkerRuntimeKind {
    DETERMINISTIC,
    GRAPH_QUERY,
    EMBEDDING_RERANKER,
    ONNX,
    LLM
}

data class AgentWorkerRuntimeDescriptor(
    val runtimeId: String,
    val kind: AgentWorkerRuntimeKind,
    val modelId: String? = null
) {
    init {
        require(runtimeId.isNotBlank()) { "worker runtime id must not be blank" }
        require(runtimeId.toByteArray(StandardCharsets.UTF_8).size <= 256) {
            "worker runtime id exceeds bounded size"
        }
        require(modelId == null || modelId.isNotBlank()) { "worker model id must not be blank" }
        require(modelId == null || modelId.toByteArray(StandardCharsets.UTF_8).size <= 256) {
            "worker model id exceeds bounded size"
        }
    }
}

data class AgentWorkerProfile(
    val workerClass: AgentWorkerClass,
    val budgetCeiling: AgentWorkBudget,
    val maxLogicalRoleRetries: Int,
    val protectedToolViewAllowed: Boolean
) {
    init {
        require(maxLogicalRoleRetries >= 0) { "worker retry ceiling must not be negative" }
        if (workerClass == AgentWorkerClass.NANO) {
            require(budgetCeiling.maxDescendants == 0) { "NANO descendants must be hard-fixed to zero" }
            require(!protectedToolViewAllowed) { "NANO protected ToolView must be disabled" }
        }
    }

    fun isWithin(parent: AgentWorkerProfile): Boolean =
        workerClass.order <= parent.workerClass.order &&
            budgetCeiling.isWithin(parent.budgetCeiling) &&
            maxLogicalRoleRetries <= parent.maxLogicalRoleRetries &&
            (!protectedToolViewAllowed || parent.protectedToolViewAllowed)
}

class AgentWorkerProfileSet(profiles: Collection<AgentWorkerProfile>) {
    private val byClass: Map<AgentWorkerClass, AgentWorkerProfile>

    init {
        require(profiles.size == AgentWorkerClass.entries.size) {
            "worker profile set must contain exactly one profile for every worker class"
        }
        require(profiles.map { it.workerClass }.toSet() == AgentWorkerClass.entries.toSet()) {
            "worker profile set must contain FULL, MICRO and NANO exactly once"
        }
        byClass = profiles.associateBy { it.workerClass }
        require(profile(AgentWorkerClass.NANO).isWithin(profile(AgentWorkerClass.MICRO))) {
            "NANO profile must not exceed MICRO profile"
        }
        require(profile(AgentWorkerClass.MICRO).isWithin(profile(AgentWorkerClass.FULL))) {
            "MICRO profile must not exceed FULL profile"
        }
    }

    fun profile(workerClass: AgentWorkerClass): AgentWorkerProfile =
        byClass.getValue(workerClass)
}

enum class AgentWorkerAdmissionRejection {
    PROFILE_BUDGET_EXCEEDED,
    RETRY_LIMIT,
    NANO_DESCENDANTS_FORBIDDEN,
    PROTECTED_TOOL_VIEW_NOT_ALLOWED
}

sealed interface AgentWorkerAdmissionDecision {
    data class Admissible(
        val workerClass: AgentWorkerClass,
        val profile: AgentWorkerProfile,
        val runtime: AgentWorkerRuntimeDescriptor
    ) : AgentWorkerAdmissionDecision

    data class Rejected(val reason: AgentWorkerAdmissionRejection) : AgentWorkerAdmissionDecision
}

class AgentWorkerAdmissionPolicy(
    private val profiles: AgentWorkerProfileSet
) {
    fun evaluate(
        workerClass: AgentWorkerClass,
        request: AgentSpawnRequest,
        runtime: AgentWorkerRuntimeDescriptor,
        protectedToolViewRequested: Boolean = false
    ): AgentWorkerAdmissionDecision {
        val profile = profiles.profile(workerClass)
        if (workerClass == AgentWorkerClass.NANO && request.budget.maxDescendants != 0) {
            return reject(AgentWorkerAdmissionRejection.NANO_DESCENDANTS_FORBIDDEN)
        }
        if (!request.budget.isWithin(profile.budgetCeiling)) {
            return reject(AgentWorkerAdmissionRejection.PROFILE_BUDGET_EXCEEDED)
        }
        if (request.logicalRoleAttempt > profile.maxLogicalRoleRetries) {
            return reject(AgentWorkerAdmissionRejection.RETRY_LIMIT)
        }
        if (protectedToolViewRequested && !profile.protectedToolViewAllowed) {
            return reject(AgentWorkerAdmissionRejection.PROTECTED_TOOL_VIEW_NOT_ALLOWED)
        }
        return AgentWorkerAdmissionDecision.Admissible(workerClass, profile, runtime)
    }

    private fun reject(reason: AgentWorkerAdmissionRejection) =
        AgentWorkerAdmissionDecision.Rejected(reason)
}

enum class AgentWorkerRequirement {
    DETERMINISTIC_CHECK,
    ATOMIC_VALIDATION,
    NARROW_MULTI_STEP,
    BROAD_SPECIALIST
}

sealed interface AgentWorkerRoutingDecision {
    data object DeterministicCheck : AgentWorkerRoutingDecision
    data class Worker(val workerClass: AgentWorkerClass) : AgentWorkerRoutingDecision
    data object Declined : AgentWorkerRoutingDecision
}

object AgentWorkerRouter {
    fun route(
        requirement: AgentWorkerRequirement,
        admittedWorkerClasses: Set<AgentWorkerClass>
    ): AgentWorkerRoutingDecision {
        if (requirement == AgentWorkerRequirement.DETERMINISTIC_CHECK) {
            return AgentWorkerRoutingDecision.DeterministicCheck
        }
        val minimum = when (requirement) {
            AgentWorkerRequirement.DETERMINISTIC_CHECK -> error("handled above")
            AgentWorkerRequirement.ATOMIC_VALIDATION -> AgentWorkerClass.NANO
            AgentWorkerRequirement.NARROW_MULTI_STEP -> AgentWorkerClass.MICRO
            AgentWorkerRequirement.BROAD_SPECIALIST -> AgentWorkerClass.FULL
        }
        val selected = AgentWorkerClass.entries
            .asSequence()
            .filter { it.order >= minimum.order }
            .firstOrNull { it in admittedWorkerClasses }
            ?: return AgentWorkerRoutingDecision.Declined
        return AgentWorkerRoutingDecision.Worker(selected)
    }
}
