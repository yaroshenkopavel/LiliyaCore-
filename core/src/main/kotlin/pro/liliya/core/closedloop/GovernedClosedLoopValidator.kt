package pro.liliya.core.closedloop

import pro.liliya.core.evaluation.OutcomeEvaluationLookupResult
import pro.liliya.core.evaluation.OutcomeEvaluationRepository
import pro.liliya.core.reflection.BoundedReflectionLookupResult
import pro.liliya.core.reflection.BoundedReflectionRepository
import pro.liliya.core.reflection.ReflectionOutcomeReference
import pro.liliya.core.reliability.OutcomeReliabilityReference
import pro.liliya.core.reliability.ReflectionReliabilityReference
import pro.liliya.core.reliability.ReliabilityAssessment
import pro.liliya.core.reliability.ReliabilityAssessmentRequest
import pro.liliya.core.reliability.StrategyReliabilityReference
import pro.liliya.core.strategy.StrategyAdaptationLookupResult
import pro.liliya.core.strategy.StrategyAdaptationRepository

sealed interface ClosedLoopActionVerificationResult {
    data object Verified : ClosedLoopActionVerificationResult
    data class Rejected(val reason: String) : ClosedLoopActionVerificationResult {
        init { require(reason.isNotBlank()) { "closed-loop action verification reason must not be blank" } }
    }
}

fun interface ClosedLoopActionAttemptVerifier {
    fun verify(action: ClosedLoopActionAttemptReference): ClosedLoopActionVerificationResult
}

data class ClosedLoopReliabilitySnapshot(
    val request: ReliabilityAssessmentRequest,
    val assessment: ReliabilityAssessment
) {
    init {
        require(assessment.requestId == request.id) {
            "closed-loop reliability snapshot request/result mismatch"
        }
    }
}

fun interface ClosedLoopReliabilityResolver {
    fun resolve(reference: ClosedLoopReliabilityReference): ClosedLoopReliabilitySnapshot?
}

sealed interface GovernedClosedLoopValidationResult {
    data object Valid : GovernedClosedLoopValidationResult
    data class Rejected(
        val iteration: ClosedLoopIterationNumber?,
        val reason: String
    ) : GovernedClosedLoopValidationResult {
        init { require(reason.isNotBlank()) { "closed-loop rejection reason must not be blank" } }
    }
}

class GovernedClosedLoopValidator(
    private val actionVerifier: ClosedLoopActionAttemptVerifier,
    private val outcomes: OutcomeEvaluationRepository,
    private val reflections: BoundedReflectionRepository,
    private val strategies: StrategyAdaptationRepository,
    private val reliability: ClosedLoopReliabilityResolver
) {
    fun validate(record: ClosedLoopRecord): GovernedClosedLoopValidationResult {
        for (iteration in record.iterations) {
            val number = iteration.number

            when (val action = actionVerifier.verify(iteration.action)) {
                ClosedLoopActionVerificationResult.Verified -> Unit
                is ClosedLoopActionVerificationResult.Rejected ->
                    return reject(number, "controlled action verification rejected: ${action.reason}")
            }

            val outcome = when (val lookup = outcomes.lookup(iteration.outcome.id)) {
                OutcomeEvaluationLookupResult.Missing ->
                    return reject(number, "outcome evaluation is missing")
                is OutcomeEvaluationLookupResult.Found -> lookup.snapshot.record
                OutcomeEvaluationLookupResult.Corrupt ->
                    return reject(number, "outcome evaluation is corrupt")
                is OutcomeEvaluationLookupResult.Incompatible ->
                    return reject(number, "outcome evaluation is incompatible: ${lookup.reason}")
                is OutcomeEvaluationLookupResult.EncryptionUnavailable ->
                    return reject(number, "outcome evaluation encryption unavailable: ${lookup.category}")
                is OutcomeEvaluationLookupResult.Failed ->
                    return reject(number, "outcome evaluation lookup failed: ${lookup.reason}")
            }

            if (outcome.version != iteration.outcome.version) {
                return reject(number, "outcome evaluation version mismatch")
            }
            if (outcome.plan.id != iteration.action.planningProposalId ||
                outcome.plan.generation != iteration.action.planningGeneration
            ) {
                return reject(number, "outcome plan provenance does not match controlled action")
            }
            if (outcome.decision.id != iteration.action.decisionId ||
                outcome.decision.generation != iteration.action.decisionGeneration
            ) {
                return reject(number, "outcome decision provenance does not match controlled action")
            }
            if (outcome.authorizedActionId != iteration.action.actionId) {
                return reject(number, "outcome authorized action does not match controlled action")
            }
            if (outcome.evidence != iteration.evidence) {
                return reject(number, "outcome evidence does not exactly match iteration observation evidence")
            }
            if (outcome.evaluatedAt < iteration.startedAt ||
                outcome.evaluatedAt > iteration.completedAt
            ) {
                return reject(number, "outcome evaluation timestamp is outside iteration bounds")
            }

            val reflection = when (val lookup = reflections.lookup(iteration.reflection.id)) {
                BoundedReflectionLookupResult.Missing ->
                    return reject(number, "reflection result is missing")
                is BoundedReflectionLookupResult.Found -> lookup.snapshot.record
                BoundedReflectionLookupResult.Corrupt ->
                    return reject(number, "reflection result is corrupt")
                is BoundedReflectionLookupResult.Incompatible ->
                    return reject(number, "reflection result is incompatible: ${lookup.reason}")
                is BoundedReflectionLookupResult.EncryptionUnavailable ->
                    return reject(number, "reflection encryption unavailable: ${lookup.category}")
                is BoundedReflectionLookupResult.Failed ->
                    return reject(number, "reflection lookup failed: ${lookup.reason}")
            }

            if (reflection.result.version != iteration.reflection.version) {
                return reject(number, "reflection version mismatch")
            }
            val exactOutcome = ReflectionOutcomeReference(outcome.id, outcome.version)
            if (reflection.request.outcomes != listOf(exactOutcome)) {
                return reject(number, "reflection inputs do not exactly match the iteration outcome")
            }
            if (reflection.request.evidence != iteration.evidence) {
                return reject(number, "reflection evidence does not exactly match iteration evidence")
            }
            if (reflection.result.completedAt < outcome.evaluatedAt ||
                reflection.result.completedAt > iteration.completedAt
            ) {
                return reject(number, "reflection timestamp is outside the outcome-to-iteration window")
            }

            val strategyRecord = iteration.strategy?.let { strategyReference ->
                when (val lookup = strategies.lookup(strategyReference.id)) {
                    StrategyAdaptationLookupResult.Missing ->
                        return reject(number, "strategy lifecycle is missing")
                    is StrategyAdaptationLookupResult.Found -> lookup.snapshot.record
                    StrategyAdaptationLookupResult.Corrupt ->
                        return reject(number, "strategy lifecycle is corrupt")
                    is StrategyAdaptationLookupResult.Incompatible ->
                        return reject(number, "strategy lifecycle is incompatible: ${lookup.reason}")
                    is StrategyAdaptationLookupResult.EncryptionUnavailable ->
                        return reject(number, "strategy lifecycle encryption unavailable: ${lookup.category}")
                    is StrategyAdaptationLookupResult.Failed ->
                        return reject(number, "strategy lifecycle lookup failed: ${lookup.reason}")
                }.also { found ->
                    if (found.candidate.version != strategyReference.version) {
                        return reject(number, "strategy version mismatch")
                    }
                    if (found.candidate.source.resultId != reflection.result.id ||
                        found.candidate.source.resultVersion != reflection.result.version
                    ) {
                        return reject(number, "strategy provenance does not match exact iteration reflection")
                    }
                    if (found.candidate.createdAt < reflection.result.completedAt) {
                        return reject(number, "strategy candidate predates its iteration reflection")
                    }
                }
            }

            val reliabilitySnapshot = reliability.resolve(iteration.reliability)
                ?: return reject(number, "reliability assessment is missing")
            val assessment = reliabilitySnapshot.assessment
            val request = reliabilitySnapshot.request

            if (assessment.id != iteration.reliability.id ||
                assessment.version != iteration.reliability.version
            ) {
                return reject(number, "reliability assessment identity/version mismatch")
            }
            val exactReliabilityOutcome = OutcomeReliabilityReference(outcome.id, outcome.version)
            if (request.outcomes != listOf(exactReliabilityOutcome)) {
                return reject(number, "reliability outcomes do not exactly match the iteration outcome")
            }
            val exactReliabilityReflection = ReflectionReliabilityReference(
                reflection.result.id,
                reflection.result.version
            )
            if (request.reflections != listOf(exactReliabilityReflection)) {
                return reject(number, "reliability reflections do not exactly match the iteration reflection")
            }
            val expectedStrategies = strategyRecord?.let {
                listOf(
                    StrategyReliabilityReference(
                        it.candidate.id,
                        it.candidate.version
                    )
                )
            } ?: emptyList()
            if (request.strategies != expectedStrategies) {
                return reject(number, "reliability strategies do not exactly match the iteration strategy")
            }
            if (assessment.assessedAt < reflection.result.completedAt ||
                assessment.assessedAt > iteration.completedAt
            ) {
                return reject(number, "reliability assessment timestamp is outside iteration bounds")
            }
            if (assessment.expiresAt <= iteration.completedAt) {
                return reject(number, "reliability assessment is expired at iteration completion")
            }
        }
        return GovernedClosedLoopValidationResult.Valid
    }

    private fun reject(
        iteration: ClosedLoopIterationNumber?,
        reason: String
    ) = GovernedClosedLoopValidationResult.Rejected(iteration, reason)
}
