package pro.liliya.core.reflection

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import pro.liliya.core.decision.DecisionGeneration
import pro.liliya.core.decision.DecisionId
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

class BoundedReflectionEngineContractTest {
    private val t0 = Instant.parse("2026-09-28T12:00:00Z")
    private val raw = evidence("observation", "obs-1")
    private val outcome = outcomeRecord()
    private val outcomeRef = ReflectionOutcomeReference(outcome.id, outcome.version)

    @Test
    fun request_identity_is_deterministic_and_input_order_independent() {
        val otherRaw = evidence("conversation", "msg-2")
        val a = request(evidence = listOf(raw, otherRaw))
        val b = request(evidence = listOf(otherRaw, raw))

        assertEquals(a, b)
        assertEquals(a.id, b.id)
        assertEquals(listOf("conversation", "observation"), a.evidence.map { it.namespace.value })
    }

    @Test
    fun request_identity_changes_with_policy_or_budget() {
        val base = request()
        val policyChanged = request(policyVersion = ReflectionPolicyVersion(2))
        val budgetChanged = request(budget = ReflectionWorkBudget(3, 4096))

        assertNotEquals(base.id, policyChanged.id)
        assertNotEquals(base.id, budgetChanged.id)
    }

    @Test
    fun duplicate_inputs_fail_closed_instead_of_silent_deduplication() {
        assertFailsWith<IllegalArgumentException> {
            request(evidence = listOf(raw, raw))
        }
        assertFailsWith<IllegalArgumentException> {
            request(outcomes = listOf(outcomeRef, outcomeRef), evidence = emptyList())
        }
    }

    @Test
    fun work_budget_is_hard_bounded() {
        assertFailsWith<IllegalArgumentException> {
            ReflectionWorkBudget(ReflectionWorkBudget.MAX_FINDINGS + 1, 1024)
        }
        assertFailsWith<IllegalArgumentException> {
            ReflectionWorkBudget(1, ReflectionWorkBudget.MAX_TOTAL_OUTPUT_UTF8_BYTES + 1)
        }
    }

    @Test
    fun engine_fails_closed_when_required_evidence_is_missing() {
        val engine = engine(
            resolver = RawEvidenceReferenceResolver { RawEvidenceResolutionResult.Missing }
        )

        assertIs<ReflectionExecutionResult.MissingEvidence>(
            engine.reflect(request(outcomes = emptyList()), ReflectionVersion(1), t0.plusSeconds(1))
        )
    }

    @Test
    fun engine_fails_closed_when_outcome_is_missing_or_wrong_version() {
        val missing = engine(repository = FakeOutcomeRepository(null))
        assertIs<ReflectionExecutionResult.MissingOutcome>(
            missing.reflect(request(evidence = emptyList()), ReflectionVersion(1), t0.plusSeconds(1))
        )

        val wrongVersionRequest = request(
            evidence = emptyList(),
            outcomes = listOf(ReflectionOutcomeReference(outcome.id, OutcomeEvaluationVersion(2)))
        )
        val mismatch = engine(repository = FakeOutcomeRepository(outcome))
        assertIs<ReflectionExecutionResult.InvalidOutcome>(
            mismatch.reflect(wrongVersionRequest, ReflectionVersion(1), t0.plusSeconds(1))
        )
    }

    @Test
    fun engine_rejects_findings_with_provenance_outside_request() {
        val foreign = evidence("observation", "foreign")
        val engine = engine(
            analyzer = ReflectionAnalyzer {
                listOf(
                    ReflectionFinding(
                        ReflectionFindingKind.EXPLANATION_CANDIDATE,
                        ReflectionFindingText("candidate explanation"),
                        evidence = listOf(foreign)
                    )
                )
            }
        )

        assertIs<ReflectionExecutionResult.Rejected>(
            engine.reflect(request(), ReflectionVersion(1), t0.plusSeconds(1))
        )
    }

    @Test
    fun engine_rejects_output_that_exceeds_request_budget() {
        val engine = engine(
            analyzer = ReflectionAnalyzer {
                listOf(
                    ReflectionFinding(
                        ReflectionFindingKind.MISSING_CONTEXT,
                        ReflectionFindingText("1234567890"),
                        evidence = listOf(raw)
                    )
                )
            }
        )
        val tiny = request(budget = ReflectionWorkBudget(1, 5))

        assertIs<ReflectionExecutionResult.Rejected>(
            engine.reflect(tiny, ReflectionVersion(1), t0.plusSeconds(1))
        )
    }

    @Test
    fun completed_result_is_provenance_bearing_and_deterministic() {
        val request = request()
        val analyzer = ReflectionAnalyzer {
            listOf(
                ReflectionFinding(
                    ReflectionFindingKind.UNCERTAINTY_OR_CONFLICT,
                    ReflectionFindingText("observed result remains uncertain"),
                    evidence = listOf(raw),
                    outcomes = listOf(outcomeRef)
                )
            )
        }
        val engine = engine(analyzer = analyzer)
        val a = assertIs<ReflectionExecutionResult.Completed>(
            engine.reflect(request, ReflectionVersion(1), t0.plusSeconds(2))
        ).result
        val b = assertIs<ReflectionExecutionResult.Completed>(
            engine.reflect(request, ReflectionVersion(1), t0.plusSeconds(2))
        ).result

        assertEquals(a, b)
        assertEquals(request.id, a.requestId)
        assertEquals(outcomeRef, a.findings.single().outcomes.single())
        assertEquals(raw, a.findings.single().evidence.single())
    }

    @Test
    fun bounded_stage_u_contract_contains_no_authority_or_strategy_adoption_material() {
        val fieldNames = (
            ReflectionRequest::class.java.declaredFields.map { it.name } +
                BoundedReflectionResult::class.java.declaredFields.map { it.name }
            ).map { it.lowercase() }

        listOf(
            "authority", "principal", "scope", "capability", "token", "permission",
            "adoptedstrategy", "strategyapplication"
        ).forEach { forbidden ->
            assertTrue(fieldNames.none { forbidden in it })
        }
    }

    @Test
    fun finding_provenance_order_is_canonical_to_prevent_identity_aliasing() {
        val secondEvidence = evidence("observation", "obs-2")
        val firstOutcome = ReflectionOutcomeReference(
            OutcomeEvaluationId("outcome-evaluation-" + "a".repeat(64)),
            OutcomeEvaluationVersion(1)
        )
        val secondOutcome = ReflectionOutcomeReference(
            OutcomeEvaluationId("outcome-evaluation-" + "b".repeat(64)),
            OutcomeEvaluationVersion(1)
        )

        assertFailsWith<IllegalArgumentException> {
            ReflectionFinding(
                ReflectionFindingKind.EXPLANATION_CANDIDATE,
                ReflectionFindingText("non canonical evidence"),
                evidence = listOf(secondEvidence, raw)
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ReflectionFinding(
                ReflectionFindingKind.EXPLANATION_CANDIDATE,
                ReflectionFindingText("non canonical outcomes"),
                outcomes = listOf(secondOutcome, firstOutcome)
            )
        }
    }

    @Test
    fun finding_must_retain_explicit_provenance() {
        assertFailsWith<IllegalArgumentException> {
            ReflectionFinding(
                ReflectionFindingKind.EXPLANATION_CANDIDATE,
                ReflectionFindingText("unanchored narrative")
            )
        }
    }

    private fun request(
        evidence: List<RawEvidenceReference> = listOf(raw),
        outcomes: List<ReflectionOutcomeReference> = listOf(outcomeRef),
        budget: ReflectionWorkBudget = ReflectionWorkBudget(4, 4096),
        policyVersion: ReflectionPolicyVersion = ReflectionPolicyVersion(1)
    ) = ReflectionRequest.create(
        version = ReflectionVersion(1),
        evidence = evidence,
        outcomes = outcomes,
        budget = budget,
        policyId = ReflectionPolicyId("bounded-reflection-v1"),
        policyVersion = policyVersion,
        requestedAt = t0
    )

    private fun engine(
        repository: OutcomeEvaluationRepository = FakeOutcomeRepository(outcome),
        resolver: RawEvidenceReferenceResolver =
            RawEvidenceReferenceResolver { reference ->
                RawEvidenceResolutionResult.Resolved(
                    ReflectionEvidenceMaterial(reference, ReflectionEvidenceText("bounded evidence"))
                )
            },
        analyzer: ReflectionAnalyzer = ReflectionAnalyzer {
            listOf(
                ReflectionFinding(
                    ReflectionFindingKind.EXPLANATION_CANDIDATE,
                    ReflectionFindingText("bounded candidate"),
                    evidence = listOf(raw),
                    outcomes = listOf(outcomeRef)
                )
            )
        }
    ) = BoundedReflectionEngine(repository, resolver, analyzer)

    private fun outcomeRecord() = OutcomeEvaluationRecord.create(
        version = OutcomeEvaluationVersion(1),
        plan = OutcomePlanReference(PlanningProposalId("plan-1"), PlanningGeneration(1)),
        decision = OutcomeDecisionReference(DecisionId("decision-1"), DecisionGeneration(1)),
        expected = OutcomeText("expected"),
        observed = OutcomeText("observed"),
        evidence = listOf(raw),
        validationReferences = listOf(OutcomeValidationReference("validation-1")),
        status = OutcomeEvaluationStatus.UNKNOWN,
        evaluatorPolicyId = EvaluatorPolicyId("evaluator-v1"),
        evaluatorPolicyVersion = EvaluatorPolicyVersion(1),
        evaluatedAt = t0.minusSeconds(1)
    )

    private fun evidence(namespace: String, id: String) =
        RawEvidenceReference(RawEvidenceNamespace(namespace), RawEvidenceId(id))

    private class FakeOutcomeRepository(
        private val record: OutcomeEvaluationRecord?
    ) : OutcomeEvaluationRepository {
        override fun store(record: OutcomeEvaluationRecord): OutcomeEvaluationStoreResult =
            error("not used")

        override fun lookup(id: OutcomeEvaluationId): OutcomeEvaluationLookupResult =
            if (record == null || record.id != id) OutcomeEvaluationLookupResult.Missing
            else OutcomeEvaluationLookupResult.Found(OutcomeEvaluationSnapshot(record, 1))

        override fun page(
            limit: Int,
            order: PersistentBackendPageOrder,
            cursorExclusive: PersistentBackendPageCursor?
        ): OutcomeEvaluationPageResult = error("not used")
    }
}
