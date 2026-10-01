package pro.liliya.core.asf

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AgentCognitiveOrganizationContractTest {
    private val now = Instant.parse("2026-10-01T14:30:00Z")
    private val expires = Instant.parse("2026-10-01T14:45:00Z")
    private val rootTask = AgentRootTaskId("root-asf-m")
    private val scope = AgentCognitiveScope.create(
        listOf("analysis", "retrieval", "verification")
    )

    private val nanoRuntime = AgentWorkerRuntimeDescriptor(
        runtimeId = "rule-v1",
        kind = AgentWorkerRuntimeKind.DETERMINISTIC
    )
    private val microRuntime = AgentWorkerRuntimeDescriptor(
        runtimeId = "graph-v1",
        kind = AgentWorkerRuntimeKind.GRAPH_QUERY
    )
    private val fullRuntime = AgentWorkerRuntimeDescriptor(
        runtimeId = "onnx-v1",
        kind = AgentWorkerRuntimeKind.ONNX,
        modelId = "bounded-model-v1"
    )

    @Test
    fun admitted_composition_creates_one_bounded_ephemeral_organization() {
        val plan = plan()
        val organization = AgentCognitiveOrganization.fromComposition(
            plan = plan,
            createdAt = now,
            expiresAt = expires
        )

        assertEquals(rootTask, organization.rootTaskId)
        assertEquals(AgentCognitiveOrganizationState.ACTIVE, organization.state)
        assertEquals(3, organization.nodes.size)
        assertEquals(2, organization.edges.size)
        assertEquals(3, organization.metrics().activeWorkerCount)
        assertEquals(0, organization.metrics().terminalWorkerCount)
        assertTrue(organization.metrics().maxDepth <= organization.bounds.maxDepth)
        assertTrue(organization.metrics().clusterCount <= organization.bounds.maxClusters)
        assertTrue(
            organization.provenanceReferences.any {
                it == "composition-decision:" + plan.decisionId.value
            }
        )
    }

    @Test
    fun organization_identity_is_deterministic_for_same_root_and_composition() {
        val plan = plan()
        val first = AgentCognitiveOrganization.fromComposition(
            plan, now, expires
        )
        val second = AgentCognitiveOrganization.fromComposition(
            plan, now.plusSeconds(1), expires.plusSeconds(1)
        )

        assertEquals(first.id, second.id)
        assertEquals(
            first.nodes.map { it.id },
            second.nodes.map { it.id }
        )
    }

    @Test
    fun graph_bounds_fail_closed_during_creation() {
        val plan = plan()

        assertFailsWith<IllegalArgumentException> {
            AgentCognitiveOrganization.fromComposition(
                plan = plan,
                createdAt = now,
                expiresAt = expires,
                bounds = AgentCognitiveGraphBounds(
                    maxNodes = 2,
                    maxEdges = 8,
                    maxDepth = 8,
                    maxClusters = 8
                )
            )
        }
    }

    @Test
    fun terminal_leaf_contraction_reduces_coordination_surface() {
        val plan = plan()
        val initial = AgentCognitiveOrganization.fromComposition(
            plan, now, expires
        )
        val nanoStep = stepFor(plan, AgentWorkerClass.NANO)

        val contracted = initial
            .markWorkerTerminal(nanoStep.id)
            .contractTerminalBranch(nanoStep.id)

        val experiment = contracted.experimentAgainst(plan)

        assertEquals(3, experiment.baselineStaticWorkerCount)
        assertEquals(2, experiment.dynamicWorkerNodeCount)
        assertEquals(1, experiment.workerSurfaceReduction)
        assertTrue(experiment.dynamicNodeCount <= initial.nodes.size)
        assertTrue(experiment.dynamicEdgeCount < initial.edges.size)
        assertTrue(experiment.preservedProvenanceReferenceCount > 0)
    }

    @Test
    fun worker_with_live_dependents_cannot_be_contracted() {
        val plan = plan()
        val organization = AgentCognitiveOrganization.fromComposition(
            plan, now, expires
        )
        val rootStep = plan.coordinatorPlan.steps.first()

        val terminalRoot = organization.markWorkerTerminal(rootStep.id)

        assertFailsWith<IllegalArgumentException> {
            terminalRoot.contractTerminalBranch(rootStep.id)
        }
    }

    @Test
    fun externally_required_artifact_survives_worker_branch_contraction() {
        val plan = plan()
        val nanoStep = stepFor(plan, AgentWorkerClass.NANO)
        val artifact = AgentArtifact.create(
            producerId = AgentInstanceId("asf-m-producer"),
            producerGeneration = AgentInstanceGeneration(1),
            rootTaskId = rootTask,
            kind = "verification-evidence",
            payloadDigest = "sha256:verification-evidence",
            provenanceReferences = listOf("evidence:source"),
            createdAt = now.plusSeconds(10)
        )

        val withArtifact = AgentCognitiveOrganization.fromComposition(
            plan, now, expires
        ).addArtifact(
            producerStepId = nanoStep.id,
            artifact = artifact
        )
        val artifactNode = withArtifact.nodes.single {
            it.kind == AgentCognitiveGraphNodeKind.ARTIFACT &&
                it.reference == artifact.id.value
        }
        val withGate = withArtifact.addVerificationGate(
            gateReference = "gate:final-verification",
            dependsOn = listOf(artifactNode.id)
        )

        val contracted = withGate
            .markWorkerTerminal(nanoStep.id)
            .contractTerminalBranch(nanoStep.id)

        assertFalse(
            contracted.nodes.any {
                it.kind == AgentCognitiveGraphNodeKind.WORKER &&
                    it.reference == nanoStep.id.value
            }
        )
        assertTrue(
            contracted.nodes.any {
                it.kind == AgentCognitiveGraphNodeKind.ARTIFACT &&
                    it.reference == artifact.id.value
            }
        )
        assertTrue(
            contracted.edges.any {
                it.from == artifactNode.id &&
                    it.kind == AgentCognitiveGraphEdgeKind.VERIFIES
            }
        )
    }

    @Test
    fun artifact_from_different_root_task_is_rejected() {
        val plan = plan()
        val nanoStep = stepFor(plan, AgentWorkerClass.NANO)
        val foreign = AgentArtifact.create(
            producerId = AgentInstanceId("foreign-producer"),
            producerGeneration = AgentInstanceGeneration(1),
            rootTaskId = AgentRootTaskId("different-root"),
            kind = "foreign",
            payloadDigest = "sha256:foreign",
            provenanceReferences = listOf("evidence:foreign"),
            createdAt = now
        )

        assertFailsWith<IllegalArgumentException> {
            AgentCognitiveOrganization.fromComposition(
                plan, now, expires
            ).addArtifact(nanoStep.id, foreign)
        }
    }

    @Test
    fun graph_mutation_budget_fails_closed_when_exhausted() {
        val plan = plan()
        val nanoStep = stepFor(plan, AgentWorkerClass.NANO)
        val organization = AgentCognitiveOrganization.fromComposition(
            plan = plan,
            createdAt = now,
            expiresAt = expires,
            bounds = AgentCognitiveGraphBounds(
                maxNodes = 32,
                maxEdges = 64,
                maxDepth = 8,
                maxClusters = 8,
                maxMutations = 1
            )
        )

        val onceMutated = organization.markWorkerTerminal(nanoStep.id)

        assertEquals(1, onceMutated.metrics().mutationCount)
        assertFailsWith<IllegalArgumentException> {
            onceMutated.contractTerminalBranch(nanoStep.id)
        }
    }

    @Test
    fun terminal_collapse_remains_available_after_mutation_budget_is_exhausted() {
        val plan = plan()
        val nanoStep = stepFor(plan, AgentWorkerClass.NANO)
        val organization = AgentCognitiveOrganization.fromComposition(
            plan = plan,
            createdAt = now,
            expiresAt = expires,
            bounds = AgentCognitiveGraphBounds(
                maxNodes = 32,
                maxEdges = 64,
                maxDepth = 8,
                maxClusters = 8,
                maxMutations = 1
            )
        ).markWorkerTerminal(nanoStep.id)

        val collapsed = organization.collapse(
            terminalAt = now.plusSeconds(60),
            auditReferences = listOf("audit:forced-disposal")
        )

        assertEquals(AgentCognitiveOrganizationState.COLLAPSED, collapsed.state)
        assertTrue(collapsed.nodes.isEmpty())
        assertTrue(collapsed.edges.isEmpty())
        assertEquals(1, collapsed.metrics().mutationCount)
    }

    @Test
    fun recovery_is_paused_and_cannot_replay_or_mutate_automatically() {
        val active = AgentCognitiveOrganization.fromComposition(
            plan(), now, expires
        )
        val snapshot = active.snapshot()
        val recovered = AgentCognitiveOrganization.recoverPaused(
            snapshot = snapshot,
            recoveredAt = now.plusSeconds(30),
            recoveryReference = "checkpoint-1"
        )

        assertEquals(
            AgentCognitiveOrganizationState.RECOVERED_PAUSED,
            recovered.state
        )
        assertEquals(active.nodes, recovered.nodes)
        assertEquals(active.edges, recovered.edges)
        assertTrue("recovery:checkpoint-1" in recovered.provenanceReferences)

        assertFailsWith<IllegalArgumentException> {
            recovered.addVerificationGate(
                "gate:must-not-auto-replay",
                listOf(recovered.nodes.first().id)
            )
        }
    }

    @Test
    fun terminal_collapse_disposes_graph_and_preserves_only_audit_provenance() {
        val active = AgentCognitiveOrganization.fromComposition(
            plan(), now, expires
        )
        val collapsed = active.collapse(
            terminalAt = now.plusSeconds(60),
            auditReferences = listOf("audit:terminal-root")
        )

        assertEquals(
            AgentCognitiveOrganizationState.COLLAPSED,
            collapsed.state
        )
        assertTrue(collapsed.nodes.isEmpty())
        assertTrue(collapsed.edges.isEmpty())
        assertTrue("audit:terminal-root" in collapsed.provenanceReferences)
        assertEquals(0, collapsed.metrics().activeWorkerCount)
        assertEquals(0, collapsed.metrics().maxDepth)

        assertFailsWith<IllegalArgumentException> {
            collapsed.markWorkerTerminal(
                AgentCoordinatorStepId("team-nano")
            )
        }
    }

    @Test
    fun collapsed_organization_cannot_be_collapsed_twice() {
        val collapsed = AgentCognitiveOrganization.fromComposition(
            plan(), now, expires
        ).collapse(
            terminalAt = now.plusSeconds(60),
            auditReferences = listOf("audit:first-collapse")
        )

        assertFailsWith<IllegalArgumentException> {
            collapsed.collapse(
                terminalAt = now.plusSeconds(61),
                auditReferences = listOf("audit:second-collapse")
            )
        }
    }

    @Test
    fun organization_and_graph_contracts_do_not_carry_security_grants() {
        val forbidden = listOf(
            "authority", "permission", "executiongrant",
            "credential", "secret", "token", "license", "principal"
        )
        listOf(
            AgentCognitiveOrganization::class.java,
            AgentCognitiveGraphNode::class.java,
            AgentCognitiveGraphEdge::class.java,
            AgentCognitiveOrganizationSnapshot::class.java
        ).forEach { type ->
            val names = type.declaredFields.map { it.name.lowercase() }
            forbidden.forEach { word ->
                assertTrue(names.none { word in it })
            }
        }
    }

    @Test
    fun different_compositions_do_not_share_organization_identity() {
        val plan = plan()
        val organization = AgentCognitiveOrganization.fromComposition(
            plan, now, expires
        )

        val alternatePlan = plan(
            policyVersion = AgentTeamCompositionPolicyVersion(
                "asf-m-alternate-policy"
            )
        )
        val alternate = AgentCognitiveOrganization.fromComposition(
            alternatePlan, now, expires
        )

        assertNotEquals(organization.id, alternate.id)
    }

    private fun plan(
        policyVersion: AgentTeamCompositionPolicyVersion =
            AgentTeamCompositionPolicyVersion("asf-m-policy-v1")
    ): AgentTeamCompositionPlan {
        val fullBudget = AgentWorkBudget(
            maxWallClockMillis = 5_000,
            maxInferenceUnits = 2_000,
            maxContextBytes = 32_000,
            maxRetrievalItems = 8,
            maxArtifacts = 4,
            maxDescendants = 4
        )
        val microBudget = AgentWorkBudget(
            maxWallClockMillis = 2_000,
            maxInferenceUnits = 500,
            maxContextBytes = 16_000,
            maxRetrievalItems = 4,
            maxArtifacts = 2,
            maxDescendants = 1
        )
        val nanoBudget = AgentWorkBudget(
            maxWallClockMillis = 1_000,
            maxInferenceUnits = 100,
            maxContextBytes = 8_000,
            maxRetrievalItems = 1,
            maxArtifacts = 1,
            maxDescendants = 0
        )
        val aggregate = AgentAggregateBudget(
            maxWallClockMillis = 10_000,
            maxInferenceUnits = 5_000,
            maxContextBytes = 80_000,
            maxRetrievalItems = 20,
            maxArtifacts = 8,
            maxAgents = 4
        )
        val candidates = listOf(
            AgentTeamWorkerCandidate(
                workerClass = AgentWorkerClass.NANO,
                blueprint = blueprint("nano"),
                cognitiveScope = AgentCognitiveScope.create(
                    listOf("verification")
                ),
                budget = nanoBudget,
                runtime = nanoRuntime
            ),
            AgentTeamWorkerCandidate(
                workerClass = AgentWorkerClass.MICRO,
                blueprint = blueprint("micro"),
                cognitiveScope = AgentCognitiveScope.create(
                    listOf("retrieval", "verification")
                ),
                budget = microBudget,
                runtime = microRuntime
            ),
            AgentTeamWorkerCandidate(
                workerClass = AgentWorkerClass.FULL,
                blueprint = blueprint("full"),
                cognitiveScope = scope,
                budget = fullBudget,
                runtime = fullRuntime
            )
        )
        return (
            AgentTeamComposer.compose(
                AgentTeamCompositionRequest.create(
                    rootTaskId = rootTask,
                    policyVersion = policyVersion,
                    taskShape = AgentTeamTaskShape.workers(
                        listOf(
                            AgentWorkerRequirement.ATOMIC_VALIDATION,
                            AgentWorkerRequirement.NARROW_MULTI_STEP,
                            AgentWorkerRequirement.BROAD_SPECIALIST
                        )
                    ),
                    aggregateBudget = aggregate,
                    inputReferences = listOf("evidence:root"),
                    candidates = candidates
                )
            ) as AgentTeamCompositionDecision.Composed
            ).plan
    }

    private fun blueprint(role: String) = AgentBlueprint.create(
        AgentBlueprintVersion(1),
        role,
        "ephemeral-organization",
        scope
    ).let { AgentBlueprintReference(it.id, it.version) }

    private fun stepFor(
        plan: AgentTeamCompositionPlan,
        workerClass: AgentWorkerClass
    ): AgentCoordinatorStep =
        plan.coordinatorPlan.steps.single {
            it.workerClass == workerClass
        }
}
