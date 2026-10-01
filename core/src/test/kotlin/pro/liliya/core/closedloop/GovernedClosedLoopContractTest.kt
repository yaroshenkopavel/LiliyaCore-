package pro.liliya.core.closedloop

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.autonomy.AutonomyDeliberationGeneration
import pro.liliya.core.autonomy.AutonomyDeliberationRequestId
import pro.liliya.core.autonomy.AutonomyGeneration
import pro.liliya.core.autonomy.AutonomyProposalId
import pro.liliya.core.autonomy.ControlledAutonomyExecutionRequest
import pro.liliya.core.autonomy.ControlledAutonomyExecutionResult
import pro.liliya.core.authority.AuthorityPrincipal
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
import pro.liliya.core.execution.ExecutionActionId
import pro.liliya.core.orchestration.OrchestrationGeneration
import pro.liliya.core.orchestration.OrchestrationIntentId
import pro.liliya.core.persistence.PersistentBackendPageCursor
import pro.liliya.core.persistence.PersistentBackendPageOrder
import pro.liliya.core.planning.PlanningGeneration
import pro.liliya.core.planning.PlanningProposalId
import pro.liliya.core.reasoning.ReasoningArtifactId
import pro.liliya.core.reasoning.ReasoningGeneration
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
import pro.liliya.core.reflection.ReflectionResultId
import pro.liliya.core.reflection.ReflectionVersion
import pro.liliya.core.reflection.ReflectionWorkBudget
import pro.liliya.core.reliability.CapabilityDeclaration
import pro.liliya.core.reliability.CapabilityState
import pro.liliya.core.reliability.MetacognitiveReliabilityEngine
import pro.liliya.core.reliability.MetacognitiveReliabilityResult
import pro.liliya.core.reliability.OutcomeReliabilityReference
import pro.liliya.core.reliability.ReflectionReliabilityReference
import pro.liliya.core.reliability.ReliabilityAssessmentRequest
import pro.liliya.core.reliability.ReliabilityFreshnessPolicy
import pro.liliya.core.reliability.ReliabilityInputDomain
import pro.liliya.core.reliability.ReliabilityPolicyId
import pro.liliya.core.reliability.ReliabilityPolicyVersion
import pro.liliya.core.reliability.ReliabilityVersion
import pro.liliya.core.reliability.StrategyReliabilityReference
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

class GovernedClosedLoopContractTest {
    private val t0 = Instant.parse("2026-09-28T18:00:00Z")
    private val budget = ClosedLoopBudget(
        maxIterations = 4,
        maxEvidencePerIteration = 8,
        maxTotalEvidenceReferences = 24,
        maxElapsedSeconds = 600
    )
    private val origin = ClosedLoopOrigin(
        AutonomyDeliberationRequestId("deliberation-1"),
        AutonomyDeliberationGeneration(1)
    )
    private val loop = ClosedLoopDefinition.create(
        ClosedLoopVersion(1),
        origin,
        budget,
        t0
    )

    @Test
    fun loop_identity_is_stable_while_record_state_changes() {
        val fixture = fixture("one", t0.plusSeconds(1), loop, 1)
        val active = record(loop, listOf(fixture.iteration), ClosedLoopState.ACTIVE, null)
        val stopped = ClosedLoopRecord(
            loop = loop,
            iterations = listOf(fixture.iteration),
            state = ClosedLoopState.STOPPED,
            stopReason = ClosedLoopStopReason.MANUAL_STOP,
            updatedAt = fixture.iteration.completedAt
        )

        assertEquals(loop.id, active.loop.id)
        assertEquals(loop.id, stopped.loop.id)
        assertEquals(active.iterations.single().loopId, stopped.iterations.single().loopId)
    }

    @Test
    fun record_rejects_wrong_loop_noncontiguous_or_elapsed_budget() {
        val fixture = fixture("one", t0.plusSeconds(1), loop, 1)
        val other = ClosedLoopDefinition.create(
            ClosedLoopVersion(1),
            ClosedLoopOrigin(
                AutonomyDeliberationRequestId("other-deliberation"),
                AutonomyDeliberationGeneration(1)
            ),
            budget,
            t0
        )

        assertFailsWith<IllegalArgumentException> {
            ClosedLoopRecord(
                loop = other,
                iterations = listOf(fixture.iteration),
                state = ClosedLoopState.ACTIVE,
                stopReason = null,
                updatedAt = fixture.iteration.completedAt
            )
        }

        val secondOnly = fixture(
            "two",
            t0.plusSeconds(40),
            loop,
            2,
            previousIterationId = fixture.iteration.id
        )
        assertFailsWith<IllegalArgumentException> {
            record(loop, listOf(secondOnly.iteration), ClosedLoopState.ACTIVE, null)
        }

        assertFailsWith<IllegalArgumentException> {
            ClosedLoopRecord(
                loop = loop,
                iterations = listOf(fixture.iteration),
                state = ClosedLoopState.ACTIVE,
                stopReason = null,
                updatedAt = t0.plusSeconds(601)
            )
        }
    }

    @Test
    fun loop_lifecycle_can_exist_before_first_completed_iteration() {
        val active = ClosedLoopRecord(
            loop = loop,
            iterations = emptyList(),
            state = ClosedLoopState.ACTIVE,
            stopReason = null,
            updatedAt = t0
        )
        assertTrue(active.iterations.isEmpty())

        val rejectedBeforeExecution = ClosedLoopRecord(
            loop = loop,
            iterations = emptyList(),
            state = ClosedLoopState.STOPPED,
            stopReason = ClosedLoopStopReason.GOVERNANCE_REJECTED,
            updatedAt = t0.plusSeconds(1)
        )
        assertEquals(
            ClosedLoopStopReason.GOVERNANCE_REJECTED,
            rejectedBeforeExecution.stopReason
        )
    }

    @Test
    fun active_loop_is_rejected_when_hard_iteration_budget_is_exhausted() {
        val oneIterationBudget = ClosedLoopBudget(
            maxIterations = 1,
            maxEvidencePerIteration = 8,
            maxTotalEvidenceReferences = 24,
            maxElapsedSeconds = 600
        )
        val boundedLoop = ClosedLoopDefinition.create(
            ClosedLoopVersion(1),
            origin,
            oneIterationBudget,
            t0
        )
        val f = fixture("bounded", t0.plusSeconds(1), boundedLoop, 1)

        assertFailsWith<IllegalArgumentException> {
            record(boundedLoop, listOf(f.iteration), ClosedLoopState.ACTIVE, null)
        }

        val stopped = record(
            boundedLoop,
            listOf(f.iteration),
            ClosedLoopState.STOPPED,
            ClosedLoopStopReason.BUDGET_EXHAUSTED
        )
        assertEquals(ClosedLoopStopReason.BUDGET_EXHAUSTED, stopped.stopReason)
    }

    @Test
    fun action_gateway_delegates_to_existing_controlled_execution_with_ephemeral_principal() {
        val f = fixture("gateway", t0.plusSeconds(1), loop, 1)
        var captured: ControlledAutonomyExecutionRequest? = null
        val gateway = GovernedClosedLoopActionGateway(
            ClosedLoopControlledExecutionPort { request ->
                captured = request
                ControlledAutonomyExecutionResult.Succeeded
            }
        )
        val principal = AuthorityPrincipal("closed-loop-test-principal")

        val result = assertIs<GovernedClosedLoopActionResult.Succeeded>(
            gateway.execute(f.iteration.action, principal)
        )

        assertEquals(f.iteration.action, result.action)
        assertEquals(principal, captured?.principal)
        assertEquals(f.iteration.action.actionId, captured?.actionId)
        assertEquals(
            f.iteration.action.deliberationRequestId,
            captured?.deliberationRequestId
        )
        assertEquals(
            f.iteration.action.orchestrationIntentId,
            captured?.orchestrationIntentId
        )
    }

    @Test
    fun exact_action_to_t_u_v_w_chain_validates() {
        val f = fixture("one", t0.plusSeconds(1), loop, 1)
        val validator = validator(listOf(f)) { ClosedLoopActionVerificationResult.Verified }

        assertIs<GovernedClosedLoopValidationResult.Valid>(
            validator.validate(record(loop, listOf(f.iteration), ClosedLoopState.ACTIVE, null))
        )
    }

    @Test
    fun predecessor_chain_must_reference_exact_previous_iteration() {
        val one = fixture("predecessor-one", t0.plusSeconds(1), loop, 1)
        val two = fixture(
            "predecessor-two",
            t0.plusSeconds(40),
            loop,
            2,
            previousIterationId = ClosedLoopIterationId("foreign-previous")
        )

        assertFailsWith<IllegalArgumentException> {
            record(
                loop,
                listOf(one.iteration, two.iteration),
                ClosedLoopState.ACTIVE,
                null
            )
        }
    }

    @Test
    fun every_iteration_requires_fresh_controlled_action_verification() {
        val one = fixture("one", t0.plusSeconds(1), loop, 1)
        val two = fixture(
            "two",
            t0.plusSeconds(40),
            loop,
            2,
            previousIterationId = one.iteration.id
        )
        var calls = 0
        val validator = validator(listOf(one, two)) { action ->
            calls += 1
            if (action.attemptNumber == 2) {
                ClosedLoopActionVerificationResult.Rejected("fresh Authority rejected second attempt")
            } else {
                ClosedLoopActionVerificationResult.Verified
            }
        }

        val result = assertIs<GovernedClosedLoopValidationResult.Rejected>(
            validator.validate(record(loop, listOf(one.iteration, two.iteration), ClosedLoopState.ACTIVE, null))
        )
        assertEquals(2, calls)
        assertEquals(ClosedLoopIterationNumber(2), result.iteration)
        assertTrue("Authority rejected" in result.reason)
    }

    @Test
    fun outcome_action_mismatch_fails_closed() {
        val f = fixture(
            "mismatch",
            t0.plusSeconds(1),
            loop,
            1,
            outcomeActionId = ExecutionActionId("different-action")
        )

        val result = assertIs<GovernedClosedLoopValidationResult.Rejected>(
            validator(listOf(f)) { ClosedLoopActionVerificationResult.Verified }
                .validate(record(loop, listOf(f.iteration), ClosedLoopState.ACTIVE, null))
        )
        assertTrue("authorized action" in result.reason)
    }

    @Test
    fun reflection_with_unrelated_outcome_context_fails_closed() {
        val f = fixture(
            "extra-reflection-context",
            t0.plusSeconds(1),
            loop,
            1,
            extraReflectionOutcome = true
        )
        val result = assertIs<GovernedClosedLoopValidationResult.Rejected>(
            validator(listOf(f)) { ClosedLoopActionVerificationResult.Verified }
                .validate(record(loop, listOf(f.iteration), ClosedLoopState.ACTIVE, null))
        )
        assertTrue("reflection inputs" in result.reason)
    }

    @Test
    fun expired_reliability_at_iteration_completion_fails_closed() {
        val f = fixture(
            "expired",
            t0.plusSeconds(1),
            loop,
            1,
            reliabilityTtlSeconds = 1
        )
        val result = assertIs<GovernedClosedLoopValidationResult.Rejected>(
            validator(listOf(f)) { ClosedLoopActionVerificationResult.Verified }
                .validate(record(loop, listOf(f.iteration), ClosedLoopState.ACTIVE, null))
        )
        assertTrue("expired" in result.reason)
    }

    @Test
    fun stage_x_models_do_not_persist_authority_permission_or_principal_material() {
        val fields = listOf(
            ClosedLoopDefinition::class.java,
            ClosedLoopActionAttemptReference::class.java,
            ClosedLoopIteration::class.java,
            ClosedLoopRecord::class.java
        ).flatMap { type -> type.declaredFields.map { it.name.lowercase() } }

        listOf(
            "authority",
            "principal",
            "permission",
            "capabilitytoken",
            "authorization",
            "license"
        ).forEach { forbidden ->
            assertTrue(fields.none { forbidden in it })
        }
    }

    private fun record(
        loop: ClosedLoopDefinition,
        iterations: List<ClosedLoopIteration>,
        state: ClosedLoopState,
        stopReason: ClosedLoopStopReason?
    ) = ClosedLoopRecord(
        loop = loop,
        iterations = iterations,
        state = state,
        stopReason = stopReason,
        updatedAt = iterations.last().completedAt
    )

    private fun validator(
        fixtures: List<Fixture>,
        verify: (ClosedLoopActionAttemptReference) -> ClosedLoopActionVerificationResult
    ): GovernedClosedLoopValidator {
        val outcomeRepo = FakeOutcomeRepository(fixtures.associate { it.outcome.id to it.outcome })
        val reflectionRepo = FakeReflectionRepository(fixtures.associate { it.reflection.result.id to it.reflection })
        val strategyRepo = FakeStrategyRepository(fixtures.associate { it.strategy.candidate.id to it.strategy })
        val reliabilitySnapshots = fixtures.associate { it.iteration.reliability to it.reliability }

        return GovernedClosedLoopValidator(
            actionVerifier = ClosedLoopActionAttemptVerifier(verify),
            outcomes = outcomeRepo,
            reflections = reflectionRepo,
            strategies = strategyRepo,
            reliability = ClosedLoopReliabilityResolver { reliabilitySnapshots[it] }
        )
    }

    private fun fixture(
        suffix: String,
        start: Instant,
        loop: ClosedLoopDefinition,
        number: Int,
        outcomeActionId: ExecutionActionId? = null,
        reliabilityTtlSeconds: Long = 60,
        extraReflectionOutcome: Boolean = false,
        previousIterationId: ClosedLoopIterationId? = null
    ): Fixture {
        val evidence = listOf(
            RawEvidenceReference(
                RawEvidenceNamespace("observation"),
                RawEvidenceId("obs-$suffix")
            )
        )
        val action = ClosedLoopActionAttemptReference(
            deliberationRequestId = loop.origin.deliberationRequestId,
            deliberationGeneration = loop.origin.deliberationGeneration,
            autonomyProposalId = AutonomyProposalId("autonomy-$suffix"),
            autonomyGeneration = AutonomyGeneration(1),
            attemptNumber = number,
            planningProposalId = PlanningProposalId("plan-$suffix"),
            planningGeneration = PlanningGeneration(1),
            reasoningArtifactId = ReasoningArtifactId("reasoning-$suffix"),
            reasoningGeneration = ReasoningGeneration(1),
            decisionId = DecisionId("decision-$suffix"),
            decisionGeneration = DecisionGeneration(1),
            orchestrationIntentId = OrchestrationIntentId("orchestration-$suffix"),
            orchestrationGeneration = OrchestrationGeneration(1),
            actionId = ExecutionActionId("action-$suffix")
        )

        val outcome = OutcomeEvaluationRecord.create(
            version = OutcomeEvaluationVersion(1),
            plan = OutcomePlanReference(action.planningProposalId, action.planningGeneration),
            decision = OutcomeDecisionReference(action.decisionId, action.decisionGeneration),
            authorizedActionId = outcomeActionId ?: action.actionId,
            expected = OutcomeText("expected $suffix"),
            observed = OutcomeText("observed $suffix"),
            evidence = evidence,
            status = OutcomeEvaluationStatus.SUCCESS,
            evaluatorPolicyId = EvaluatorPolicyId("evaluator-v1"),
            evaluatorPolicyVersion = EvaluatorPolicyVersion(1),
            evaluatedAt = start.plusSeconds(10)
        )

        val reflectionRequest = ReflectionRequest.create(
            version = ReflectionVersion(1),
            evidence = evidence,
            outcomes = buildList {
                add(ReflectionOutcomeReference(outcome.id, outcome.version))
                if (extraReflectionOutcome) {
                    add(
                        ReflectionOutcomeReference(
                            OutcomeEvaluationId("outcome-evaluation-unrelated-$suffix"),
                            OutcomeEvaluationVersion(1)
                        )
                    )
                }
            },
            budget = ReflectionWorkBudget(4, 4096),
            policyId = ReflectionPolicyId("reflection-v1"),
            policyVersion = ReflectionPolicyVersion(1),
            requestedAt = start.plusSeconds(11)
        )
        val reflectionResult = BoundedReflectionResult.create(
            version = ReflectionVersion(1),
            requestId = reflectionRequest.id,
            findings = listOf(
                ReflectionFinding(
                    kind = ReflectionFindingKind.STRATEGY_CANDIDATE_INPUT,
                    text = ReflectionFindingText("strategy proposal $suffix"),
                    evidence = evidence
                )
            ),
            policyId = reflectionRequest.policyId,
            policyVersion = reflectionRequest.policyVersion,
            completedAt = start.plusSeconds(20)
        )
        val reflection = BoundedReflectionRecord(reflectionRequest, reflectionResult)

        val candidate = StrategyCandidate.create(
            version = StrategyVersion(1),
            source = StrategyReflectionSource(
                reflection.result.id,
                reflection.result.version,
                0,
                ReflectionFindingKind.STRATEGY_CANDIDATE_INPUT
            ),
            target = StrategyTarget.RETRIEVAL,
            scope = StrategyScope("closedloop.$suffix"),
            proposal = StrategyText("strategy proposal $suffix"),
            compatibility = listOf(
                StrategyCompatibilityConstraint("runtime", "offline")
            ),
            rollbackTo = null,
            createdAt = start.plusSeconds(21),
            expiresAt = start.plusSeconds(300)
        )
        val strategyRef = StrategyReference(candidate.id, candidate.version)
        val validation = StrategyValidationRecord.create(
            candidate = strategyRef,
            disposition = StrategyValidationDisposition.VALID,
            constraintResults = candidate.compatibility.map {
                StrategyConstraintResult(
                    it,
                    StrategyConstraintDisposition.SATISFIED,
                    "compatible"
                )
            },
            policyId = StrategyPolicyId("strategy-v1"),
            policyVersion = StrategyPolicyVersion(1),
            validatedAt = start.plusSeconds(22)
        )
        val adoption = StrategyAdoptionRecord.create(
            candidate = strategyRef,
            validation = StrategyValidationReference(validation.id, strategyRef),
            disposition = StrategyAdoptionDisposition.ADOPT,
            rationale = "accepted",
            decidedAt = start.plusSeconds(23)
        )
        val intent = StrategyApplicationIntent.create(
            candidate = strategyRef,
            adoption = StrategyAdoptionReference(adoption.id, strategyRef),
            target = candidate.target,
            scope = candidate.scope,
            createdAt = start.plusSeconds(24)
        )
        val strategy = StrategyAdaptationRecord(candidate, validation, adoption, intent)

        val outcomeRepo = FakeOutcomeRepository(mapOf(outcome.id to outcome))
        val reflectionRepo = FakeReflectionRepository(mapOf(reflection.result.id to reflection))
        val strategyRepo = FakeStrategyRepository(mapOf(strategy.candidate.id to strategy))
        val reliabilityRequest = ReliabilityAssessmentRequest.create(
            version = ReliabilityVersion(1),
            outcomes = listOf(OutcomeReliabilityReference(outcome.id, outcome.version)),
            reflections = listOf(
                ReflectionReliabilityReference(reflection.result.id, reflection.result.version)
            ),
            strategies = listOf(
                StrategyReliabilityReference(strategy.candidate.id, strategy.candidate.version)
            ),
            diagnostics = emptyList(),
            performanceLinks = emptyList(),
            capabilities = listOf(
                CapabilityDeclaration(
                    "closedloop.controlled-execution",
                    CapabilityState.SUPPORTED,
                    "verified through existing controlled boundary"
                )
            ),
            requiredDomains = listOf(
                ReliabilityInputDomain.OUTCOME_EVALUATION,
                ReliabilityInputDomain.REFLECTION,
                ReliabilityInputDomain.STRATEGY_ADAPTATION
            ),
            freshnessPolicy = ReliabilityFreshnessPolicy(120, reliabilityTtlSeconds),
            policyId = ReliabilityPolicyId("reliability-v1"),
            policyVersion = ReliabilityPolicyVersion(1),
            assessedAt = start.plusSeconds(30)
        )
        val reliabilityAssessment = assertIs<MetacognitiveReliabilityResult.Completed>(
            MetacognitiveReliabilityEngine(
                outcomeRepo,
                reflectionRepo,
                strategyRepo
            ).assess(reliabilityRequest, ReliabilityVersion(1))
        ).assessment
        val reliability = ClosedLoopReliabilitySnapshot(
            reliabilityRequest,
            reliabilityAssessment
        )

        val iteration = ClosedLoopIteration.create(
            loopId = loop.id,
            number = ClosedLoopIterationNumber(number),
            previousIterationId = previousIterationId,
            action = action,
            evidence = evidence,
            outcome = ClosedLoopOutcomeReference(outcome.id, outcome.version),
            reflection = ClosedLoopReflectionReference(
                reflection.result.id,
                reflection.result.version
            ),
            strategy = ClosedLoopStrategyReference(
                strategy.candidate.id,
                strategy.candidate.version
            ),
            reliability = ClosedLoopReliabilityReference(
                reliabilityAssessment.id,
                reliabilityAssessment.version
            ),
            startedAt = start,
            completedAt = start.plusSeconds(31)
        )

        return Fixture(iteration, outcome, reflection, strategy, reliability)
    }

    private data class Fixture(
        val iteration: ClosedLoopIteration,
        val outcome: OutcomeEvaluationRecord,
        val reflection: BoundedReflectionRecord,
        val strategy: StrategyAdaptationRecord,
        val reliability: ClosedLoopReliabilitySnapshot
    )

    private class FakeOutcomeRepository(
        private val records: Map<OutcomeEvaluationId, OutcomeEvaluationRecord>
    ) : OutcomeEvaluationRepository {
        override fun store(record: OutcomeEvaluationRecord): OutcomeEvaluationStoreResult =
            error("not used")

        override fun lookup(id: OutcomeEvaluationId): OutcomeEvaluationLookupResult =
            records[id]?.let {
                OutcomeEvaluationLookupResult.Found(OutcomeEvaluationSnapshot(it, 1))
            } ?: OutcomeEvaluationLookupResult.Missing

        override fun page(
            limit: Int,
            order: PersistentBackendPageOrder,
            cursorExclusive: PersistentBackendPageCursor?
        ): OutcomeEvaluationPageResult = error("not used")
    }

    private class FakeReflectionRepository(
        private val records: Map<ReflectionResultId, BoundedReflectionRecord>
    ) : BoundedReflectionRepository {
        override fun store(record: BoundedReflectionRecord): BoundedReflectionStoreResult =
            error("not used")

        override fun lookup(id: ReflectionResultId): BoundedReflectionLookupResult =
            records[id]?.let {
                BoundedReflectionLookupResult.Found(BoundedReflectionSnapshot(it, 1))
            } ?: BoundedReflectionLookupResult.Missing

        override fun page(
            limit: Int,
            order: PersistentBackendPageOrder,
            cursorExclusive: PersistentBackendPageCursor?
        ): BoundedReflectionPageResult = error("not used")
    }

    private class FakeStrategyRepository(
        private val records: Map<StrategyCandidateId, StrategyAdaptationRecord>
    ) : StrategyAdaptationRepository {
        override fun store(record: StrategyAdaptationRecord): StrategyAdaptationStoreResult =
            error("not used")

        override fun lookup(id: StrategyCandidateId): StrategyAdaptationLookupResult =
            records[id]?.let {
                StrategyAdaptationLookupResult.Found(StrategyAdaptationSnapshot(it, 1))
            } ?: StrategyAdaptationLookupResult.Missing

        override fun page(
            limit: Int,
            order: PersistentBackendPageOrder,
            cursorExclusive: PersistentBackendPageCursor?
        ): StrategyAdaptationPageResult = error("not used")
    }
}
