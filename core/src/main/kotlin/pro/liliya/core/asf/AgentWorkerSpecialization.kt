package pro.liliya.core.asf

import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

data class AgentWorkerSpecializationProfile(
    val workerClass: AgentWorkerClass,
    val blueprint: AgentBlueprintReference,
    val runtime: AgentWorkerRuntimeDescriptor
) {
    val canonicalKey: String
        get() = listOf(
            workerClass.name,
            blueprint.id.value,
            blueprint.version.value.toString(),
            runtime.runtimeId,
            runtime.kind.name,
            runtime.modelId ?: ""
        ).joinToString("|")
}

class AgentWorkerSpecializationEvidence private constructor(
    val taskClass: AgentWorkerRequirement,
    val profile: AgentWorkerSpecializationProfile,
    val evaluationReference: String,
    val baselineEvaluationReference: String,
    val comparisonToBaseline: AgentBlueprintEvaluationComparison,
    val observedAt: Instant,
    val provenanceReferences: List<String>
) {
    init {
        require(taskClass != AgentWorkerRequirement.DETERMINISTIC_CHECK) {
            "deterministic checks cannot create worker specialization evidence"
        }
        require(evaluationReference.isNotBlank()) {
            "specialization evaluation reference must not be blank"
        }
        require(evaluationReference.toByteArray(StandardCharsets.UTF_8).size <= 256) {
            "specialization evaluation reference exceeds bounded size"
        }
        require(baselineEvaluationReference.isNotBlank()) {
            "specialization baseline evaluation reference must not be blank"
        }
        require(baselineEvaluationReference.toByteArray(StandardCharsets.UTF_8).size <= 256) {
            "specialization baseline evaluation reference exceeds bounded size"
        }
        require(evaluationReference != baselineEvaluationReference) {
            "candidate and baseline evaluation references must differ"
        }
        require(provenanceReferences.isNotEmpty()) {
            "specialization evidence requires provenance"
        }
        require(provenanceReferences.size <= 32) {
            "specialization evidence provenance exceeds bounded count"
        }
        provenanceReferences.forEach {
            require(it.isNotBlank()) { "specialization provenance reference must not be blank" }
            require(it.toByteArray(StandardCharsets.UTF_8).size <= 256) {
                "specialization provenance reference exceeds bounded size"
            }
        }
        require(provenanceReferences == provenanceReferences.distinct().sorted()) {
            "specialization provenance references must be unique and canonical"
        }
        require(evaluationReference in provenanceReferences) {
            "specialization provenance must include candidate evaluation reference"
        }
        require(baselineEvaluationReference in provenanceReferences) {
            "specialization provenance must include baseline evaluation reference"
        }
    }

    companion object {
        fun fromCoreEvaluation(
            taskClass: AgentWorkerRequirement,
            profile: AgentWorkerSpecializationProfile,
            baselineEvaluationReference: String,
            baselineEvaluation: AgentBlueprintEvaluationVector,
            candidateEvaluationReference: String,
            candidateEvaluation: AgentBlueprintEvaluationVector,
            observedAt: Instant,
            provenanceReferences: Collection<String>
        ) = AgentWorkerSpecializationEvidence(
            taskClass = taskClass,
            profile = profile,
            evaluationReference = candidateEvaluationReference,
            baselineEvaluationReference = baselineEvaluationReference,
            comparisonToBaseline = AgentBlueprintEvaluationComparator.compare(
                baseline = baselineEvaluation,
                candidate = candidateEvaluation
            ),
            observedAt = observedAt,
            provenanceReferences = provenanceReferences.distinct().sorted()
        )
    }
}

class AgentWorkerSpecializationEvidenceSet private constructor(
    val records: List<AgentWorkerSpecializationEvidence>
) {
    init {
        require(records.size <= MAX_RECORDS) {
            "specialization evidence set exceeds bounded size"
        }
        require(records.map { it.evaluationReference }.distinct().size == records.size) {
            "specialization evaluation evidence must not be counted more than once"
        }
    }

    companion object {
        const val MAX_RECORDS = 512

        fun create(records: Collection<AgentWorkerSpecializationEvidence>) =
            AgentWorkerSpecializationEvidenceSet(
                records.sortedWith(
                    compareBy<AgentWorkerSpecializationEvidence>(
                        { it.taskClass.ordinal },
                        { it.profile.canonicalKey },
                        { it.observedAt },
                        { it.evaluationReference }
                    )
                )
            )

        fun empty() = create(emptyList())
    }
}

data class AgentWorkerSpecializationPolicy(
    val minimumEvidenceCount: Int = 3,
    val maxEvidencePerProfile: Int = 24,
    val freshWindow: Duration = Duration.ofDays(7),
    val staleAfter: Duration = Duration.ofDays(30),
    val freshWeight: Int = 4,
    val agingWeight: Int = 2,
    val oldWeight: Int = 1
) {
    init {
        require(minimumEvidenceCount > 0)
        require(maxEvidencePerProfile >= minimumEvidenceCount)
        require(maxEvidencePerProfile <= 64)
        require(!freshWindow.isZero && !freshWindow.isNegative)
        require(staleAfter >= freshWindow)
        require(freshWeight >= agingWeight && agingWeight >= oldWeight)
        require(oldWeight > 0)
        require(freshWeight <= 16)
    }
}

enum class AgentWorkerSpecializationSignalState {
    COLD_START,
    INSUFFICIENT_EVIDENCE,
    STALE_EVIDENCE,
    AVAILABLE
}

data class AgentWorkerSpecializationSignal(
    val profile: AgentWorkerSpecializationProfile,
    val state: AgentWorkerSpecializationSignalState,
    val weightedBaselineDelta: Int,
    val evidenceCount: Int,
    val latestObservedAt: Instant?,
    val evidenceReferences: List<String>,
    val provenanceReferences: List<String>
) {
    val coverageCount: Int
        get() = evidenceCount

    val advisoryOnly: Boolean
        get() = true
}

enum class AgentWorkerSpecializationNoRecommendationReason {
    DETERMINISTIC_TASK,
    NO_ELIGIBLE_PROFILE,
    COLD_START,
    INSUFFICIENT_EVIDENCE,
    STALE_EVIDENCE,
    NO_POSITIVE_BASELINE_EVIDENCE,
    INVALID_FUTURE_EVIDENCE
}

sealed interface AgentWorkerSpecializationDecision {
    data class Recommended(
        val profile: AgentWorkerSpecializationProfile,
        val signal: AgentWorkerSpecializationSignal,
        val allSignals: List<AgentWorkerSpecializationSignal>
    ) : AgentWorkerSpecializationDecision

    data class NoRecommendation(
        val reason: AgentWorkerSpecializationNoRecommendationReason,
        val signals: List<AgentWorkerSpecializationSignal>
    ) : AgentWorkerSpecializationDecision
}

object AgentWorkerSpecializationAdvisor {
    fun advise(
        taskClass: AgentWorkerRequirement,
        eligibleProfiles: Collection<AgentWorkerSpecializationProfile>,
        evidence: AgentWorkerSpecializationEvidenceSet,
        now: Instant,
        policy: AgentWorkerSpecializationPolicy = AgentWorkerSpecializationPolicy()
    ): AgentWorkerSpecializationDecision {
        if (taskClass == AgentWorkerRequirement.DETERMINISTIC_CHECK) {
            return AgentWorkerSpecializationDecision.NoRecommendation(
                AgentWorkerSpecializationNoRecommendationReason.DETERMINISTIC_TASK,
                emptyList()
            )
        }

        val compatible = eligibleProfiles
            .distinctBy { it.canonicalKey }
            .filter { profile ->
                when (
                    AgentWorkerRouter.route(
                        taskClass,
                        setOf(profile.workerClass)
                    )
                ) {
                    is AgentWorkerRoutingDecision.Worker -> true
                    AgentWorkerRoutingDecision.DeterministicCheck,
                    AgentWorkerRoutingDecision.Declined -> false
                }
            }
            .sortedBy { it.canonicalKey }

        if (compatible.isEmpty()) {
            return AgentWorkerSpecializationDecision.NoRecommendation(
                AgentWorkerSpecializationNoRecommendationReason.NO_ELIGIBLE_PROFILE,
                emptyList()
            )
        }

        val compatibleKeys = compatible.map { it.canonicalKey }.toSet()
        val relevant = evidence.records.filter {
            it.taskClass == taskClass && it.profile.canonicalKey in compatibleKeys
        }
        if (relevant.any { it.observedAt.isAfter(now) }) {
            return AgentWorkerSpecializationDecision.NoRecommendation(
                AgentWorkerSpecializationNoRecommendationReason.INVALID_FUTURE_EVIDENCE,
                emptyList()
            )
        }

        val signals = compatible.map { profile ->
            buildSignal(profile, relevant, now, policy)
        }

        val available = signals
            .filter { it.state == AgentWorkerSpecializationSignalState.AVAILABLE }
            .sortedWith(
                compareByDescending<AgentWorkerSpecializationSignal> {
                    it.weightedBaselineDelta
                }.thenByDescending {
                    it.evidenceCount
                }.thenByDescending {
                    it.latestObservedAt
                }.thenBy {
                    it.profile.canonicalKey
                }
            )

        val best = available.firstOrNull()
        if (best != null && best.weightedBaselineDelta > 0) {
            return AgentWorkerSpecializationDecision.Recommended(
                profile = best.profile,
                signal = best,
                allSignals = signals
            )
        }

        return AgentWorkerSpecializationDecision.NoRecommendation(
            reason = noRecommendationReason(signals),
            signals = signals
        )
    }

    private fun buildSignal(
        profile: AgentWorkerSpecializationProfile,
        relevant: List<AgentWorkerSpecializationEvidence>,
        now: Instant,
        policy: AgentWorkerSpecializationPolicy
    ): AgentWorkerSpecializationSignal {
        val allForProfile = relevant
            .filter { it.profile.canonicalKey == profile.canonicalKey }
            .sortedByDescending { it.observedAt }

        if (allForProfile.isEmpty()) {
            return emptySignal(profile, AgentWorkerSpecializationSignalState.COLD_START)
        }

        val nonStale = allForProfile.filter {
            Duration.between(it.observedAt, now) <= policy.staleAfter
        }
        if (nonStale.isEmpty()) {
            return AgentWorkerSpecializationSignal(
                profile = profile,
                state = AgentWorkerSpecializationSignalState.STALE_EVIDENCE,
                weightedBaselineDelta = 0,
                evidenceCount = 0,
                latestObservedAt = allForProfile.maxOfOrNull { it.observedAt },
                evidenceReferences = emptyList(),
                provenanceReferences = emptyList()
            )
        }

        val bounded = nonStale.take(policy.maxEvidencePerProfile)
        val state = if (bounded.size < policy.minimumEvidenceCount) {
            AgentWorkerSpecializationSignalState.INSUFFICIENT_EVIDENCE
        } else {
            AgentWorkerSpecializationSignalState.AVAILABLE
        }

        val delta = bounded.sumOf { record ->
            val age = Duration.between(record.observedAt, now)
            comparisonDelta(record.comparisonToBaseline) * recencyWeight(age, policy)
        }

        return AgentWorkerSpecializationSignal(
            profile = profile,
            state = state,
            weightedBaselineDelta = delta,
            evidenceCount = bounded.size,
            latestObservedAt = bounded.maxOfOrNull { it.observedAt },
            evidenceReferences = bounded
                .flatMap { listOf(it.baselineEvaluationReference, it.evaluationReference) }
                .distinct()
                .sorted(),
            provenanceReferences = bounded
                .flatMap { it.provenanceReferences }
                .distinct()
                .sorted()
        )
    }

    private fun emptySignal(
        profile: AgentWorkerSpecializationProfile,
        state: AgentWorkerSpecializationSignalState
    ) = AgentWorkerSpecializationSignal(
        profile = profile,
        state = state,
        weightedBaselineDelta = 0,
        evidenceCount = 0,
        latestObservedAt = null,
        evidenceReferences = emptyList(),
        provenanceReferences = emptyList()
    )

    private fun recencyWeight(
        age: Duration,
        policy: AgentWorkerSpecializationPolicy
    ): Int = when {
        age <= policy.freshWindow -> policy.freshWeight
        age <= policy.freshWindow.multipliedBy(2) -> policy.agingWeight
        else -> policy.oldWeight
    }

    private fun comparisonDelta(comparison: AgentBlueprintEvaluationComparison): Int {
        val relations = listOf(
            comparison.completion,
            comparison.challengedFindings,
            comparison.unresolvedConflicts,
            comparison.wallClock,
            comparison.inference,
            comparison.context,
            comparison.artifacts,
            comparison.retries,
            comparison.cancellations
        )
        return relations.sumOf {
            when (it) {
                AgentBlueprintEvaluationRelation.BETTER -> 1
                AgentBlueprintEvaluationRelation.EQUAL -> 0
                AgentBlueprintEvaluationRelation.WORSE -> -1
            }
        }
    }

    private fun noRecommendationReason(
        signals: List<AgentWorkerSpecializationSignal>
    ): AgentWorkerSpecializationNoRecommendationReason = when {
        signals.all { it.state == AgentWorkerSpecializationSignalState.COLD_START } ->
            AgentWorkerSpecializationNoRecommendationReason.COLD_START
        signals.none { it.state == AgentWorkerSpecializationSignalState.AVAILABLE } &&
            signals.any { it.state == AgentWorkerSpecializationSignalState.INSUFFICIENT_EVIDENCE } ->
            AgentWorkerSpecializationNoRecommendationReason.INSUFFICIENT_EVIDENCE
        signals.none { it.state == AgentWorkerSpecializationSignalState.AVAILABLE } &&
            signals.any { it.state == AgentWorkerSpecializationSignalState.STALE_EVIDENCE } ->
            AgentWorkerSpecializationNoRecommendationReason.STALE_EVIDENCE
        else ->
            AgentWorkerSpecializationNoRecommendationReason.NO_POSITIVE_BASELINE_EVIDENCE
    }
}


enum class AgentWorkerSpecializationSelectionSource {
    BASELINE,
    SPECIALIZATION
}

enum class AgentWorkerSpecializationSelectionRejection {
    INCOMPATIBLE_BASELINE
}

sealed interface AgentWorkerSpecializationSelection {
    data class Selected(
        val candidate: AgentTeamWorkerCandidate,
        val source: AgentWorkerSpecializationSelectionSource,
        val evidenceReferences: List<String>
    ) : AgentWorkerSpecializationSelection

    data class Rejected(
        val reason: AgentWorkerSpecializationSelectionRejection
    ) : AgentWorkerSpecializationSelection
}

object AgentWorkerSpecializationCandidateSelector {
    fun select(
        taskClass: AgentWorkerRequirement,
        baseline: AgentTeamWorkerCandidate,
        alternatives: Collection<AgentTeamWorkerCandidate>,
        evidence: AgentWorkerSpecializationEvidenceSet,
        now: Instant,
        policy: AgentWorkerSpecializationPolicy = AgentWorkerSpecializationPolicy()
    ): AgentWorkerSpecializationSelection {
        val baselineCompatible = when (
            AgentWorkerRouter.route(taskClass, setOf(baseline.workerClass))
        ) {
            is AgentWorkerRoutingDecision.Worker -> true
            AgentWorkerRoutingDecision.DeterministicCheck,
            AgentWorkerRoutingDecision.Declined -> false
        }
        if (!baselineCompatible) {
            return AgentWorkerSpecializationSelection.Rejected(
                AgentWorkerSpecializationSelectionRejection.INCOMPATIBLE_BASELINE
            )
        }

        val sameClassAlternatives = alternatives
            .filter { it.workerClass == baseline.workerClass }
        val candidates = (listOf(baseline) + sameClassAlternatives)
            .distinctBy { it.specializationProfile().canonicalKey }
            .sortedBy { it.specializationProfile().canonicalKey }

        val decision = AgentWorkerSpecializationAdvisor.advise(
            taskClass = taskClass,
            eligibleProfiles = candidates.map { it.specializationProfile() },
            evidence = evidence,
            now = now,
            policy = policy
        )
        val recommended = decision as? AgentWorkerSpecializationDecision.Recommended
            ?: return baselineSelection(baseline)

        val selected = candidates.firstOrNull {
            it.specializationProfile().canonicalKey == recommended.profile.canonicalKey
        } ?: return baselineSelection(baseline)

        if (!selected.budget.isWithin(baseline.budget)) {
            return baselineSelection(baseline)
        }
        if (!selected.cognitiveScope.isWithin(baseline.cognitiveScope)) {
            return baselineSelection(baseline)
        }

        return AgentWorkerSpecializationSelection.Selected(
            candidate = selected,
            source = AgentWorkerSpecializationSelectionSource.SPECIALIZATION,
            evidenceReferences = recommended.signal.evidenceReferences
        )
    }

    private fun baselineSelection(
        baseline: AgentTeamWorkerCandidate
    ) = AgentWorkerSpecializationSelection.Selected(
        candidate = baseline,
        source = AgentWorkerSpecializationSelectionSource.BASELINE,
        evidenceReferences = emptyList()
    )
}

fun AgentTeamWorkerCandidate.specializationProfile() =
    AgentWorkerSpecializationProfile(
        workerClass = workerClass,
        blueprint = blueprint,
        runtime = runtime
    )
