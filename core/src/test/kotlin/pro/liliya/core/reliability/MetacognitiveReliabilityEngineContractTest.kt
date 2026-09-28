package pro.liliya.core.reliability

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.decision.DecisionGeneration
import pro.liliya.core.decision.DecisionId
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.episodic.RawEvidenceId
import pro.liliya.core.episodic.RawEvidenceNamespace
import pro.liliya.core.episodic.RawEvidenceReference
import pro.liliya.core.evaluation.EvaluatorPolicyId
import pro.liliya.core.evaluation.EvaluatorPolicyVersion
import pro.liliya.core.evaluation.OutcomeDecisionReference
import pro.liliya.core.evaluation.OutcomeEvaluationId
import pro.liliya.core.evaluation.OutcomeEvaluationLookupResult
import pro.liliya.core.evaluation.OutcomeEvaluationPageResult
import pro.liliya.core.evaluation.OutcomeEvaluationRecord
import pro.liliya.core.evaluation.OutcomeEvaluationRepository
import pro.liliya.core.evaluation.OutcomeEvaluationSnapshot
import pro.liliya.core.evaluation.OutcomeEvaluationStatus
import pro.liliya.core.evaluation.OutcomeEvaluationStoreResult
import pro.liliya.core.evaluation.OutcomeEvaluationVersion
import pro.liliya.core.evaluation.OutcomePlanReference
import pro.liliya.core.evaluation.OutcomeText
import pro.liliya.core.evaluation.OutcomeValidationReference
import pro.liliya.core.persistence.PersistentBackendPageCursor
import pro.liliya.core.persistence.PersistentBackendPageOrder
import pro.liliya.core.planning.PlanningGeneration
import pro.liliya.core.planning.PlanningProposalId
import pro.liliya.core.reflection.BoundedReflectionLookupResult
import pro.liliya.core.reflection.BoundedReflectionPageResult
import pro.liliya.core.reflection.BoundedReflectionRecord
import pro.liliya.core.reflection.BoundedReflectionRepository
import pro.liliya.core.reflection.BoundedReflectionResult
import pro.liliya.core.reflection.BoundedReflectionSnapshot
import pro.liliya.core.reflection.BoundedReflectionStoreResult
import pro.liliya.core.reflection.ReflectionFinding
import pro.liliya.core.reflection.ReflectionFindingKind
import pro.liliya.core.reflection.ReflectionFindingText
import pro.liliya.core.reflection.ReflectionOutcomeReference
import pro.liliya.core.reflection.ReflectionPolicyId
import pro.liliya.core.reflection.ReflectionPolicyVersion
import pro.liliya.core.reflection.ReflectionResultId
import pro.liliya.core.reflection.ReflectionRequest
import pro.liliya.core.reflection.ReflectionVersion
import pro.liliya.core.reflection.ReflectionWorkBudget
import pro.liliya.core.strategy.StrategyAdaptationLookupResult
import pro.liliya.core.strategy.StrategyAdaptationPageResult
import pro.liliya.core.strategy.StrategyAdaptationRecord
import pro.liliya.core.strategy.StrategyAdaptationRepository
import pro.liliya.core.strategy.StrategyAdaptationSnapshot
import pro.liliya.core.strategy.StrategyAdaptationStoreResult
import pro.liliya.core.strategy.StrategyAdoptionDisposition
import pro.liliya.core.strategy.StrategyAdoptionRecord
import pro.liliya.core.strategy.StrategyAdoptionReference
import pro.liliya.core.strategy.StrategyApplicationIntent
import pro.liliya.core.strategy.StrategyCandidate
import pro.liliya.core.strategy.StrategyCandidateId
import pro.liliya.core.strategy.StrategyCompatibilityConstraint
import pro.liliya.core.strategy.StrategyConstraintDisposition
import pro.liliya.core.strategy.StrategyConstraintResult
import pro.liliya.core.strategy.StrategyPolicyId
import pro.liliya.core.strategy.StrategyPolicyVersion
import pro.liliya.core.strategy.StrategyReference
import pro.liliya.core.strategy.StrategyReflectionSource
import pro.liliya.core.strategy.StrategyScope
import pro.liliya.core.strategy.StrategyTarget
import pro.liliya.core.strategy.StrategyText
import pro.liliya.core.strategy.StrategyValidationDisposition
import pro.liliya.core.strategy.StrategyValidationRecord
import pro.liliya.core.strategy.StrategyValidationReference
import pro.liliya.core.strategy.StrategyVersion

class MetacognitiveReliabilityEngineContractTest {
    private val t0 = Instant.parse("2026-09-28T16:00:00Z")
    private val outcome = outcome(OutcomeEvaluationStatus.SUCCESS, t0.minusSeconds(5))
    private val reflection = reflection(conflict = false, completedAt = t0.minusSeconds(15))
    private val strategy = strategy(reflection, t0.minusSeconds(10))

    @Test
    fun request_identity_is_deterministic_and_input_order_independent() {
        val otherOutcome = outcome(OutcomeEvaluationStatus.PARTIAL, t0.minusSeconds(30), "2")
        val a = request(outcomes = listOf(outcomeRef(otherOutcome), outcomeRef(outcome)))
        val b = request(outcomes = listOf(outcomeRef(outcome), outcomeRef(otherOutcome)))

        assertEquals(a, b)
        assertEquals(a.id, b.id)
    }

    @Test
    fun performance_links_must_reference_selected_strategy_and_outcomes() {
        val foreignOutcome = outcome(OutcomeEvaluationStatus.SUCCESS, t0.minusSeconds(10), "foreign")
        assertFailsWith<IllegalArgumentException> {
            request(
                performanceLinks = listOf(
                    StrategyPerformanceLink.create(
                        strategyRef(strategy),
                        listOf(outcomeRef(foreignOutcome))
                    )
                )
            )
        }
    }

    @Test
    fun missing_or_wrong_version_source_fails_closed() {
        val missingEngine = MetacognitiveReliabilityEngine(
            FakeOutcomeRepository(null),
            FakeReflectionRepository(reflection),
            FakeStrategyRepository(strategy)
        )
        assertIs<MetacognitiveReliabilityResult.MissingSource>(
            missingEngine.assess(request(), ReliabilityVersion(1))
        )

        val wrong = request(
            outcomes = listOf(
                OutcomeReliabilityReference(outcome.id, OutcomeEvaluationVersion(2))
            ),
            performanceLinks = emptyList()
        )
        assertIs<MetacognitiveReliabilityResult.InvalidSource>(
            engine().assess(wrong, ReliabilityVersion(1))
        )
    }

    @Test
    fun healthy_selected_inputs_produce_advisory_good_signals() {
        val result = assertIs<MetacognitiveReliabilityResult.Completed>(
            engine().assess(request(), ReliabilityVersion(1))
        ).assessment

        val states = result.signals.associate { it.kind to it.state }
        assertEquals(ReliabilitySignalState.GOOD, states[ReliabilitySignalKind.EVIDENCE_QUALITY])
        assertEquals(ReliabilitySignalState.GOOD, states[ReliabilitySignalKind.UNRESOLVED_CONFLICT])
        assertEquals(ReliabilitySignalState.GOOD, states[ReliabilitySignalKind.STALENESS])
        assertEquals(ReliabilitySignalState.GOOD, states[ReliabilitySignalKind.COVERAGE])
        assertEquals(ReliabilitySignalState.GOOD, states[ReliabilitySignalKind.STRATEGY_PERFORMANCE])
        assertEquals(ReliabilitySignalState.GOOD, states[ReliabilitySignalKind.CAPABILITY_LIMIT])
        assertEquals(ReliabilitySignalState.GOOD, states[ReliabilitySignalKind.DIAGNOSTIC_HEALTH])
        assertEquals(t0.plusSeconds(60), result.expiresAt)
        assertTrue(result.signals.all { it.provenance.isNotEmpty() })
    }

    @Test
    fun conflict_partial_warning_limited_and_stale_inputs_are_visible_not_hidden() {
        val partial = outcome(
            OutcomeEvaluationStatus.PARTIAL,
            t0.minusSeconds(300),
            "partial"
        )
        val conflictReflection = reflection(
            conflict = true,
            completedAt = t0.minusSeconds(500)
        )
        val conflictStrategy = strategy(conflictReflection, t0.minusSeconds(400))
        val req = request(
            outcomes = listOf(outcomeRef(partial)),
            reflections = listOf(reflectionRef(conflictReflection)),
            strategies = listOf(strategyRef(conflictStrategy)),
            diagnostics = listOf(
                DiagnosticReliabilityEvidence(
                    9,
                    t0.minusSeconds(5).toEpochMilli(),
                    DiagnosticSeverity.WARNING,
                    "runtime.warning"
                )
            ),
            performanceLinks = listOf(
                StrategyPerformanceLink.create(
                    strategyRef(conflictStrategy),
                    listOf(outcomeRef(partial))
                )
            ),
            capabilities = listOf(
                CapabilityDeclaration(
                    "semantic.retrieval",
                    CapabilityState.LIMITED,
                    "bounded provider coverage"
                )
            ),
            freshness = ReliabilityFreshnessPolicy(120, 60)
        )

        val result = assertIs<MetacognitiveReliabilityResult.Completed>(
            MetacognitiveReliabilityEngine(
                FakeOutcomeRepository(partial),
                FakeReflectionRepository(conflictReflection),
                FakeStrategyRepository(conflictStrategy)
            ).assess(req, ReliabilityVersion(1))
        ).assessment

        val states = result.signals.associate { it.kind to it.state }
        assertEquals(ReliabilitySignalState.CAUTION, states[ReliabilitySignalKind.EVIDENCE_QUALITY])
        assertEquals(ReliabilitySignalState.POOR, states[ReliabilitySignalKind.UNRESOLVED_CONFLICT])
        assertEquals(ReliabilitySignalState.CAUTION, states[ReliabilitySignalKind.STALENESS])
        assertEquals(ReliabilitySignalState.CAUTION, states[ReliabilitySignalKind.STRATEGY_PERFORMANCE])
        assertEquals(ReliabilitySignalState.CAUTION, states[ReliabilitySignalKind.CAPABILITY_LIMIT])
        assertEquals(ReliabilitySignalState.CAUTION, states[ReliabilitySignalKind.DIAGNOSTIC_HEALTH])
    }

    @Test
    fun future_source_timestamp_fails_closed() {
        val futureOutcome = outcome(
            OutcomeEvaluationStatus.SUCCESS,
            t0.plusSeconds(1),
            "future"
        )
        val req = request(
            outcomes = listOf(outcomeRef(futureOutcome)),
            performanceLinks = emptyList()
        )
        assertIs<MetacognitiveReliabilityResult.Rejected>(
            MetacognitiveReliabilityEngine(
                FakeOutcomeRepository(futureOutcome),
                FakeReflectionRepository(reflection),
                FakeStrategyRepository(strategy)
            ).assess(req, ReliabilityVersion(1))
        )
    }

    @Test
    fun coverage_is_explicit_and_does_not_invent_missing_domains() {
        val req = request(
            reflections = emptyList(),
            strategies = emptyList(),
            diagnostics = emptyList(),
            performanceLinks = emptyList(),
            requiredDomains = listOf(
                ReliabilityInputDomain.OUTCOME_EVALUATION,
                ReliabilityInputDomain.REFLECTION,
                ReliabilityInputDomain.STRATEGY_ADAPTATION
            )
        )
        val result = assertIs<MetacognitiveReliabilityResult.Completed>(
            MetacognitiveReliabilityEngine(
                FakeOutcomeRepository(outcome),
                FakeReflectionRepository(null),
                FakeStrategyRepository(null)
            ).assess(req, ReliabilityVersion(1))
        ).assessment

        assertEquals(
            ReliabilitySignalState.CAUTION,
            result.signals.single { it.kind == ReliabilitySignalKind.COVERAGE }.state
        )
    }

    @Test
    fun assessment_expiry_is_clamped_to_next_strategy_expiry() {
        val shortStrategy = strategy(
            reflection,
            applicationAt = t0.minusSeconds(10),
            expiresAt = t0.plusSeconds(30)
        )
        val req = request(
            strategies = listOf(strategyRef(shortStrategy)),
            performanceLinks = listOf(
                StrategyPerformanceLink.create(
                    strategyRef(shortStrategy),
                    listOf(outcomeRef(outcome))
                )
            )
        )
        val result = assertIs<MetacognitiveReliabilityResult.Completed>(
            MetacognitiveReliabilityEngine(
                FakeOutcomeRepository(outcome),
                FakeReflectionRepository(reflection),
                FakeStrategyRepository(shortStrategy)
            ).assess(req, ReliabilityVersion(1))
        ).assessment

        assertEquals(t0.plusSeconds(30), result.expiresAt)
    }

    @Test
    fun strategy_performance_requires_adopted_applied_strategy() {
        val rejectedStrategy = strategy(
            reflection,
            applicationAt = t0.minusSeconds(10),
            adopted = false
        )
        val req = request(
            strategies = listOf(strategyRef(rejectedStrategy)),
            performanceLinks = listOf(
                StrategyPerformanceLink.create(
                    strategyRef(rejectedStrategy),
                    listOf(outcomeRef(outcome))
                )
            )
        )

        assertIs<MetacognitiveReliabilityResult.Rejected>(
            MetacognitiveReliabilityEngine(
                FakeOutcomeRepository(outcome),
                FakeReflectionRepository(reflection),
                FakeStrategyRepository(rejectedStrategy)
            ).assess(req, ReliabilityVersion(1))
        )
    }

    @Test
    fun strategy_performance_outcome_cannot_predate_strategy_application() {
        val earlyOutcome = outcome(
            OutcomeEvaluationStatus.SUCCESS,
            t0.minusSeconds(20),
            "early"
        )
        val req = request(
            outcomes = listOf(outcomeRef(earlyOutcome)),
            performanceLinks = listOf(
                StrategyPerformanceLink.create(
                    strategyRef(strategy),
                    listOf(outcomeRef(earlyOutcome))
                )
            )
        )

        assertIs<MetacognitiveReliabilityResult.Rejected>(
            MetacognitiveReliabilityEngine(
                FakeOutcomeRepository(earlyOutcome),
                FakeReflectionRepository(reflection),
                FakeStrategyRepository(strategy)
            ).assess(req, ReliabilityVersion(1))
        )
    }

    @Test
    fun stage_w_contract_contains_no_authority_execution_or_strategy_mutation_material() {
        val fields = listOf(
            ReliabilityAssessmentRequest::class.java,
            ReliabilityAssessment::class.java,
            ReliabilitySignal::class.java,
            CapabilityDeclaration::class.java
        ).flatMap { type -> type.declaredFields.map { it.name.lowercase() } }

        listOf(
            "authority",
            "principal",
            "capabilitytoken",
            "permission",
            "execution",
            "adoptstrategy",
            "applystrategy",
            "selfpatch"
        ).forEach { forbidden ->
            assertTrue(fields.none { forbidden in it })
        }
    }

    @Test
    fun signal_provenance_must_be_canonical_and_unique() {
        val a = ReliabilityProvenanceReference(
            ReliabilityProvenanceDomain.OUTCOME_EVALUATION,
            "a",
            1
        )
        val b = ReliabilityProvenanceReference(
            ReliabilityProvenanceDomain.OUTCOME_EVALUATION,
            "b",
            1
        )

        assertFailsWith<IllegalArgumentException> {
            ReliabilitySignal(
                ReliabilitySignalKind.EVIDENCE_QUALITY,
                ReliabilitySignalState.GOOD,
                "invalid order",
                listOf(b, a)
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ReliabilitySignal(
                ReliabilitySignalKind.EVIDENCE_QUALITY,
                ReliabilitySignalState.GOOD,
                "duplicate",
                listOf(a, a)
            )
        }
    }

    private fun request(
        outcomes: List<OutcomeReliabilityReference> = listOf(outcomeRef(outcome)),
        reflections: List<ReflectionReliabilityReference> = listOf(reflectionRef(reflection)),
        strategies: List<StrategyReliabilityReference> = listOf(strategyRef(strategy)),
        diagnostics: List<DiagnosticReliabilityEvidence> = listOf(
            DiagnosticReliabilityEvidence(
                1,
                t0.minusSeconds(5).toEpochMilli(),
                DiagnosticSeverity.INFO,
                "runtime.ok"
            )
        ),
        performanceLinks: List<StrategyPerformanceLink> = listOf(
            StrategyPerformanceLink.create(
                strategyRef(strategy),
                listOf(outcomeRef(outcome))
            )
        ),
        capabilities: List<CapabilityDeclaration> = listOf(
            CapabilityDeclaration(
                "semantic.retrieval",
                CapabilityState.SUPPORTED,
                "accepted exact provider contract"
            )
        ),
        requiredDomains: List<ReliabilityInputDomain> = listOf(
            ReliabilityInputDomain.OUTCOME_EVALUATION,
            ReliabilityInputDomain.REFLECTION,
            ReliabilityInputDomain.STRATEGY_ADAPTATION,
            ReliabilityInputDomain.DIAGNOSTIC
        ),
        freshness: ReliabilityFreshnessPolicy = ReliabilityFreshnessPolicy(120, 60)
    ) = ReliabilityAssessmentRequest.create(
        version = ReliabilityVersion(1),
        outcomes = outcomes,
        reflections = reflections,
        strategies = strategies,
        diagnostics = diagnostics,
        performanceLinks = performanceLinks,
        capabilities = capabilities,
        requiredDomains = requiredDomains,
        freshnessPolicy = freshness,
        policyId = ReliabilityPolicyId("metacognitive-reliability-v1"),
        policyVersion = ReliabilityPolicyVersion(1),
        assessedAt = t0
    )

    private fun engine() = MetacognitiveReliabilityEngine(
        FakeOutcomeRepository(outcome),
        FakeReflectionRepository(reflection),
        FakeStrategyRepository(strategy)
    )

    private fun outcomeRef(record: OutcomeEvaluationRecord) =
        OutcomeReliabilityReference(record.id, record.version)

    private fun reflectionRef(record: BoundedReflectionRecord) =
        ReflectionReliabilityReference(record.result.id, record.result.version)

    private fun strategyRef(record: StrategyAdaptationRecord) =
        StrategyReliabilityReference(record.candidate.id, record.candidate.version)

    private fun outcome(
        status: OutcomeEvaluationStatus,
        evaluatedAt: Instant,
        suffix: String = "1"
    ) = OutcomeEvaluationRecord.create(
        version = OutcomeEvaluationVersion(1),
        plan = OutcomePlanReference(
            PlanningProposalId("plan-$suffix"),
            PlanningGeneration(1)
        ),
        decision = OutcomeDecisionReference(
            DecisionId("decision-$suffix"),
            DecisionGeneration(1)
        ),
        expected = OutcomeText("expected $suffix"),
        observed = OutcomeText("observed $suffix"),
        evidence = listOf(
            RawEvidenceReference(
                RawEvidenceNamespace("observation"),
                RawEvidenceId("obs-$suffix")
            )
        ),
        validationReferences = listOf(
            OutcomeValidationReference("validation-$suffix")
        ),
        status = status,
        evaluatorPolicyId = EvaluatorPolicyId("evaluator-v1"),
        evaluatorPolicyVersion = EvaluatorPolicyVersion(1),
        evaluatedAt = evaluatedAt
    )

    private fun reflection(
        conflict: Boolean,
        completedAt: Instant
    ): BoundedReflectionRecord {
        val raw = RawEvidenceReference(
            RawEvidenceNamespace("observation"),
            RawEvidenceId(if (conflict) "conflict" else "normal")
        )
        val request = ReflectionRequest.create(
            version = ReflectionVersion(1),
            evidence = listOf(raw),
            outcomes = emptyList(),
            budget = ReflectionWorkBudget(4, 4096),
            policyId = ReflectionPolicyId("bounded-reflection-v1"),
            policyVersion = ReflectionPolicyVersion(1),
            requestedAt = completedAt.minusSeconds(1)
        )
        val findings = buildList {
            add(
                ReflectionFinding(
                    ReflectionFindingKind.STRATEGY_CANDIDATE_INPUT,
                    ReflectionFindingText("prefer validated reciprocal retrieval"),
                    evidence = listOf(raw)
                )
            )
            if (conflict) {
                add(
                    ReflectionFinding(
                        ReflectionFindingKind.UNCERTAINTY_OR_CONFLICT,
                        ReflectionFindingText("conflicting evidence remains"),
                        evidence = listOf(raw)
                    )
                )
            }
        }
        val result = BoundedReflectionResult.create(
            version = ReflectionVersion(1),
            requestId = request.id,
            findings = findings,
            policyId = request.policyId,
            policyVersion = request.policyVersion,
            completedAt = completedAt
        )
        return BoundedReflectionRecord(request, result)
    }

    private fun strategy(
        source: BoundedReflectionRecord,
        applicationAt: Instant,
        adopted: Boolean = true,
        expiresAt: Instant = applicationAt.plusSeconds(600)
    ): StrategyAdaptationRecord {
        val candidate = StrategyCandidate.create(
            version = StrategyVersion(1),
            source = StrategyReflectionSource(
                source.result.id,
                source.result.version,
                0,
                ReflectionFindingKind.STRATEGY_CANDIDATE_INPUT
            ),
            target = StrategyTarget.RETRIEVAL,
            scope = StrategyScope("semantic.retrieval.ranking"),
            proposal = StrategyText("prefer validated reciprocal retrieval"),
            compatibility = listOf(
                StrategyCompatibilityConstraint("abi", "arm64-v8a"),
                StrategyCompatibilityConstraint("runtime", "offline")
            ),
            rollbackTo = null,
            createdAt = applicationAt.minusSeconds(3),
            expiresAt = expiresAt
        )
        val ref = StrategyReference(candidate.id, candidate.version)
        val validation = StrategyValidationRecord.create(
            candidate = ref,
            disposition = StrategyValidationDisposition.VALID,
            constraintResults = candidate.compatibility.map {
                StrategyConstraintResult(
                    it,
                    StrategyConstraintDisposition.SATISFIED,
                    "compatible"
                )
            },
            policyId = StrategyPolicyId("strategy-validation-v1"),
            policyVersion = StrategyPolicyVersion(1),
            validatedAt = applicationAt.minusSeconds(2)
        )
        val adoption = StrategyAdoptionRecord.create(
            candidate = ref,
            validation = StrategyValidationReference(validation.id, ref),
            disposition = if (adopted) {
                StrategyAdoptionDisposition.ADOPT
            } else {
                StrategyAdoptionDisposition.REJECT
            },
            rationale = if (adopted) "accepted" else "rejected",
            decidedAt = applicationAt.minusSeconds(1)
        )
        val intent = if (adopted) {
            StrategyApplicationIntent.create(
                candidate = ref,
                adoption = StrategyAdoptionReference(adoption.id, ref),
                target = candidate.target,
                scope = candidate.scope,
                createdAt = applicationAt
            )
        } else {
            null
        }
        return StrategyAdaptationRecord(candidate, validation, adoption, intent)
    }

    private class FakeOutcomeRepository(
        private val record: OutcomeEvaluationRecord?
    ) : OutcomeEvaluationRepository {
        override fun store(record: OutcomeEvaluationRecord): OutcomeEvaluationStoreResult =
            error("not used")

        override fun lookup(id: OutcomeEvaluationId): OutcomeEvaluationLookupResult =
            if (record == null || record.id != id) {
                OutcomeEvaluationLookupResult.Missing
            } else {
                OutcomeEvaluationLookupResult.Found(OutcomeEvaluationSnapshot(record, 1))
            }

        override fun page(
            limit: Int,
            order: PersistentBackendPageOrder,
            cursorExclusive: PersistentBackendPageCursor?
        ): OutcomeEvaluationPageResult = error("not used")
    }

    private class FakeReflectionRepository(
        private val record: BoundedReflectionRecord?
    ) : BoundedReflectionRepository {
        override fun store(record: BoundedReflectionRecord): BoundedReflectionStoreResult =
            error("not used")

        override fun lookup(id: ReflectionResultId): BoundedReflectionLookupResult =
            if (record == null || record.result.id != id) {
                BoundedReflectionLookupResult.Missing
            } else {
                BoundedReflectionLookupResult.Found(BoundedReflectionSnapshot(record, 1))
            }

        override fun page(
            limit: Int,
            order: PersistentBackendPageOrder,
            cursorExclusive: PersistentBackendPageCursor?
        ): BoundedReflectionPageResult = error("not used")
    }

    private class FakeStrategyRepository(
        private val record: StrategyAdaptationRecord?
    ) : StrategyAdaptationRepository {
        override fun store(record: StrategyAdaptationRecord): StrategyAdaptationStoreResult =
            error("not used")

        override fun lookup(id: StrategyCandidateId): StrategyAdaptationLookupResult =
            if (record == null || record.candidate.id != id) {
                StrategyAdaptationLookupResult.Missing
            } else {
                StrategyAdaptationLookupResult.Found(
                    StrategyAdaptationSnapshot(record, 1)
                )
            }

        override fun page(
            limit: Int,
            order: PersistentBackendPageOrder,
            cursorExclusive: PersistentBackendPageCursor?
        ): StrategyAdaptationPageResult = error("not used")
    }
}
