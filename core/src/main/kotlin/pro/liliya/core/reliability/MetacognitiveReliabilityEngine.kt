package pro.liliya.core.reliability

import java.time.Instant
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.evaluation.OutcomeEvaluationLookupResult
import pro.liliya.core.evaluation.OutcomeEvaluationRecord
import pro.liliya.core.evaluation.OutcomeEvaluationRepository
import pro.liliya.core.evaluation.OutcomeEvaluationStatus
import pro.liliya.core.reflection.BoundedReflectionLookupResult
import pro.liliya.core.reflection.BoundedReflectionRecord
import pro.liliya.core.reflection.BoundedReflectionRepository
import pro.liliya.core.reflection.ReflectionFindingKind
import pro.liliya.core.strategy.StrategyAdaptationLookupResult
import pro.liliya.core.strategy.StrategyAdaptationRecord
import pro.liliya.core.strategy.StrategyAdaptationRepository
import pro.liliya.core.strategy.StrategyValidationDisposition

sealed interface MetacognitiveReliabilityResult {
    data class Completed(val assessment: ReliabilityAssessment) : MetacognitiveReliabilityResult
    data class MissingSource(
        val domain: ReliabilityInputDomain,
        val id: String
    ) : MetacognitiveReliabilityResult
    data class InvalidSource(
        val domain: ReliabilityInputDomain,
        val id: String,
        val reason: String
    ) : MetacognitiveReliabilityResult
    data class Rejected(val reason: String) : MetacognitiveReliabilityResult
}

class MetacognitiveReliabilityEngine(
    private val outcomes: OutcomeEvaluationRepository,
    private val reflections: BoundedReflectionRepository,
    private val strategies: StrategyAdaptationRepository
) {
    fun assess(
        request: ReliabilityAssessmentRequest,
        resultVersion: ReliabilityVersion
    ): MetacognitiveReliabilityResult {
        val loadedOutcomes = LinkedHashMap<OutcomeReliabilityReference, OutcomeEvaluationRecord>()
        for (reference in request.outcomes) {
            when (val lookup = outcomes.lookup(reference.id)) {
                OutcomeEvaluationLookupResult.Missing ->
                    return MetacognitiveReliabilityResult.MissingSource(
                        ReliabilityInputDomain.OUTCOME_EVALUATION,
                        reference.id.value
                    )
                is OutcomeEvaluationLookupResult.Found -> {
                    val record = lookup.snapshot.record
                    if (record.version != reference.version) {
                        return MetacognitiveReliabilityResult.InvalidSource(
                            ReliabilityInputDomain.OUTCOME_EVALUATION,
                            reference.id.value,
                            "outcome evaluation version mismatch"
                        )
                    }
                    loadedOutcomes[reference] = record
                }
                OutcomeEvaluationLookupResult.Corrupt ->
                    return invalid(
                        ReliabilityInputDomain.OUTCOME_EVALUATION,
                        reference.id.value,
                        "corrupt outcome evaluation"
                    )
                is OutcomeEvaluationLookupResult.Incompatible ->
                    return invalid(
                        ReliabilityInputDomain.OUTCOME_EVALUATION,
                        reference.id.value,
                        lookup.reason
                    )
                is OutcomeEvaluationLookupResult.EncryptionUnavailable ->
                    return invalid(
                        ReliabilityInputDomain.OUTCOME_EVALUATION,
                        reference.id.value,
                        "outcome evaluation encryption unavailable"
                    )
                is OutcomeEvaluationLookupResult.Failed ->
                    return invalid(
                        ReliabilityInputDomain.OUTCOME_EVALUATION,
                        reference.id.value,
                        lookup.reason
                    )
            }
        }

        val loadedReflections =
            LinkedHashMap<ReflectionReliabilityReference, BoundedReflectionRecord>()
        for (reference in request.reflections) {
            when (val lookup = reflections.lookup(reference.id)) {
                BoundedReflectionLookupResult.Missing ->
                    return MetacognitiveReliabilityResult.MissingSource(
                        ReliabilityInputDomain.REFLECTION,
                        reference.id.value
                    )
                is BoundedReflectionLookupResult.Found -> {
                    val record = lookup.snapshot.record
                    if (record.result.version != reference.version) {
                        return MetacognitiveReliabilityResult.InvalidSource(
                            ReliabilityInputDomain.REFLECTION,
                            reference.id.value,
                            "reflection version mismatch"
                        )
                    }
                    loadedReflections[reference] = record
                }
                BoundedReflectionLookupResult.Corrupt ->
                    return invalid(
                        ReliabilityInputDomain.REFLECTION,
                        reference.id.value,
                        "corrupt reflection"
                    )
                is BoundedReflectionLookupResult.Incompatible ->
                    return invalid(
                        ReliabilityInputDomain.REFLECTION,
                        reference.id.value,
                        lookup.reason
                    )
                is BoundedReflectionLookupResult.EncryptionUnavailable ->
                    return invalid(
                        ReliabilityInputDomain.REFLECTION,
                        reference.id.value,
                        "reflection encryption unavailable"
                    )
                is BoundedReflectionLookupResult.Failed ->
                    return invalid(
                        ReliabilityInputDomain.REFLECTION,
                        reference.id.value,
                        lookup.reason
                    )
            }
        }

        val loadedStrategies =
            LinkedHashMap<StrategyReliabilityReference, StrategyAdaptationRecord>()
        for (reference in request.strategies) {
            when (val lookup = strategies.lookup(reference.id)) {
                StrategyAdaptationLookupResult.Missing ->
                    return MetacognitiveReliabilityResult.MissingSource(
                        ReliabilityInputDomain.STRATEGY_ADAPTATION,
                        reference.id.value
                    )
                is StrategyAdaptationLookupResult.Found -> {
                    val record = lookup.snapshot.record
                    if (record.candidate.version != reference.version) {
                        return MetacognitiveReliabilityResult.InvalidSource(
                            ReliabilityInputDomain.STRATEGY_ADAPTATION,
                            reference.id.value,
                            "strategy adaptation version mismatch"
                        )
                    }
                    loadedStrategies[reference] = record
                }
                StrategyAdaptationLookupResult.Corrupt ->
                    return invalid(
                        ReliabilityInputDomain.STRATEGY_ADAPTATION,
                        reference.id.value,
                        "corrupt strategy adaptation"
                    )
                is StrategyAdaptationLookupResult.Incompatible ->
                    return invalid(
                        ReliabilityInputDomain.STRATEGY_ADAPTATION,
                        reference.id.value,
                        lookup.reason
                    )
                is StrategyAdaptationLookupResult.EncryptionUnavailable ->
                    return invalid(
                        ReliabilityInputDomain.STRATEGY_ADAPTATION,
                        reference.id.value,
                        "strategy adaptation encryption unavailable"
                    )
                is StrategyAdaptationLookupResult.Failed ->
                    return invalid(
                        ReliabilityInputDomain.STRATEGY_ADAPTATION,
                        reference.id.value,
                        lookup.reason
                    )
            }
        }

        for (link in request.performanceLinks) {
            val strategyRecord = loadedStrategies.getValue(link.strategy)
            if (strategyRecord.adoption.disposition !=
                pro.liliya.core.strategy.StrategyAdoptionDisposition.ADOPT ||
                strategyRecord.applicationIntent == null
            ) {
                return MetacognitiveReliabilityResult.Rejected(
                    "strategy performance link requires an adopted applied strategy"
                )
            }
            val appliedAt = strategyRecord.applicationIntent.createdAt
            if (link.outcomes.any { reference ->
                    loadedOutcomes.getValue(reference).evaluatedAt < appliedAt
                }
            ) {
                return MetacognitiveReliabilityResult.Rejected(
                    "strategy performance outcome cannot predate strategy application"
                )
            }
        }

        val sourceTimes = ArrayList<Instant>()
        sourceTimes += loadedOutcomes.values.map { it.evaluatedAt }
        sourceTimes += loadedReflections.values.map { it.result.completedAt }
        sourceTimes += loadedStrategies.values.map {
            it.applicationIntent?.createdAt ?: it.adoption.decidedAt
        }
        sourceTimes += request.diagnostics.map { Instant.ofEpochMilli(it.timestampMillis) }

        if (sourceTimes.any { it > request.assessedAt }) {
            return MetacognitiveReliabilityResult.Rejected(
                "reliability source timestamp cannot be in the future"
            )
        }

        val requestProvenance = ReliabilityProvenanceReference(
            ReliabilityProvenanceDomain.REQUEST,
            request.id.value,
            request.version.value
        )
        val outcomeProvenance = request.outcomes.map {
            ReliabilityProvenanceReference(
                ReliabilityProvenanceDomain.OUTCOME_EVALUATION,
                it.id.value,
                it.version.value
            )
        }
        val reflectionProvenance = request.reflections.map {
            ReliabilityProvenanceReference(
                ReliabilityProvenanceDomain.REFLECTION,
                it.id.value,
                it.version.value
            )
        }
        val strategyProvenance = request.strategies.map {
            ReliabilityProvenanceReference(
                ReliabilityProvenanceDomain.STRATEGY_ADAPTATION,
                it.id.value,
                it.version.value
            )
        }
        val diagnosticProvenance = request.diagnostics.map { it.provenance() }
        val capabilityProvenance = request.capabilities.map { it.provenance() }

        val signals = listOf(
            evidenceQualitySignal(
                loadedOutcomes.values.toList(),
                provenanceOrRequest(outcomeProvenance, requestProvenance)
            ),
            unresolvedConflictSignal(
                loadedReflections.values.toList(),
                loadedStrategies.values.toList(),
                provenanceOrRequest(
                    canonicalProvenance(reflectionProvenance + strategyProvenance),
                    requestProvenance
                )
            ),
            stalenessSignal(
                request,
                sourceTimes,
                loadedStrategies.values.toList(),
                provenanceOrRequest(
                    canonicalProvenance(
                        outcomeProvenance + reflectionProvenance +
                            strategyProvenance + diagnosticProvenance
                    ),
                    requestProvenance
                )
            ),
            coverageSignal(
                request,
                canonicalProvenance(
                    listOf(requestProvenance) +
                        outcomeProvenance + reflectionProvenance +
                        strategyProvenance + diagnosticProvenance
                )
            ),
            strategyPerformanceSignal(
                request,
                loadedOutcomes,
                canonicalProvenance(
                    listOf(requestProvenance) + request.performanceLinks.flatMap { link ->
                        listOf(
                            ReliabilityProvenanceReference(
                                ReliabilityProvenanceDomain.STRATEGY_ADAPTATION,
                                link.strategy.id.value,
                                link.strategy.version.value
                            )
                        ) + link.outcomes.map {
                            ReliabilityProvenanceReference(
                                ReliabilityProvenanceDomain.OUTCOME_EVALUATION,
                                it.id.value,
                                it.version.value
                            )
                        }
                    }
                )
            ),
            capabilitySignal(
                request,
                canonicalProvenance(capabilityProvenance)
            ),
            diagnosticHealthSignal(
                request,
                provenanceOrRequest(
                    canonicalProvenance(diagnosticProvenance),
                    requestProvenance
                )
            )
        )

        val requestedExpiry = request.assessedAt.plusSeconds(
            request.freshnessPolicy.assessmentTtlSeconds
        )
        val nextStrategyExpiry = loadedStrategies.values
            .map { it.candidate.expiresAt }
            .filter { it > request.assessedAt }
            .minOrNull()
        val expiresAt =
            if (nextStrategyExpiry != null && nextStrategyExpiry < requestedExpiry) {
                nextStrategyExpiry
            } else {
                requestedExpiry
            }

        return MetacognitiveReliabilityResult.Completed(
            ReliabilityAssessment.create(
                requestId = request.id,
                version = resultVersion,
                signals = signals,
                policyId = request.policyId,
                policyVersion = request.policyVersion,
                assessedAt = request.assessedAt,
                expiresAt = expiresAt
            )
        )
    }

    private fun evidenceQualitySignal(
        records: List<OutcomeEvaluationRecord>,
        provenance: List<ReliabilityProvenanceReference>
    ): ReliabilitySignal {
        if (records.isEmpty()) {
            return signal(
                ReliabilitySignalKind.EVIDENCE_QUALITY,
                ReliabilitySignalState.UNKNOWN,
                "no selected outcome evaluations",
                provenance
            )
        }
        val state = when {
            records.any { it.status == OutcomeEvaluationStatus.FAILURE } ->
                ReliabilitySignalState.POOR
            records.any { it.status == OutcomeEvaluationStatus.PARTIAL } ->
                ReliabilitySignalState.CAUTION
            records.any { it.status == OutcomeEvaluationStatus.UNKNOWN } ->
                ReliabilitySignalState.UNKNOWN
            else -> ReliabilitySignalState.GOOD
        }
        return signal(
            ReliabilitySignalKind.EVIDENCE_QUALITY,
            state,
            "derived deterministically from selected outcome evaluation statuses",
            provenance
        )
    }

    private fun unresolvedConflictSignal(
        reflectionRecords: List<BoundedReflectionRecord>,
        strategyRecords: List<StrategyAdaptationRecord>,
        provenance: List<ReliabilityProvenanceReference>
    ): ReliabilitySignal {
        if (reflectionRecords.isEmpty() && strategyRecords.isEmpty()) {
            return signal(
                ReliabilitySignalKind.UNRESOLVED_CONFLICT,
                ReliabilitySignalState.UNKNOWN,
                "no selected reflection or strategy records",
                provenance
            )
        }
        val hasConflict = reflectionRecords.any { record ->
            record.result.findings.any {
                it.kind == ReflectionFindingKind.UNCERTAINTY_OR_CONFLICT
            }
        }
        val hasUnknownStrategy = strategyRecords.any {
            it.validation.disposition == StrategyValidationDisposition.UNKNOWN
        }
        val state =
            if (hasConflict || hasUnknownStrategy) ReliabilitySignalState.POOR
            else ReliabilitySignalState.GOOD
        return signal(
            ReliabilitySignalKind.UNRESOLVED_CONFLICT,
            state,
            "derived from explicit conflict findings and unresolved strategy validation",
            provenance
        )
    }

    private fun stalenessSignal(
        request: ReliabilityAssessmentRequest,
        sourceTimes: List<Instant>,
        strategyRecords: List<StrategyAdaptationRecord>,
        provenance: List<ReliabilityProvenanceReference>
    ): ReliabilitySignal {
        if (sourceTimes.isEmpty()) {
            return signal(
                ReliabilitySignalKind.STALENESS,
                ReliabilitySignalState.UNKNOWN,
                "no timestamped selected sources",
                provenance
            )
        }
        val staleByAge = sourceTimes.any {
            request.assessedAt.epochSecond - it.epochSecond >
                request.freshnessPolicy.maxSourceAgeSeconds
        }
        val expiredStrategy = strategyRecords.any {
            it.candidate.isExpired(request.assessedAt)
        }
        val stale = staleByAge || expiredStrategy
        return signal(
            ReliabilitySignalKind.STALENESS,
            if (stale) ReliabilitySignalState.CAUTION else ReliabilitySignalState.GOOD,
            if (stale) {
                "one or more selected sources exceed the configured freshness bound"
            } else {
                "all selected timestamped sources are inside the configured freshness bound"
            },
            provenance
        )
    }

    private fun coverageSignal(
        request: ReliabilityAssessmentRequest,
        provenance: List<ReliabilityProvenanceReference>
    ): ReliabilitySignal {
        val present = buildSet {
            if (request.outcomes.isNotEmpty()) add(ReliabilityInputDomain.OUTCOME_EVALUATION)
            if (request.reflections.isNotEmpty()) add(ReliabilityInputDomain.REFLECTION)
            if (request.strategies.isNotEmpty()) add(ReliabilityInputDomain.STRATEGY_ADAPTATION)
            if (request.diagnostics.isNotEmpty()) add(ReliabilityInputDomain.DIAGNOSTIC)
        }
        val covered = request.requiredDomains.count { it in present }
        val state = when {
            covered == request.requiredDomains.size -> ReliabilitySignalState.GOOD
            covered == 0 -> ReliabilitySignalState.POOR
            else -> ReliabilitySignalState.CAUTION
        }
        return signal(
            ReliabilitySignalKind.COVERAGE,
            state,
            "selected input domains cover $covered of ${request.requiredDomains.size} required domains",
            provenance
        )
    }

    private fun strategyPerformanceSignal(
        request: ReliabilityAssessmentRequest,
        loadedOutcomes: Map<OutcomeReliabilityReference, OutcomeEvaluationRecord>,
        provenance: List<ReliabilityProvenanceReference>
    ): ReliabilitySignal {
        if (request.performanceLinks.isEmpty()) {
            return signal(
                ReliabilitySignalKind.STRATEGY_PERFORMANCE,
                ReliabilitySignalState.UNKNOWN,
                "no explicit strategy-to-outcome performance links",
                provenance
            )
        }
        val linkedStatuses = request.performanceLinks.flatMap { link ->
            link.outcomes.map { reference -> loadedOutcomes.getValue(reference).status }
        }
        val state = when {
            linkedStatuses.any { it == OutcomeEvaluationStatus.FAILURE } ->
                ReliabilitySignalState.POOR
            linkedStatuses.any { it == OutcomeEvaluationStatus.PARTIAL } ->
                ReliabilitySignalState.CAUTION
            linkedStatuses.any { it == OutcomeEvaluationStatus.UNKNOWN } ->
                ReliabilitySignalState.UNKNOWN
            else -> ReliabilitySignalState.GOOD
        }
        return signal(
            ReliabilitySignalKind.STRATEGY_PERFORMANCE,
            state,
            "derived only from explicit strategy-to-outcome links",
            provenance
        )
    }

    private fun capabilitySignal(
        request: ReliabilityAssessmentRequest,
        provenance: List<ReliabilityProvenanceReference>
    ): ReliabilitySignal {
        val state = when {
            request.capabilities.any { it.state == CapabilityState.UNSUPPORTED } ->
                ReliabilitySignalState.POOR
            request.capabilities.any { it.state == CapabilityState.LIMITED } ->
                ReliabilitySignalState.CAUTION
            request.capabilities.any { it.state == CapabilityState.UNKNOWN } ->
                ReliabilitySignalState.UNKNOWN
            else -> ReliabilitySignalState.GOOD
        }
        return signal(
            ReliabilitySignalKind.CAPABILITY_LIMIT,
            state,
            "derived from explicit bounded capability declarations",
            provenance
        )
    }

    private fun diagnosticHealthSignal(
        request: ReliabilityAssessmentRequest,
        provenance: List<ReliabilityProvenanceReference>
    ): ReliabilitySignal {
        if (request.diagnostics.isEmpty()) {
            return signal(
                ReliabilitySignalKind.DIAGNOSTIC_HEALTH,
                ReliabilitySignalState.UNKNOWN,
                "no selected diagnostic signals",
                provenance
            )
        }
        val state = when {
            request.diagnostics.any {
                it.severity == DiagnosticSeverity.CRITICAL ||
                    it.severity == DiagnosticSeverity.ERROR
            } -> ReliabilitySignalState.POOR
            request.diagnostics.any { it.severity == DiagnosticSeverity.WARNING } ->
                ReliabilitySignalState.CAUTION
            else -> ReliabilitySignalState.GOOD
        }
        return signal(
            ReliabilitySignalKind.DIAGNOSTIC_HEALTH,
            state,
            "derived from explicit selected diagnostic severities",
            provenance
        )
    }

    private fun signal(
        kind: ReliabilitySignalKind,
        state: ReliabilitySignalState,
        rationale: String,
        provenance: List<ReliabilityProvenanceReference>
    ) = ReliabilitySignal(
        kind = kind,
        state = state,
        rationale = rationale,
        provenance = canonicalProvenance(provenance)
    )

    private fun provenanceOrRequest(
        provenance: List<ReliabilityProvenanceReference>,
        request: ReliabilityProvenanceReference
    ): List<ReliabilityProvenanceReference> =
        if (provenance.isEmpty()) listOf(request) else provenance

    private fun canonicalProvenance(
        provenance: List<ReliabilityProvenanceReference>
    ): List<ReliabilityProvenanceReference> =
        provenance.distinct().sortedWith(
            compareBy<ReliabilityProvenanceReference>(
                { it.domain.name },
                { it.id },
                { it.version }
            )
        )

    private fun invalid(
        domain: ReliabilityInputDomain,
        id: String,
        reason: String
    ) = MetacognitiveReliabilityResult.InvalidSource(domain, id, reason)
}
