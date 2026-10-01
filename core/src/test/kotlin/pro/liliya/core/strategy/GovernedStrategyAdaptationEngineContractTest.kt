package pro.liliya.core.strategy

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import pro.liliya.core.episodic.RawEvidenceId
import pro.liliya.core.episodic.RawEvidenceNamespace
import pro.liliya.core.episodic.RawEvidenceReference
import pro.liliya.core.evaluation.OutcomeEvaluationId
import pro.liliya.core.evaluation.OutcomeEvaluationVersion
import pro.liliya.core.persistence.PersistentBackendPageCursor
import pro.liliya.core.persistence.PersistentBackendPageOrder
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
import pro.liliya.core.reflection.ReflectionRequest
import pro.liliya.core.reflection.ReflectionVersion
import pro.liliya.core.reflection.ReflectionWorkBudget

class GovernedStrategyAdaptationEngineContractTest {
    private val t0 = Instant.parse("2026-09-28T14:00:00Z")
    private val reflection = reflectionRecord()
    private val candidate = candidate()

    @Test
    fun candidate_identity_is_deterministic_and_constraints_are_canonical() {
        val reversed = listOf(
            StrategyCompatibilityConstraint("runtime", "offline"),
            StrategyCompatibilityConstraint("abi", "arm64-v8a")
        )
        val a = candidate(compatibility = reversed)
        val b = candidate(compatibility = reversed.reversed())

        assertEquals(a, b)
        assertEquals(listOf("abi", "runtime"), a.compatibility.map { it.key })
    }

    @Test
    fun candidate_duplicate_constraints_fail_closed() {
        val constraint = StrategyCompatibilityConstraint("runtime", "offline")
        assertFailsWith<IllegalArgumentException> {
            candidate(compatibility = listOf(constraint, constraint))
        }
    }

    @Test
    fun validation_requires_exact_stage_u_source_and_strategy_finding() {
        val missing = engine(repository = FakeReflectionRepository(null))
        assertIs<StrategyValidationExecutionResult.SourceMissing>(
            missing.validate(candidate, policyId(), policyVersion(), t0.plusSeconds(2))
        )

        val wrongText = candidate(proposal = StrategyText("different proposal"))
        assertIs<StrategyValidationExecutionResult.SourceInvalid>(
            engine().validate(wrongText, policyId(), policyVersion(), t0.plusSeconds(2))
        )
    }

    @Test
    fun compatibility_evaluator_must_cover_exact_canonical_constraints() {
        val engine = engine(
            evaluator = StrategyCompatibilityEvaluator {
                listOf(
                    StrategyConstraintResult(
                        it.compatibility.last(),
                        StrategyConstraintDisposition.SATISFIED,
                        "ok"
                    )
                )
            }
        )

        assertIs<StrategyValidationExecutionResult.Rejected>(
            engine.validate(candidate, policyId(), policyVersion(), t0.plusSeconds(2))
        )
    }

    @Test
    fun valid_candidate_can_be_adopted_and_only_then_prepare_application_intent() {
        val engine = engine()
        val validation = assertIs<StrategyValidationExecutionResult.Validated>(
            engine.validate(candidate, policyId(), policyVersion(), t0.plusSeconds(2))
        ).record
        assertEquals(StrategyValidationDisposition.VALID, validation.disposition)

        val adoption = assertIs<StrategyAdoptionExecutionResult.Decided>(
            engine.decide(
                candidate,
                validation,
                StrategyAdoptionDisposition.ADOPT,
                "bounded improvement accepted",
                t0.plusSeconds(3)
            )
        ).record

        val intent = assertIs<StrategyApplicationIntentResult.Prepared>(
            engine.prepareApplicationIntent(
                candidate,
                validation,
                adoption,
                t0.plusSeconds(4)
            )
        ).intent

        val record = StrategyAdaptationRecord(candidate, validation, adoption, intent)
        assertEquals(candidate.id, record.applicationIntent?.candidate?.id)
    }

    @Test
    fun invalid_or_unknown_validation_cannot_be_adopted() {
        val invalidEngine = engine(
            evaluator = all(StrategyConstraintDisposition.UNSATISFIED, "mismatch")
        )
        val invalid = assertIs<StrategyValidationExecutionResult.Validated>(
            invalidEngine.validate(candidate, policyId(), policyVersion(), t0.plusSeconds(2))
        ).record
        assertEquals(StrategyValidationDisposition.INVALID, invalid.disposition)
        assertIs<StrategyAdoptionExecutionResult.Rejected>(
            invalidEngine.decide(
                candidate, invalid, StrategyAdoptionDisposition.ADOPT, "no", t0.plusSeconds(3)
            )
        )

        val unknownEngine = engine(
            evaluator = all(StrategyConstraintDisposition.UNKNOWN, "not proven")
        )
        val unknown = assertIs<StrategyValidationExecutionResult.Validated>(
            unknownEngine.validate(candidate, policyId(), policyVersion(), t0.plusSeconds(2))
        ).record
        assertEquals(StrategyValidationDisposition.UNKNOWN, unknown.disposition)
        assertIs<StrategyAdoptionExecutionResult.Rejected>(
            unknownEngine.decide(
                candidate, unknown, StrategyAdoptionDisposition.ADOPT, "no", t0.plusSeconds(3)
            )
        )
    }

    @Test
    fun expired_candidate_is_explicit_and_cannot_be_adopted() {
        val short = candidate(expiresAt = t0.plusSeconds(2))
        val engine = engine()
        val validation = assertIs<StrategyValidationExecutionResult.Validated>(
            engine.validate(short, policyId(), policyVersion(), t0.plusSeconds(2))
        ).record

        assertEquals(StrategyValidationDisposition.EXPIRED, validation.disposition)
        assertIs<StrategyAdoptionExecutionResult.Rejected>(
            engine.decide(
                short,
                validation,
                StrategyAdoptionDisposition.ADOPT,
                "expired",
                t0.plusSeconds(3)
            )
        )
    }

    @Test
    fun rejected_candidate_cannot_have_application_intent() {
        val engine = engine()
        val validation = assertIs<StrategyValidationExecutionResult.Validated>(
            engine.validate(candidate, policyId(), policyVersion(), t0.plusSeconds(2))
        ).record
        val rejection = assertIs<StrategyAdoptionExecutionResult.Decided>(
            engine.decide(
                candidate,
                validation,
                StrategyAdoptionDisposition.REJECT,
                "not adopted",
                t0.plusSeconds(3)
            )
        ).record

        assertIs<StrategyApplicationIntentResult.Rejected>(
            engine.prepareApplicationIntent(
                candidate,
                validation,
                rejection,
                t0.plusSeconds(4)
            )
        )
        val record = StrategyAdaptationRecord(candidate, validation, rejection, null)
        assertNull(record.applicationIntent)
    }

    @Test
    fun validation_record_semantics_fail_closed_before_adoption_or_persistence() {
        val ref = StrategyReference(candidate.id, candidate.version)
        assertFailsWith<IllegalArgumentException> {
            StrategyValidationRecord.create(
                candidate = ref,
                disposition = StrategyValidationDisposition.VALID,
                constraintResults = candidate.compatibility.map {
                    StrategyConstraintResult(
                        it,
                        StrategyConstraintDisposition.UNSATISFIED,
                        "not compatible"
                    )
                },
                policyId = policyId(),
                policyVersion = policyVersion(),
                validatedAt = t0.plusSeconds(2)
            )
        }
    }

    @Test
    fun rollback_target_must_exist_and_be_exact_adopted_compatible_state() {
        val previous = previousStrategyRecord()
        val rollback = StrategyReference(previous.candidate.id, previous.candidate.version)
        val candidateWithRollback = candidate(rollbackTo = rollback)

        assertIs<StrategyValidationExecutionResult.Rejected>(
            engine(strategies = FakeStrategyRepository(null)).validate(
                candidateWithRollback,
                policyId(),
                policyVersion(),
                t0.plusSeconds(2)
            )
        )

        val accepted = assertIs<StrategyValidationExecutionResult.Validated>(
            engine(strategies = FakeStrategyRepository(previous)).validate(
                candidateWithRollback,
                policyId(),
                policyVersion(),
                t0.plusSeconds(2)
            )
        ).record
        assertEquals(StrategyValidationDisposition.VALID, accepted.disposition)
    }

    @Test
    fun rollback_target_must_be_preexisting_and_unexpired() {
        val expiredPrevious = previousStrategyRecord(expiresAt = t0.plusSeconds(1))
        val expiredRollback = StrategyReference(
            expiredPrevious.candidate.id,
            expiredPrevious.candidate.version
        )
        assertIs<StrategyValidationExecutionResult.Rejected>(
            engine(strategies = FakeStrategyRepository(expiredPrevious)).validate(
                candidate(rollbackTo = expiredRollback),
                policyId(),
                policyVersion(),
                t0.plusSeconds(2)
            )
        )

        val futurePrevious = previousStrategyRecord(applicationAt = t0.plusSeconds(2))
        val futureRollback = StrategyReference(
            futurePrevious.candidate.id,
            futurePrevious.candidate.version
        )
        assertIs<StrategyValidationExecutionResult.Rejected>(
            engine(strategies = FakeStrategyRepository(futurePrevious)).validate(
                candidate(rollbackTo = futureRollback),
                policyId(),
                policyVersion(),
                t0.plusSeconds(2)
            )
        )
    }

    @Test
    fun stage_v_contract_contains_no_authority_execution_or_self_patch_material() {
        val fields = listOf(
            StrategyCandidate::class.java,
            StrategyValidationRecord::class.java,
            StrategyAdoptionRecord::class.java,
            StrategyApplicationIntent::class.java
        ).flatMap { type -> type.declaredFields.map { it.name.lowercase() } }

        listOf(
            "authority", "principal", "capability", "token", "permission",
            "execution", "selfpatch", "securitypolicy"
        ).forEach { forbidden ->
            assertTrue(fields.none { forbidden in it })
        }
    }

    private fun candidate(
        proposal: StrategyText = StrategyText("prefer validated reciprocal retrieval"),
        compatibility: List<StrategyCompatibilityConstraint> = listOf(
            StrategyCompatibilityConstraint("abi", "arm64-v8a"),
            StrategyCompatibilityConstraint("runtime", "offline")
        ),
        rollbackTo: StrategyReference? = null,
        expiresAt: Instant = t0.plusSeconds(300)
    ): StrategyCandidate = StrategyCandidate.create(
        version = StrategyVersion(1),
        source = StrategyReflectionSource(
            resultId = reflection.result.id,
            resultVersion = reflection.result.version,
            findingIndex = 0,
            findingKind = ReflectionFindingKind.STRATEGY_CANDIDATE_INPUT
        ),
        target = StrategyTarget.RETRIEVAL,
        scope = StrategyScope("semantic.retrieval.ranking"),
        proposal = proposal,
        compatibility = compatibility,
        rollbackTo = rollbackTo,
        createdAt = t0.plusSeconds(1),
        expiresAt = expiresAt
    )

    private fun engine(
        repository: BoundedReflectionRepository = FakeReflectionRepository(reflection),
        evaluator: StrategyCompatibilityEvaluator = all(
            StrategyConstraintDisposition.SATISFIED,
            "compatible"
        ),
        strategies: StrategyAdaptationRepository = FakeStrategyRepository(null)
    ) = GovernedStrategyAdaptationEngine(repository, evaluator, strategies)

    private fun all(
        disposition: StrategyConstraintDisposition,
        reason: String
    ) = StrategyCompatibilityEvaluator { candidate ->
        candidate.compatibility.map { StrategyConstraintResult(it, disposition, reason) }
    }

    private fun policyId() = StrategyPolicyId("strategy-validation-v1")
    private fun policyVersion() = StrategyPolicyVersion(1)

    private fun previousStrategyRecord(
        createdAt: Instant = t0.minusSeconds(30),
        expiresAt: Instant = t0.plusSeconds(600),
        applicationAt: Instant = t0.minusSeconds(5)
    ): StrategyAdaptationRecord {
        val previousCandidate = StrategyCandidate.create(
            version = StrategyVersion(7),
            source = StrategyReflectionSource(
                resultId = reflection.result.id,
                resultVersion = reflection.result.version,
                findingIndex = 0,
                findingKind = ReflectionFindingKind.STRATEGY_CANDIDATE_INPUT
            ),
            target = StrategyTarget.RETRIEVAL,
            scope = StrategyScope("semantic.retrieval.ranking"),
            proposal = StrategyText("prefer validated reciprocal retrieval"),
            compatibility = listOf(
                StrategyCompatibilityConstraint("abi", "arm64-v8a"),
                StrategyCompatibilityConstraint("runtime", "offline")
            ),
            rollbackTo = null,
            createdAt = createdAt,
            expiresAt = expiresAt
        )
        val ref = StrategyReference(previousCandidate.id, previousCandidate.version)
        val validation = StrategyValidationRecord.create(
            candidate = ref,
            disposition = StrategyValidationDisposition.VALID,
            constraintResults = previousCandidate.compatibility.map {
                StrategyConstraintResult(
                    it,
                    StrategyConstraintDisposition.SATISFIED,
                    "compatible"
                )
            },
            policyId = policyId(),
            policyVersion = policyVersion(),
            validatedAt = t0.minusSeconds(20)
        )
        val adoption = StrategyAdoptionRecord.create(
            candidate = ref,
            validation = StrategyValidationReference(validation.id, ref),
            disposition = StrategyAdoptionDisposition.ADOPT,
            rationale = "previous adopted strategy",
            decidedAt = t0.minusSeconds(10)
        )
        val intent = StrategyApplicationIntent.create(
            candidate = ref,
            adoption = StrategyAdoptionReference(adoption.id, ref),
            target = previousCandidate.target,
            scope = previousCandidate.scope,
            createdAt = applicationAt
        )
        return StrategyAdaptationRecord(previousCandidate, validation, adoption, intent)
    }

    private fun reflectionRecord(): BoundedReflectionRecord {
        val raw = RawEvidenceReference(
            RawEvidenceNamespace("observation"),
            RawEvidenceId("strategy-source")
        )
        val outcome = ReflectionOutcomeReference(
            OutcomeEvaluationId("outcome-evaluation-" + "a".repeat(64)),
            OutcomeEvaluationVersion(1)
        )
        val request = ReflectionRequest.create(
            version = ReflectionVersion(1),
            evidence = listOf(raw),
            outcomes = listOf(outcome),
            budget = ReflectionWorkBudget(2, 4096),
            policyId = ReflectionPolicyId("bounded-reflection-v1"),
            policyVersion = ReflectionPolicyVersion(1),
            requestedAt = t0
        )
        val result = BoundedReflectionResult.create(
            version = ReflectionVersion(1),
            requestId = request.id,
            findings = listOf(
                ReflectionFinding(
                    ReflectionFindingKind.STRATEGY_CANDIDATE_INPUT,
                    ReflectionFindingText("prefer validated reciprocal retrieval"),
                    evidence = listOf(raw),
                    outcomes = listOf(outcome)
                )
            ),
            policyId = request.policyId,
            policyVersion = request.policyVersion,
            completedAt = t0.plusSeconds(1)
        )
        return BoundedReflectionRecord(request, result)
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

    private class FakeReflectionRepository(
        private val record: BoundedReflectionRecord?
    ) : BoundedReflectionRepository {
        override fun store(record: BoundedReflectionRecord): BoundedReflectionStoreResult =
            error("not used")

        override fun lookup(id: pro.liliya.core.reflection.ReflectionResultId):
            BoundedReflectionLookupResult =
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
}
