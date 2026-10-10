package pro.liliya.core.asf

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentCoordinatorTest {
    private val now = Instant.parse("2026-09-29T08:40:00Z")
    private val expires = Instant.parse("2026-09-29T08:50:00Z")
    private val root = AgentRootTaskId("root-c")
    private val researchScope = AgentCognitiveScope.create(listOf("research"))
    private val verifyScope = AgentCognitiveScope.create(listOf("verification"))
    private val parentBudget = AgentWorkBudget(
        maxWallClockMillis = 20_000,
        maxInferenceUnits = 10_000,
        maxContextBytes = 32_000,
        maxRetrievalItems = 8,
        maxArtifacts = 2,
        maxDescendants = 2
    )
    private val childBudget = AgentWorkBudget(
        maxWallClockMillis = 10_000,
        maxInferenceUnits = 5_000,
        maxContextBytes = 16_000,
        maxRetrievalItems = 4,
        maxArtifacts = 1,
        maxDescendants = 0
    )
    private val researcher = AgentBlueprint.create(
        AgentBlueprintVersion(1), "researcher", "evidence-analysis",
        AgentCognitiveScope.create(listOf("research", "verification"))
    )
    private val verifier = AgentBlueprint.create(
        AgentBlueprintVersion(1), "verifier", "verification",
        AgentCognitiveScope.create(listOf("verification"))
    )

    @Test
    fun parallel_preflight_reserves_budget_without_starting_workers() {
        var executions = 0
        val aggregate = AgentAggregateBudget(
            maxWallClockMillis = 60_000,
            maxInferenceUnits = 40_000,
            maxContextBytes = 256_000,
            maxRetrievalItems = 32,
            maxArtifacts = 8,
            maxAgents = 4
        )
        val coordinator = coordinator(aggregate, AgentRuntimeAdapter { context ->
            executions++
            AgentRuntimeOutcome.Completed(
                "report", "sha256:preflight", context.workspace.inputReferences,
                AgentRuntimeUsage(1, 1, 1, 0, 1)
            )
        })
        val expected = AgentParallelScheduler.schedule(plan(), aggregate)
        assertEquals(expected, coordinator.previewParallelSchedule(plan()))
        assertEquals(0, executions)
    }

    @Test
    fun parallel_preflight_rejects_over_budget_before_execution() {
        var executions = 0
        val aggregate = AgentAggregateBudget(
            maxWallClockMillis = 1_000,
            maxInferenceUnits = 1_000,
            maxContextBytes = 1_000,
            maxRetrievalItems = 0,
            maxArtifacts = 1,
            maxAgents = 1
        )
        val coordinator = coordinator(aggregate, AgentRuntimeAdapter { context ->
            executions++
            AgentRuntimeOutcome.Completed(
                "report", "sha256:preflight", context.workspace.inputReferences,
                AgentRuntimeUsage(1, 1, 1, 0, 1)
            )
        })
        val rejected = assertIs<AgentParallelScheduleResult.Rejected>(
            coordinator.previewParallelSchedule(plan())
        )
        assertEquals(AgentParallelScheduleRejection.PLAN_AGGREGATE_BUDGET_EXCEEDED, rejected.reason)
        assertEquals(0, executions)
    }

    @Test
    fun parallel_wave_preview_requires_complete_dependency_barrier() {
        val coordinator = coordinator(adapter = AgentRuntimeAdapter { error("must not run") })
        val fullPlan = plan()
        val rootId = AgentCoordinatorStepId("research")
        val childId = AgentCoordinatorStepId("verify")
        assertEquals(listOf(rootId), coordinator.previewNextParallelWave(fullPlan, emptySet())?.stepIds)
        assertEquals(listOf(childId), coordinator.previewNextParallelWave(fullPlan, setOf(rootId))?.stepIds)
        assertNull(coordinator.previewNextParallelWave(fullPlan, setOf(childId)))
        assertNull(coordinator.previewNextParallelWave(fullPlan, setOf(rootId, childId)))
        assertNull(coordinator.previewNextParallelWave(fullPlan, setOf(AgentCoordinatorStepId("unknown"))))
    }

    @Test
    fun parallel_preview_requires_all_siblings_before_any_descendant_wave() {
        val coordinator = coordinator(adapter = AgentRuntimeAdapter { error("preview must not run agents") })
        val rootId = AgentCoordinatorStepId("research")
        val childA = AgentCoordinatorStepId("a")
        val childB = AgentCoordinatorStepId("b")
        val grandchild = AgentCoordinatorStepId("grandchild")
        val rooted = parentBudget.copy(maxDescendants = 3)
        val childWithDescendant = childBudget.copy(maxDescendants = 1)
        val siblingPlan = AgentCoordinatorPlan(
            root,
            listOf(
                step("research", researcher, researcher.cognitiveScope, rooted, listOf("evidence:a")),
                step("a", verifier, verifyScope, childWithDescendant, listOf("evidence:b"), parent = "research"),
                step("b", verifier, verifyScope, childBudget, listOf("evidence:c"), parent = "research"),
                step("grandchild", verifier, verifyScope, childBudget, listOf("evidence:d"), parent = "a")
            )
        )
        // The plan exceeds the default coordinator's four-agent wall-clock reservation:
        // 20s + 10s + 10s + 10s <= 60s; the complete plan remains in budget.
        assertEquals(listOf(rootId), coordinator.previewNextParallelWave(siblingPlan, emptySet())?.stepIds)
        assertEquals(listOf(childA, childB), coordinator.previewNextParallelWave(siblingPlan, setOf(rootId))?.stepIds)
        assertNull(coordinator.previewNextParallelWave(siblingPlan, setOf(rootId, childA)))
        assertNull(coordinator.previewNextParallelWave(siblingPlan, setOf(rootId, childB)))
        assertEquals(
            listOf(grandchild),
            coordinator.previewNextParallelWave(siblingPlan, setOf(rootId, childA, childB))?.stepIds
        )
    }

    @Test
    fun parallel_wave_preview_rejects_over_budget_plan_without_dispatch() {
        val aggregate = AgentAggregateBudget(1_000, 1_000, 1_000, 0, 1, 1)
        val coordinator = coordinator(aggregate, AgentRuntimeAdapter { error("must not run") })
        assertNull(coordinator.previewNextParallelWave(plan(), emptySet()))
    }

    @Test
    fun coordinator_wave_checkpoint_requires_exact_successful_prior_wave() {
        var executions = 0
        val coordinator = coordinator(adapter = AgentRuntimeAdapter {
            executions++
            error("checkpoint must not execute agents")
        })
        val rootId = AgentCoordinatorStepId("research")
        val childId = AgentCoordinatorStepId("verify")
        val first = coordinator.previewAdvanceParallelWave(
            plan(), AgentParallelWaveProgress(),
            AgentParallelWaveExecutionResult(
                AgentParallelWaveExecutionState.COMPLETED,
                listOf(AgentParallelWaveTaskOutcome.Completed(rootId))
            )
        )
        val accepted = kotlin.test.assertNotNull(first)
        assertEquals(setOf(rootId), accepted.completedStepIds)
        assertEquals(1, accepted.nextWaveIndex)
        assertNull(coordinator.previewAdvanceParallelWave(
            plan(), AgentParallelWaveProgress(),
            AgentParallelWaveExecutionResult(
                AgentParallelWaveExecutionState.COMPLETED,
                listOf(AgentParallelWaveTaskOutcome.Completed(childId))
            )
        ))
        assertNull(coordinator.previewAdvanceParallelWave(
            plan(), accepted,
            AgentParallelWaveExecutionResult(
                AgentParallelWaveExecutionState.PARTIAL,
                listOf(AgentParallelWaveTaskOutcome.Completed(childId))
            )
        ))
        assertEquals(0, executions)
    }

    @Test
    fun coordinator_verified_wave_checkpoint_requires_exact_committed_steps() {
        var launches = 0
        val coordinator = coordinator(adapter = AgentRuntimeAdapter {
            launches++
            error("advisory checkpoint must not launch")
        })
        val rootId = AgentCoordinatorStepId("research")
        val result = AgentParallelWaveExecutionResult(
            AgentParallelWaveExecutionState.COMPLETED,
            listOf(AgentParallelWaveTaskOutcome.Completed(rootId))
        )
        assertNull(coordinator.previewAdvanceVerifiedParallelWave(
            plan(), AgentParallelWaveProgress(), result, emptySet()
        ))
        assertNull(coordinator.previewAdvanceVerifiedParallelWave(
            plan(), AgentParallelWaveProgress(), result,
            setOf(rootId, AgentCoordinatorStepId("verify"))
        ))
        val progress = kotlin.test.assertNotNull(coordinator.previewAdvanceVerifiedParallelWave(
            plan(), AgentParallelWaveProgress(), result, setOf(rootId)
        ))
        assertEquals(1, progress.nextWaveIndex)
        assertEquals(0, launches)
    }

    @Test
    fun receipt_verifier_observes_stable_snapshot_after_caller_mutation() {
        val coordinator = coordinator(adapter = AgentRuntimeAdapter {
            error("preview must not dispatch")
        })
        val rootId = AgentCoordinatorStepId("research")
        val receipts = mutableMapOf(rootId to "artifact:research")
        val result = AgentParallelWaveExecutionResult(
            AgentParallelWaveExecutionState.COMPLETED,
            listOf(AgentParallelWaveTaskOutcome.Completed(rootId, "artifact:research"))
        )
        val accepted = coordinator.previewAdvanceReceiptCheckedParallelWave(
            plan(), AgentParallelWaveProgress(), result, receipts
        ) { stepId, reference ->
            receipts[stepId] = "artifact:tampered"
            reference == "artifact:research"
        }
        assertNull(accepted)
        assertEquals("artifact:tampered", receipts[rootId])
    }

    @Test
    fun receipt_checked_checkpoint_fails_closed_on_denial_or_verifier_exception() {
        val coordinator = coordinator(adapter = AgentRuntimeAdapter {
            error("receipt preview must not dispatch agents")
        })
        val rootId = AgentCoordinatorStepId("research")
        val result = AgentParallelWaveExecutionResult(
            AgentParallelWaveExecutionState.COMPLETED,
            listOf(AgentParallelWaveTaskOutcome.Completed(rootId, "artifact:research"))
        )
        val receipts = mapOf(rootId to "artifact:research")
        assertNull(coordinator.previewAdvanceReceiptCheckedParallelWave(
            plan(), AgentParallelWaveProgress(), result, receipts
        ) { _, _ -> false })
        assertNull(coordinator.previewAdvanceReceiptCheckedParallelWave(
            plan(), AgentParallelWaveProgress(), result, receipts
        ) { _, _ -> throw IllegalStateException("storage unavailable") })
        var verified = 0
        val progress = kotlin.test.assertNotNull(coordinator.previewAdvanceReceiptCheckedParallelWave(
            plan(), AgentParallelWaveProgress(), result, receipts
        ) { id, ref ->
            verified++
            id == rootId && ref == "artifact:research"
        })
        assertEquals(1, verified)
        assertEquals(1, progress.nextWaveIndex)
        assertNull(coordinator.previewAdvanceReceiptCheckedParallelWave(
            plan(), AgentParallelWaveProgress(), result, mapOf(rootId to "artifact:wrong")
        ) { _, _ -> error("mismatched receipt must not reach verifier") })
    }

    @Test
    fun coordinator_artifact_checkpoint_matches_receipts_without_dispatch() {
        var launches = 0
        val coordinator = coordinator(adapter = AgentRuntimeAdapter {
            launches++
            error("preview must not dispatch")
        })
        val rootId = AgentCoordinatorStepId("research")
        val result = AgentParallelWaveExecutionResult(
            AgentParallelWaveExecutionState.COMPLETED,
            listOf(AgentParallelWaveTaskOutcome.Completed(rootId, "artifact:research"))
        )
        assertNull(coordinator.previewAdvanceArtifactMatchedParallelWave(
            plan(), AgentParallelWaveProgress(), result, emptyMap()
        ))
        assertNull(coordinator.previewAdvanceArtifactMatchedParallelWave(
            plan(), AgentParallelWaveProgress(), result,
            mapOf(rootId to "artifact:wrong")
        ))
        val accepted = kotlin.test.assertNotNull(coordinator.previewAdvanceArtifactMatchedParallelWave(
            plan(), AgentParallelWaveProgress(), result,
            mapOf(rootId to "artifact:research")
        ))
        assertEquals(1, accepted.nextWaveIndex)
        val restricted = coordinator(
            AgentAggregateBudget(1_000, 1_000, 1_000, 0, 1, 1),
            AgentRuntimeAdapter { error("must not dispatch") }
        )
        assertNull(restricted.previewAdvanceArtifactMatchedParallelWave(
            plan(), AgentParallelWaveProgress(), result,
            mapOf(rootId to "artifact:research")
        ))
        assertEquals(0, launches)
    }

    @Test
    fun verified_progress_preview_rejects_forged_prefix_without_dispatch() {
        var launches = 0
        val coordinator = coordinator(adapter = AgentRuntimeAdapter {
            launches++
            error("preview must not dispatch")
        })
        val plan = plan()
        val rootId = AgentCoordinatorStepId("research")
        val childId = AgentCoordinatorStepId("verify")
        assertEquals(listOf(rootId),
            coordinator.previewNextVerifiedParallelWave(plan, AgentParallelWaveProgress())?.stepIds)
        assertNull(coordinator.previewNextVerifiedParallelWave(
            plan, AgentParallelWaveProgress(setOf(childId), 1)
        ))
        assertEquals(listOf(childId), coordinator.previewNextVerifiedParallelWave(
            plan, AgentParallelWaveProgress(setOf(rootId), 1)
        )?.stepIds)
        assertNull(coordinator.previewNextVerifiedParallelWave(
            plan, AgentParallelWaveProgress(setOf(rootId, childId), 2)
        ))
        assertEquals(0, launches)
    }

    @Test
    fun sequential_two_worker_chain_preserves_parent_child_provenance() {
        val seen = mutableListOf<AgentRuntimeContext>()
        val coordinator = coordinator(
            adapter = AgentRuntimeAdapter { context ->
                seen += context
                AgentRuntimeOutcome.Completed(
                    kind = if (seen.size == 1) "research-report" else "verification-report",
                    payloadDigest = "sha256:${seen.size}",
                    provenanceReferences = context.workspace.inputReferences,
                    usage = AgentRuntimeUsage(100, 100, 100, 0, 1)
                )
            }
        )

        val result = coordinator.runSequential(
            plan = plan(),
            runWindow = AgentCoordinatorRunWindow(now, expires)
        )

        assertEquals(AgentCoordinatorTerminalState.COMPLETED, result.state)
        assertEquals(2, result.completedSteps)
        assertEquals(2, result.terminalInstances.size)
        assertEquals(2, result.artifacts.size)
        assertEquals(2, result.aggregateUsage.agentsStarted)
        assertEquals(200, result.aggregateUsage.inferenceUnits)
        assertEquals(root, result.terminalInstances[0].provenance.rootTaskId)
        assertNull(result.terminalInstances[0].provenance.parentAgentId)
        assertEquals(0, result.terminalInstances[0].provenance.depth)
        assertEquals(result.terminalInstances[0].id, result.terminalInstances[1].provenance.parentAgentId)
        assertEquals(result.terminalInstances[0].generation, result.terminalInstances[1].provenance.parentGeneration)
        assertEquals(1, result.terminalInstances[1].provenance.depth)
        assertTrue(result.artifacts.all { it.rootTaskId == root })
    }

    @Test
    fun aggregate_budget_exhaustion_stops_before_next_worker() {
        var calls = 0
        val coordinator = coordinator(
            aggregate = AgentAggregateBudget(
                maxWallClockMillis = 30_000,
                maxInferenceUnits = 10_000,
                maxContextBytes = 64_000,
                maxRetrievalItems = 10,
                maxArtifacts = 4,
                maxAgents = 1
            ),
            adapter = AgentRuntimeAdapter { context ->
                calls++
                AgentRuntimeOutcome.Completed(
                    "report",
                    "sha256:$calls",
                    context.workspace.inputReferences,
                    AgentRuntimeUsage(10, 10, 10, 0, 1)
                )
            }
        )

        val result = coordinator.runSequential(plan(), AgentCoordinatorRunWindow(now, expires))
        assertEquals(AgentCoordinatorTerminalState.BUDGET_EXHAUSTED, result.state)
        assertEquals(1, calls)
        assertEquals(1, result.completedSteps)
        assertEquals(1, result.aggregateUsage.agentsStarted)
    }

    @Test
    fun compute_budget_overrun_stops_chain_and_does_not_start_second_worker() {
        var calls = 0
        val coordinator = coordinator(
            aggregate = AgentAggregateBudget(
                maxWallClockMillis = 30_000,
                maxInferenceUnits = 150,
                maxContextBytes = 64_000,
                maxRetrievalItems = 10,
                maxArtifacts = 4,
                maxAgents = 4
            ),
            adapter = AgentRuntimeAdapter { context ->
                calls++
                AgentRuntimeOutcome.Completed(
                    "report",
                    "sha256:$calls",
                    context.workspace.inputReferences,
                    AgentRuntimeUsage(10, 200, 10, 0, 1)
                )
            }
        )

        val result = coordinator.runSequential(plan(), AgentCoordinatorRunWindow(now, expires))
        assertEquals(AgentCoordinatorTerminalState.BUDGET_EXHAUSTED, result.state)
        assertEquals(1, calls)
        assertEquals(0, result.completedSteps)
    }

    @Test
    fun root_cancellation_prevents_any_admission() {
        var calls = 0
        val result = coordinator(
            adapter = AgentRuntimeAdapter {
                calls++
                error("must not run")
            }
        ).runSequential(
            plan(),
            AgentCoordinatorRunWindow(now, expires),
            cancelled = { true }
        )

        assertEquals(AgentCoordinatorTerminalState.CANCELLED, result.state)
        assertEquals(0, calls)
        assertEquals(0, result.aggregateUsage.agentsStarted)
        assertTrue(result.terminalInstances.isEmpty())
    }

    @Test
    fun cancellation_after_first_worker_stops_second_worker() {
        var calls = 0
        var cancelled = false
        val result = coordinator(
            adapter = AgentRuntimeAdapter { context ->
                calls++
                cancelled = true
                AgentRuntimeOutcome.Completed(
                    "research-report",
                    "sha256:first",
                    context.workspace.inputReferences,
                    AgentRuntimeUsage(10, 10, 10, 0, 1)
                )
            }
        ).runSequential(
            plan(),
            AgentCoordinatorRunWindow(now, expires),
            cancelled = { cancelled }
        )

        assertEquals(AgentCoordinatorTerminalState.CANCELLED, result.state)
        assertEquals(1, calls)
        assertEquals(AgentLifecycleState.CANCELLED, result.terminalInstances.single().lifecycle)
        assertEquals(0, result.completedSteps)
    }

    @Test
    fun failed_second_worker_returns_partial_without_retry_storm() {
        var calls = 0
        val result = coordinator(
            adapter = AgentRuntimeAdapter { context ->
                calls++
                if (calls == 1) {
                    AgentRuntimeOutcome.Completed(
                        "research-report",
                        "sha256:first",
                        context.workspace.inputReferences,
                        AgentRuntimeUsage(10, 10, 10, 0, 1)
                    )
                } else {
                    AgentRuntimeOutcome.Failed(
                        "verification failed",
                        AgentRuntimeUsage(10, 10, 10, 0, 0)
                    )
                }
            }
        ).runSequential(plan(), AgentCoordinatorRunWindow(now, expires))

        assertEquals(AgentCoordinatorTerminalState.PARTIAL, result.state)
        assertEquals(2, calls)
        assertEquals(1, result.completedSteps)
        assertEquals(2, result.terminalInstances.size)
        assertEquals(1, result.artifacts.size)
        assertEquals(AgentLifecycleState.FAILED, result.terminalInstances.last().lifecycle)
    }

    @Test
    fun child_is_rejected_when_parent_descendant_budget_is_zero() {
        var calls = 0
        val noChildren = parentBudget.copy(maxDescendants = 0)
        val result = coordinator(
            adapter = AgentRuntimeAdapter { context ->
                calls++
                AgentRuntimeOutcome.Completed(
                    "report",
                    "sha256:$calls",
                    context.workspace.inputReferences,
                    AgentRuntimeUsage(10, 10, 10, 0, 1)
                )
            }
        ).runSequential(
            AgentCoordinatorPlan(
                root,
                listOf(
                    step("a", researcher, researchScope, noChildren, listOf("evidence:a")),
                    step("b", verifier, verifyScope, childBudget, listOf("evidence:b"), parent = "a")
                )
            ),
            AgentCoordinatorRunWindow(now, expires)
        )

        assertEquals(AgentCoordinatorTerminalState.BUDGET_EXHAUSTED, result.state)
        assertEquals(1, calls)
        assertEquals(1, result.completedSteps)
    }

    @Test
    fun depth_overflow_fails_closed_without_running_overflow_worker() {
        var calls = 0
        val steps = (0..4).map { index ->
            step(
                "s$index",
                researcher,
                researchScope,
                parentBudget.copy(maxDescendants = 4 - index),
                listOf("evidence:$index"),
                parent = if (index == 0) null else "s${index - 1}"
            )
        }

        val result = coordinator(
            aggregate = AgentAggregateBudget(
                maxWallClockMillis = 60_000,
                maxInferenceUnits = 40_000,
                maxContextBytes = 256_000,
                maxRetrievalItems = 32,
                maxArtifacts = 8,
                maxAgents = 8
            ),
            adapter = AgentRuntimeAdapter { context ->
                calls++
                AgentRuntimeOutcome.Completed(
                    "report",
                    "sha256:$calls",
                    context.workspace.inputReferences,
                    AgentRuntimeUsage(10, 10, 10, 0, 1)
                )
            }
        ).runSequential(
            AgentCoordinatorPlan(root, steps),
            AgentCoordinatorRunWindow(now, expires)
        )

        assertEquals(AgentCoordinatorTerminalState.PARTIAL, result.state)
        assertEquals(4, calls)
        assertEquals(4, result.completedSteps)
    }

    @Test
    fun direct_child_fanout_overflow_stops_fifth_child_before_runtime() {
        var calls = 0
        val rootBudget = parentBudget.copy(maxDescendants = 8)
        val steps = buildList {
            add(step("root", researcher, researcher.cognitiveScope, rootBudget, listOf("evidence:root")))
            (1..5).forEach { index ->
                add(
                    step(
                        "child-$index",
                        researcher,
                        researchScope,
                        childBudget,
                        listOf("evidence:child-$index"),
                        parent = "root"
                    )
                )
            }
        }

        val result = coordinator(
            aggregate = AgentAggregateBudget(
                maxWallClockMillis = 60_000,
                maxInferenceUnits = 40_000,
                maxContextBytes = 256_000,
                maxRetrievalItems = 32,
                maxArtifacts = 8,
                maxAgents = 8
            ),
            adapter = AgentRuntimeAdapter { context ->
                calls++
                AgentRuntimeOutcome.Completed(
                    "report",
                    "sha256:$calls",
                    context.workspace.inputReferences,
                    AgentRuntimeUsage(10, 10, 10, 0, 1)
                )
            }
        ).runSequential(
            AgentCoordinatorPlan(root, steps),
            AgentCoordinatorRunWindow(now, expires)
        )

        assertEquals(AgentCoordinatorTerminalState.PARTIAL, result.state)
        assertEquals(5, calls)
        assertEquals(5, result.completedSteps)
        assertEquals(5, result.terminalInstances.size)
        assertEquals(
            4,
            result.terminalInstances.drop(1).count {
                it.provenance.parentAgentId == result.terminalInstances.first().id
            }
        )
    }

    @Test
    fun plan_rejects_forward_parent_reference() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            AgentCoordinatorPlan(
                root,
                listOf(
                    step(
                        "root",
                        researcher,
                        researcher.cognitiveScope,
                        parentBudget,
                        listOf("evidence:root")
                    ),
                    step(
                        "child",
                        researcher,
                        researchScope,
                        childBudget,
                        listOf("evidence:child"),
                        parent = "future"
                    ),
                    step(
                        "future",
                        researcher,
                        researchScope,
                        childBudget,
                        listOf("evidence:future"),
                        parent = "root"
                    )
                )
            )
        }
    }

    @Test
    fun plan_rejects_more_than_root_population_bound() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            AgentCoordinatorPlan(
                root,
                buildList {
                    add(
                        step(
                            "root",
                            researcher,
                            researcher.cognitiveScope,
                            parentBudget.copy(maxDescendants = 12),
                            listOf("evidence:root")
                        )
                    )
                    (1..12).forEach { index ->
                        add(
                            step(
                                "child-$index",
                                researcher,
                                researchScope,
                                childBudget,
                                listOf("evidence:$index"),
                                parent = "root"
                            )
                        )
                    }
                }
            )
        }
    }

    @Test
    fun coordinator_contracts_contain_no_authority_execution_or_secret_fields() {
        val forbidden = listOf(
            "authority", "permission", "credential", "secret",
            "token", "license", "executiongrant", "principal"
        )
        listOf(
            AgentCoordinatorPlan::class.java,
            AgentCoordinatorStep::class.java,
            AgentAggregateBudget::class.java,
            AgentAggregateUsage::class.java,
            AgentCoordinatorResult::class.java
        ).forEach { type ->
            val fields = type.declaredFields.map { it.name.lowercase() }
            forbidden.forEach { word ->
                assertTrue(fields.none { word in it }, "${type.simpleName} contains forbidden field: $word")
            }
        }
    }

    private fun plan() = AgentCoordinatorPlan(
        root,
        listOf(
            step("research", researcher, researcher.cognitiveScope, parentBudget, listOf("evidence:a")),
            step("verify", verifier, verifyScope, childBudget, listOf("evidence:b"), parent = "research")
        )
    )

    private fun step(
        id: String,
        blueprint: AgentBlueprint,
        scope: AgentCognitiveScope,
        budget: AgentWorkBudget,
        inputs: Collection<String>,
        parent: String? = null
    ) = AgentCoordinatorStep.create(
        AgentCoordinatorStepId(id),
        parent?.let(::AgentCoordinatorStepId),
        AgentBlueprintReference(blueprint.id, blueprint.version),
        scope,
        budget,
        inputs
    )

    private fun coordinator(
        aggregate: AgentAggregateBudget = AgentAggregateBudget(
            maxWallClockMillis = 60_000,
            maxInferenceUnits = 40_000,
            maxContextBytes = 256_000,
            maxRetrievalItems = 32,
            maxArtifacts = 8,
            maxAgents = 4
        ),
        adapter: AgentRuntimeAdapter
    ): AgentCoordinator {
        val registry = AgentBlueprintRegistry(listOf(researcher, verifier))
        val policy = AgentAdmissionPolicy(
            AgentFactoryBounds.PROTOTYPE,
            AgentCognitiveScope.create(listOf("research", "verification")),
            AgentWorkBudget(
                maxWallClockMillis = 120_000,
                maxInferenceUnits = 100_000,
                maxContextBytes = 1_000_000,
                maxRetrievalItems = 128,
                maxArtifacts = 32,
                maxDescendants = 12
            )
        )
        val factory = AgentFactory(
            registry,
            policy,
            adapter,
            AgentAuditLedger { },
            timeSource = { now }
        )
        return AgentCoordinator(factory, aggregate)
    }
}
