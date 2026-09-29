package pro.liliya.core.asf

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentReviewPatternTest {
    private val now = Instant.parse("2026-09-29T15:00:00Z")
    private val expires = Instant.parse("2026-09-29T15:10:00Z")
    private val root = AgentRootTaskId("root-review")
    private val broadScope = AgentCognitiveScope.create(listOf("analysis", "verification"))
    private val reviewScope = AgentCognitiveScope.create(listOf("verification"))
    private val primaryBudget = AgentWorkBudget(
        maxWallClockMillis = 20_000,
        maxInferenceUnits = 10_000,
        maxContextBytes = 64_000,
        maxRetrievalItems = 8,
        maxArtifacts = 2,
        maxDescendants = 1
    )
    private val reviewerBudget = AgentWorkBudget(
        maxWallClockMillis = 10_000,
        maxInferenceUnits = 5_000,
        maxContextBytes = 32_000,
        maxRetrievalItems = 4,
        maxArtifacts = 1,
        maxDescendants = 0
    )
    private val researcher = AgentBlueprint.create(
        AgentBlueprintVersion(1),
        "researcher",
        "evidence-analysis",
        broadScope
    )
    private val verifier = AgentBlueprint.create(
        AgentBlueprintVersion(1),
        "verifier",
        "verification",
        reviewScope
    )

    @Test
    fun researcher_verifier_pattern_binds_review_to_primary_artifact() {
        val pattern = AgentReviewPatterns.researcherVerifier(
            rootTaskId = root,
            researcherBlueprint = reference(researcher),
            verifierBlueprint = reference(verifier),
            researcherScope = broadScope,
            verifierScope = reviewScope,
            researcherBudget = primaryBudget,
            verifierBudget = reviewerBudget,
            researchInputs = listOf("evidence:source"),
            verifierContextInputs = listOf("policy:verification")
        )

        assertEquals(AgentReviewPatternKind.RESEARCH_VERIFY, pattern.kind)
        assertEquals(AgentReviewKind.VERIFICATION, pattern.reviewKind)

        val reviewerStep = pattern.coordinatorPlan.step(pattern.reviewerStepId)!!
        assertEquals(pattern.primaryStepId, reviewerStep.parentStepId)
        assertTrue(reviewerStep.includeParentArtifact)

        val seenInputs = mutableListOf<List<String>>()
        val coordinator = coordinator(
            listOf(researcher, verifier),
            AgentRuntimeAdapter { context ->
                seenInputs += context.workspace.inputReferences
                AgentRuntimeOutcome.Completed(
                    kind = if (seenInputs.size == 1) "research-report" else "verification-report",
                    payloadDigest = "sha256:result-${seenInputs.size}",
                    provenanceReferences = context.workspace.inputReferences,
                    usage = AgentRuntimeUsage(10, 10, 100, 0, 1)
                )
            }
        )

        val result = coordinator.runSequential(
            pattern.coordinatorPlan,
            AgentCoordinatorRunWindow(now, expires)
        )

        assertEquals(AgentCoordinatorTerminalState.COMPLETED, result.state)
        assertEquals(2, result.artifacts.size)

        val primaryArtifact = result.artifacts.first()
        val reviewArtifact = result.artifacts.last()
        val primaryArtifactReference = "asf-artifact:${primaryArtifact.id.value}"

        assertEquals(
            listOf(primaryArtifactReference, "policy:verification"),
            seenInputs[1]
        )
        assertTrue(primaryArtifactReference in reviewArtifact.provenanceReferences)

        val contribution = AgentReviewContribution.create(
            AgentReviewKind.VERIFICATION,
            reviewArtifact,
            listOf(
                AgentReviewFinding.create(
                    AgentClaimKey("claim:primary-output"),
                    AgentFindingDisposition.SUPPORTS,
                    listOf(primaryArtifactReference)
                )
            )
        )
        val synthesis = AgentSynthesisGate().synthesize(listOf(contribution))

        assertEquals(AgentSynthesisFindingState.SUPPORTED, synthesis.findings.single().state)
        assertEquals(reviewArtifact.id, synthesis.findings.single().contributorArtifactIds.single())
        assertEquals(listOf(primaryArtifactReference), synthesis.findings.single().evidenceReferences)

        val evaluation = AgentPatternEvaluationHooks.measure(
            pattern,
            primaryArtifact,
            contribution,
            synthesis
        )
        assertEquals(1, evaluation.totalFindings)
        assertEquals(1, evaluation.supportedFindings)
        assertEquals(0, evaluation.challengedFindings)
        assertTrue(!evaluation.reviewDetectedRiskOrUncertainty)
    }

    @Test
    fun planner_critic_pattern_uses_critique_role() {
        val pattern = AgentReviewPatterns.plannerCritic(
            rootTaskId = root,
            plannerBlueprint = reference(researcher),
            criticBlueprint = reference(verifier),
            plannerScope = broadScope,
            criticScope = reviewScope,
            plannerBudget = primaryBudget,
            criticBudget = reviewerBudget,
            planningInputs = listOf("task:plan"),
            criticContextInputs = listOf("constraint:a")
        )

        assertEquals(AgentReviewPatternKind.PLAN_CRITIQUE, pattern.kind)
        assertEquals(AgentReviewKind.CRITIQUE, pattern.reviewKind)
        assertTrue(pattern.coordinatorPlan.step(pattern.reviewerStepId)!!.includeParentArtifact)

        val evaluation = assertPatternRunsEndToEnd(pattern)
        assertEquals(1, evaluation.challengedFindings)
        assertTrue(evaluation.reviewDetectedRiskOrUncertainty)
    }

    @Test
    fun proposer_adversarial_pattern_uses_adversarial_review_role() {
        val pattern = AgentReviewPatterns.proposerAdversarialReviewer(
            rootTaskId = root,
            proposerBlueprint = reference(researcher),
            reviewerBlueprint = reference(verifier),
            proposerScope = broadScope,
            reviewerScope = reviewScope,
            proposerBudget = primaryBudget,
            reviewerBudget = reviewerBudget,
            proposalInputs = listOf("task:proposal"),
            reviewerContextInputs = listOf("constraint:a")
        )

        assertEquals(AgentReviewPatternKind.PROPOSE_ADVERSARIAL_REVIEW, pattern.kind)
        assertEquals(AgentReviewKind.ADVERSARIAL_REVIEW, pattern.reviewKind)

        val evaluation = assertPatternRunsEndToEnd(pattern)
        assertEquals(1, evaluation.challengedFindings)
        assertTrue(evaluation.reviewDetectedRiskOrUncertainty)
    }

    @Test
    fun review_pattern_rejects_scope_widening() {
        val narrowPrimary = AgentCognitiveScope.create(listOf("analysis"))
        val widenedReviewer = AgentCognitiveScope.create(listOf("analysis", "verification"))

        assertFailsWith<IllegalArgumentException> {
            AgentReviewPatterns.researcherVerifier(
                root,
                reference(researcher),
                reference(verifier),
                narrowPrimary,
                widenedReviewer,
                primaryBudget,
                reviewerBudget,
                listOf("evidence:a"),
                listOf("policy:a")
            )
        }
    }

    @Test
    fun review_pattern_rejects_budget_widening() {
        assertFailsWith<IllegalArgumentException> {
            AgentReviewPatterns.researcherVerifier(
                root,
                reference(researcher),
                reference(verifier),
                broadScope,
                reviewScope,
                primaryBudget,
                reviewerBudget.copy(maxInferenceUnits = primaryBudget.maxInferenceUnits + 1),
                listOf("evidence:a"),
                listOf("policy:a")
            )
        }
    }

    @Test
    fun review_pattern_requires_descendant_budget() {
        assertFailsWith<IllegalArgumentException> {
            AgentReviewPatterns.researcherVerifier(
                root,
                reference(researcher),
                reference(verifier),
                broadScope,
                reviewScope,
                primaryBudget.copy(maxDescendants = 0),
                reviewerBudget,
                listOf("evidence:a"),
                listOf("policy:a")
            )
        }
    }

    @Test
    fun root_step_cannot_request_parent_artifact() {
        assertFailsWith<IllegalArgumentException> {
            AgentCoordinatorStep.create(
                id = AgentCoordinatorStepId("root"),
                parentStepId = null,
                blueprint = reference(researcher),
                cognitiveScope = broadScope,
                budget = primaryBudget,
                inputReferences = listOf("evidence:a"),
                includeParentArtifact = true
            )
        }
    }

    @Test
    fun evaluation_rejects_review_without_primary_artifact_provenance() {
        val pattern = AgentReviewPatterns.researcherVerifier(
            root,
            reference(researcher),
            reference(verifier),
            broadScope,
            reviewScope,
            primaryBudget,
            reviewerBudget,
            listOf("evidence:a"),
            listOf("policy:a")
        )
        val primary = AgentArtifact.create(
            AgentInstanceId("producer-primary"),
            AgentInstanceGeneration(1),
            root,
            "research-report",
            "sha256:primary",
            listOf("evidence:a"),
            now
        )
        val review = AgentArtifact.create(
            AgentInstanceId("producer-review"),
            AgentInstanceGeneration(1),
            root,
            "verification-report",
            "sha256:review",
            listOf("policy:a"),
            now
        )
        val contribution = AgentReviewContribution.create(
            AgentReviewKind.VERIFICATION,
            review,
            listOf(
                AgentReviewFinding.create(
                    AgentClaimKey("claim:a"),
                    AgentFindingDisposition.INCONCLUSIVE,
                    listOf("policy:a")
                )
            )
        )
        val synthesis = AgentSynthesisGate().synthesize(listOf(contribution))

        assertFailsWith<IllegalArgumentException> {
            AgentPatternEvaluationHooks.measure(pattern, primary, contribution, synthesis)
        }
    }

    @Test
    fun review_pattern_contracts_contain_no_authority_execution_or_secret_fields() {
        val forbidden = listOf(
            "authority", "permission", "credential", "secret",
            "token", "license", "executiongrant", "principal"
        )
        listOf(
            AgentReviewPatternPlan::class.java,
            AgentPatternEvaluation::class.java,
            AgentCoordinatorStep::class.java
        ).forEach { type ->
            val fields = type.declaredFields.map { it.name.lowercase() }
            forbidden.forEach { word ->
                assertTrue(fields.none { word in it }, "${type.simpleName} contains forbidden field: $word")
            }
        }
    }

    private fun assertPatternRunsEndToEnd(
        pattern: AgentReviewPatternPlan
    ): AgentPatternEvaluation {
        val seenInputs = mutableListOf<List<String>>()
        val coordinator = coordinator(
            listOf(researcher, verifier),
            AgentRuntimeAdapter { context ->
                seenInputs += context.workspace.inputReferences
                AgentRuntimeOutcome.Completed(
                    kind = if (seenInputs.size == 1) "primary-artifact" else "review-artifact",
                    payloadDigest = "sha256:pattern-${seenInputs.size}",
                    provenanceReferences = context.workspace.inputReferences,
                    usage = AgentRuntimeUsage(10, 10, 100, 0, 1)
                )
            }
        )
        val result = coordinator.runSequential(
            pattern.coordinatorPlan,
            AgentCoordinatorRunWindow(now, expires)
        )

        assertEquals(AgentCoordinatorTerminalState.COMPLETED, result.state)
        assertEquals(2, result.artifacts.size)

        val primary = result.artifacts.first()
        val review = result.artifacts.last()
        val primaryReference = "asf-artifact:${primary.id.value}"

        assertTrue(primaryReference in seenInputs[1])
        assertTrue(primaryReference in review.provenanceReferences)

        val contribution = AgentReviewContribution.create(
            pattern.reviewKind,
            review,
            listOf(
                AgentReviewFinding.create(
                    AgentClaimKey("claim:review-target"),
                    AgentFindingDisposition.CHALLENGES,
                    listOf(primaryReference)
                )
            )
        )
        val synthesis = AgentSynthesisGate().synthesize(listOf(contribution))
        return AgentPatternEvaluationHooks.measure(
            pattern,
            primary,
            contribution,
            synthesis
        )
    }

    private fun reference(blueprint: AgentBlueprint) =
        AgentBlueprintReference(blueprint.id, blueprint.version)

    private fun coordinator(
        blueprints: List<AgentBlueprint>,
        adapter: AgentRuntimeAdapter
    ): AgentCoordinator {
        val registry = AgentBlueprintRegistry(blueprints)
        val policy = AgentAdmissionPolicy(
            AgentFactoryBounds.PROTOTYPE,
            broadScope,
            AgentWorkBudget(
                maxWallClockMillis = 60_000,
                maxInferenceUnits = 40_000,
                maxContextBytes = 256_000,
                maxRetrievalItems = 32,
                maxArtifacts = 8,
                maxDescendants = 4
            )
        )
        val factory = AgentFactory(
            registry,
            policy,
            adapter,
            AgentAuditLedger { },
            timeSource = { now }
        )
        return AgentCoordinator(
            factory,
            AgentAggregateBudget(
                maxWallClockMillis = 60_000,
                maxInferenceUnits = 40_000,
                maxContextBytes = 256_000,
                maxRetrievalItems = 32,
                maxArtifacts = 8,
                maxAgents = 4
            )
        )
    }
}
