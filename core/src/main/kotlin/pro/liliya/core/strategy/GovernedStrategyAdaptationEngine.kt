package pro.liliya.core.strategy

import java.time.Instant
import pro.liliya.core.reflection.BoundedReflectionLookupResult
import pro.liliya.core.reflection.BoundedReflectionRepository
import pro.liliya.core.reflection.ReflectionFindingKind

fun interface StrategyCompatibilityEvaluator {
    fun evaluate(candidate: StrategyCandidate): List<StrategyConstraintResult>
}

sealed interface StrategyValidationExecutionResult {
    data class Validated(val record: StrategyValidationRecord) : StrategyValidationExecutionResult
    data class SourceMissing(val candidate: StrategyCandidateId) : StrategyValidationExecutionResult
    data class SourceInvalid(val reason: String) : StrategyValidationExecutionResult
    data class Rejected(val reason: String) : StrategyValidationExecutionResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        StrategyValidationExecutionResult
}

sealed interface StrategyAdoptionExecutionResult {
    data class Decided(val record: StrategyAdoptionRecord) : StrategyAdoptionExecutionResult
    data class Rejected(val reason: String) : StrategyAdoptionExecutionResult
}

sealed interface StrategyApplicationIntentResult {
    data class Prepared(val intent: StrategyApplicationIntent) : StrategyApplicationIntentResult
    data class Rejected(val reason: String) : StrategyApplicationIntentResult
}

class GovernedStrategyAdaptationEngine(
    private val reflections: BoundedReflectionRepository,
    private val compatibilityEvaluator: StrategyCompatibilityEvaluator,
    private val strategies: StrategyAdaptationRepository
) {
    fun validate(
        candidate: StrategyCandidate,
        policyId: StrategyPolicyId,
        policyVersion: StrategyPolicyVersion,
        validatedAt: Instant
    ): StrategyValidationExecutionResult {
        if (validatedAt < candidate.createdAt) {
            return StrategyValidationExecutionResult.Rejected(
                "strategy validation cannot predate candidate"
            )
        }

        val sourceRecord = when (val lookup = reflections.lookup(candidate.source.resultId)) {
            BoundedReflectionLookupResult.Missing ->
                return StrategyValidationExecutionResult.SourceMissing(candidate.id)
            is BoundedReflectionLookupResult.Found -> lookup.snapshot.record
            BoundedReflectionLookupResult.Corrupt ->
                return StrategyValidationExecutionResult.SourceInvalid("corrupt reflection source")
            is BoundedReflectionLookupResult.Incompatible ->
                return StrategyValidationExecutionResult.SourceInvalid(lookup.reason)
            is BoundedReflectionLookupResult.EncryptionUnavailable ->
                return StrategyValidationExecutionResult.SourceInvalid(
                    "reflection source encryption unavailable"
                )
            is BoundedReflectionLookupResult.Failed ->
                return StrategyValidationExecutionResult.SourceInvalid(lookup.reason)
        }

        if (sourceRecord.result.version != candidate.source.resultVersion) {
            return StrategyValidationExecutionResult.SourceInvalid(
                "reflection source version mismatch"
            )
        }
        val finding = sourceRecord.result.findings.getOrNull(candidate.source.findingIndex)
            ?: return StrategyValidationExecutionResult.SourceInvalid(
                "reflection source finding index is out of bounds"
            )
        if (finding.kind != ReflectionFindingKind.STRATEGY_CANDIDATE_INPUT ||
            finding.kind != candidate.source.findingKind
        ) {
            return StrategyValidationExecutionResult.SourceInvalid(
                "reflection source finding kind mismatch"
            )
        }
        if (finding.text.value != candidate.proposal.value) {
            return StrategyValidationExecutionResult.SourceInvalid(
                "strategy proposal does not match exact reflection finding"
            )
        }

        candidate.rollbackTo?.let { rollback ->
            when (val lookup = strategies.lookup(rollback.id)) {
                StrategyAdaptationLookupResult.Missing ->
                    return StrategyValidationExecutionResult.Rejected(
                        "rollback strategy is missing"
                    )
                is StrategyAdaptationLookupResult.Found -> {
                    val previous = lookup.snapshot.record
                    if (previous.candidate.version != rollback.version) {
                        return StrategyValidationExecutionResult.Rejected(
                            "rollback strategy version mismatch"
                        )
                    }
                    if (previous.adoption.disposition != StrategyAdoptionDisposition.ADOPT ||
                        previous.applicationIntent == null
                    ) {
                        return StrategyValidationExecutionResult.Rejected(
                            "rollback strategy is not an adopted application state"
                        )
                    }
                    if (previous.candidate.target != candidate.target ||
                        previous.candidate.scope != candidate.scope
                    ) {
                        return StrategyValidationExecutionResult.Rejected(
                            "rollback strategy target or scope is incompatible"
                        )
                    }
                }
                StrategyAdaptationLookupResult.Corrupt ->
                    return StrategyValidationExecutionResult.Rejected(
                        "rollback strategy is corrupt"
                    )
                is StrategyAdaptationLookupResult.Incompatible ->
                    return StrategyValidationExecutionResult.Rejected(lookup.reason)
                is StrategyAdaptationLookupResult.EncryptionUnavailable ->
                    return StrategyValidationExecutionResult.Rejected(
                        "rollback strategy encryption unavailable"
                    )
                is StrategyAdaptationLookupResult.Failed ->
                    return StrategyValidationExecutionResult.Rejected(lookup.reason)
            }
        }

        if (candidate.isExpired(validatedAt)) {
            val expiredResults = candidate.compatibility.map {
                StrategyConstraintResult(
                    it,
                    StrategyConstraintDisposition.UNKNOWN,
                    "candidate expired before compatibility evaluation"
                )
            }
            return StrategyValidationExecutionResult.Validated(
                StrategyValidationRecord.create(
                    candidate = StrategyReference(candidate.id, candidate.version),
                    disposition = StrategyValidationDisposition.EXPIRED,
                    constraintResults = expiredResults,
                    policyId = policyId,
                    policyVersion = policyVersion,
                    validatedAt = validatedAt
                )
            )
        }

        val results = try {
            compatibilityEvaluator.evaluate(candidate)
        } catch (t: Throwable) {
            return StrategyValidationExecutionResult.Failed(
                "strategy compatibility evaluation failed",
                t
            )
        }
        if (results.map { it.constraint } != candidate.compatibility) {
            return StrategyValidationExecutionResult.Rejected(
                "compatibility evaluator must return exact canonical candidate constraints"
            )
        }

        val disposition = when {
            results.any { it.disposition == StrategyConstraintDisposition.UNSATISFIED } ->
                StrategyValidationDisposition.INVALID
            results.any { it.disposition == StrategyConstraintDisposition.UNKNOWN } ->
                StrategyValidationDisposition.UNKNOWN
            else -> StrategyValidationDisposition.VALID
        }

        return StrategyValidationExecutionResult.Validated(
            StrategyValidationRecord.create(
                candidate = StrategyReference(candidate.id, candidate.version),
                disposition = disposition,
                constraintResults = results,
                policyId = policyId,
                policyVersion = policyVersion,
                validatedAt = validatedAt
            )
        )
    }

    fun decide(
        candidate: StrategyCandidate,
        validation: StrategyValidationRecord,
        disposition: StrategyAdoptionDisposition,
        rationale: String,
        decidedAt: Instant
    ): StrategyAdoptionExecutionResult {
        val ref = StrategyReference(candidate.id, candidate.version)
        if (validation.candidate != ref) {
            return StrategyAdoptionExecutionResult.Rejected(
                "strategy validation does not belong to candidate"
            )
        }
        if (decidedAt < validation.validatedAt) {
            return StrategyAdoptionExecutionResult.Rejected(
                "strategy adoption decision cannot predate validation"
            )
        }
        if (disposition == StrategyAdoptionDisposition.ADOPT) {
            if (validation.disposition != StrategyValidationDisposition.VALID) {
                return StrategyAdoptionExecutionResult.Rejected(
                    "only a valid strategy candidate may be adopted"
                )
            }
            if (candidate.isExpired(decidedAt)) {
                return StrategyAdoptionExecutionResult.Rejected(
                    "expired strategy candidate cannot be adopted"
                )
            }
        }

        return try {
            StrategyAdoptionExecutionResult.Decided(
                StrategyAdoptionRecord.create(
                    candidate = ref,
                    validation = StrategyValidationReference(validation.id, ref),
                    disposition = disposition,
                    rationale = rationale,
                    decidedAt = decidedAt
                )
            )
        } catch (e: IllegalArgumentException) {
            StrategyAdoptionExecutionResult.Rejected(
                e.message ?: "invalid strategy adoption decision"
            )
        }
    }

    fun prepareApplicationIntent(
        candidate: StrategyCandidate,
        validation: StrategyValidationRecord,
        adoption: StrategyAdoptionRecord,
        createdAt: Instant
    ): StrategyApplicationIntentResult {
        val ref = StrategyReference(candidate.id, candidate.version)
        if (validation.candidate != ref || adoption.candidate != ref) {
            return StrategyApplicationIntentResult.Rejected(
                "strategy application chain does not belong to candidate"
            )
        }
        if (adoption.validation.id != validation.id) {
            return StrategyApplicationIntentResult.Rejected(
                "strategy adoption does not reference exact validation"
            )
        }
        if (adoption.disposition != StrategyAdoptionDisposition.ADOPT) {
            return StrategyApplicationIntentResult.Rejected(
                "rejected strategy cannot create application intent"
            )
        }
        if (validation.disposition != StrategyValidationDisposition.VALID) {
            return StrategyApplicationIntentResult.Rejected(
                "application intent requires valid strategy"
            )
        }
        if (createdAt < adoption.decidedAt) {
            return StrategyApplicationIntentResult.Rejected(
                "application intent cannot predate adoption"
            )
        }
        if (candidate.isExpired(createdAt)) {
            return StrategyApplicationIntentResult.Rejected(
                "expired strategy cannot create application intent"
            )
        }

        return StrategyApplicationIntentResult.Prepared(
            StrategyApplicationIntent.create(
                candidate = ref,
                adoption = StrategyAdoptionReference(adoption.id, ref),
                target = candidate.target,
                scope = candidate.scope,
                createdAt = createdAt
            )
        )
    }
}
