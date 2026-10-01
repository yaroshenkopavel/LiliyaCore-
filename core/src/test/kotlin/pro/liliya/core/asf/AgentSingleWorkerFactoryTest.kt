package pro.liliya.core.asf

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentSingleWorkerFactoryTest {
    private val now = Instant.parse("2026-09-29T07:20:00Z")
    private val expires = Instant.parse("2026-09-29T07:25:00Z")
    private val scope = AgentCognitiveScope.create(listOf("research"))
    private val blueprint = AgentBlueprint.create(
        AgentBlueprintVersion(1),
        "researcher",
        "evidence-analysis",
        scope
    )
    private val budget = AgentWorkBudget(
        maxWallClockMillis = 30_000,
        maxInferenceUnits = 20_000,
        maxContextBytes = 128_000,
        maxRetrievalItems = 16,
        maxArtifacts = 4,
        maxDescendants = 0
    )

    @Test
    fun registry_requires_exact_version_and_rejects_duplicate_reference() {
        val registry = AgentBlueprintRegistry(listOf(blueprint))
        assertEquals(blueprint, registry.requireExact(AgentBlueprintReference(blueprint.id, blueprint.version)))
        assertNull(registry.resolve(AgentBlueprintReference(blueprint.id, AgentBlueprintVersion(2))))

        assertFailsWith<IllegalArgumentException> {
            AgentBlueprintRegistry(listOf(blueprint, blueprint))
        }
    }

    @Test
    fun single_worker_runs_to_typed_artifact_and_disposes_workspace() {
        val events = mutableListOf<AgentAuditEvent>()
        val factory = factory(
            events = events,
            adapter = AgentRuntimeAdapter { context ->
                assertEquals(AgentWorkspaceState.ACTIVE, context.workspace.state)
                assertEquals(listOf("evidence:a", "evidence:z"), context.workspace.inputReferences)
                AgentRuntimeOutcome.Completed(
                    kind = "research-report",
                    payloadDigest = "sha256:result",
                    provenanceReferences = context.workspace.inputReferences,
                    usage = AgentRuntimeUsage(100, 500, 1024, 2, 1)
                )
            }
        )

        val result = assertIs<AgentFactoryResult.Terminal>(
            factory.runSingle(
                request = request(),
                population = AgentPopulationSnapshot(0, 0, 0),
                generation = AgentInstanceGeneration(1),
                admittedAt = now,
                expiresAt = expires,
                inputReferences = listOf("evidence:z", "evidence:a")
            )
        )

        assertEquals(AgentLifecycleState.COMPLETED, result.instance.lifecycle)
        assertTrue(result.workspaceDisposed)
        assertNotNull(result.artifact)
        assertEquals("research-report", result.artifact.kind)
        assertEquals(listOf("evidence:a", "evidence:z"), result.artifact.provenanceReferences)
        assertEquals(
            listOf(
                AgentAuditEventKind.ADMITTED,
                AgentAuditEventKind.SPAWNED,
                AgentAuditEventKind.RUNNING,
                AgentAuditEventKind.COMPLETED,
                AgentAuditEventKind.WORKSPACE_DISPOSED
            ),
            events.map { it.kind }
        )
        val completedEvent = events.single { it.kind == AgentAuditEventKind.COMPLETED }
        assertEquals(result.artifact.id, completedEvent.artifactId)
        assertEquals(result.usage, completedEvent.usage)
    }

    @Test
    fun unknown_blueprint_fails_before_admission_or_runtime() {
        var runtimeCalls = 0
        val events = mutableListOf<AgentAuditEvent>()
        val factory = factory(events, AgentRuntimeAdapter {
            runtimeCalls++
            error("must not run")
        })
        val other = AgentBlueprint.create(
            AgentBlueprintVersion(1),
            "critic",
            "evidence-analysis",
            scope
        )
        val request = AgentSpawnRequest.create(
            AgentBlueprintReference(other.id, other.version),
            AgentSpawnProvenance(AgentRootTaskId("root-1"), null, null, 0),
            scope,
            budget
        )

        val rejected = assertIs<AgentFactoryResult.Rejected>(
            factory.runSingle(
                request,
                AgentPopulationSnapshot(0, 0, 0),
                AgentInstanceGeneration(1),
                now,
                expires,
                listOf("evidence:a")
            )
        )
        assertEquals(AgentAdmissionRejection.UNKNOWN_BLUEPRINT, rejected.reason)
        assertEquals(0, runtimeCalls)
        assertTrue(events.isEmpty())
    }

    @Test
    fun cancellation_and_expiry_stop_before_runtime() {
        var calls = 0
        val adapter = AgentRuntimeAdapter {
            calls++
            error("must not run")
        }

        val cancelled = assertIs<AgentFactoryResult.Terminal>(
            factory(mutableListOf(), adapter).runSingle(
                request(), AgentPopulationSnapshot(0, 0, 0), AgentInstanceGeneration(1),
                now, expires, listOf("evidence:a"), cancellationRequested = { true }
            )
        )
        assertEquals(AgentLifecycleState.CANCELLED, cancelled.instance.lifecycle)
        assertTrue(!cancelled.workspaceDisposed)

        val expired = assertIs<AgentFactoryResult.Terminal>(
            factory(mutableListOf(), adapter).runSingle(
                request(), AgentPopulationSnapshot(0, 0, 0), AgentInstanceGeneration(2),
                now, now, listOf("evidence:a")
            )
        )
        assertEquals(AgentLifecycleState.EXPIRED, expired.instance.lifecycle)
        assertTrue(!expired.workspaceDisposed)
        assertEquals(0, calls)
    }

    @Test
    fun cancellation_and_expiry_are_rechecked_after_runtime_returns() {
        var cancelled = false
        var clock = now

        val cancelledResult = assertIs<AgentFactoryResult.Terminal>(
            factory(
                mutableListOf(),
                AgentRuntimeAdapter {
                    cancelled = true
                    AgentRuntimeOutcome.Completed(
                        "research-report",
                        "sha256:cancelled",
                        listOf("evidence:a"),
                        AgentRuntimeUsage(100, 100, 100, 0, 1)
                    )
                },
                timeSource = { clock }
            ).runSingle(
                request(), AgentPopulationSnapshot(0, 0, 0), AgentInstanceGeneration(1),
                now, expires, listOf("evidence:a"),
                cancellationRequested = { cancelled }
            )
        )
        assertEquals(AgentLifecycleState.CANCELLED, cancelledResult.instance.lifecycle)
        assertNull(cancelledResult.artifact)

        clock = now
        val expiredResult = assertIs<AgentFactoryResult.Terminal>(
            factory(
                mutableListOf(),
                AgentRuntimeAdapter {
                    clock = expires
                    AgentRuntimeOutcome.Completed(
                        "research-report",
                        "sha256:expired",
                        listOf("evidence:a"),
                        AgentRuntimeUsage(100, 100, 100, 0, 1)
                    )
                },
                timeSource = { clock }
            ).runSingle(
                request(), AgentPopulationSnapshot(0, 0, 0), AgentInstanceGeneration(2),
                now, expires, listOf("evidence:a")
            )
        )
        assertEquals(AgentLifecycleState.EXPIRED, expiredResult.instance.lifecycle)
        assertNull(expiredResult.artifact)
    }

    @Test
    fun invalid_workspace_input_terminalizes_without_running_adapter() {
        var calls = 0
        val events = mutableListOf<AgentAuditEvent>()
        val result = assertIs<AgentFactoryResult.Terminal>(
            factory(
                events,
                AgentRuntimeAdapter {
                    calls++
                    error("must not run")
                }
            ).runSingle(
                request(),
                AgentPopulationSnapshot(0, 0, 0),
                AgentInstanceGeneration(1),
                now,
                expires,
                listOf("evidence:a", "evidence:a")
            )
        )

        assertEquals(0, calls)
        assertEquals(AgentLifecycleState.FAILED, result.instance.lifecycle)
        assertNull(result.artifact)
        assertTrue(!result.workspaceDisposed)
        assertEquals(
            listOf(
                AgentAuditEventKind.ADMITTED,
                AgentAuditEventKind.SPAWNED,
                AgentAuditEventKind.FAILED
            ),
            events.map { it.kind }
        )
    }

    @Test
    fun invalid_completed_artifact_is_quarantined_as_failed() {
        val events = mutableListOf<AgentAuditEvent>()
        val result = assertIs<AgentFactoryResult.Terminal>(
            factory(
                events,
                AgentRuntimeAdapter {
                    AgentRuntimeOutcome.Completed(
                        kind = "",
                        payloadDigest = "sha256:invalid",
                        provenanceReferences = listOf("evidence:a"),
                        usage = AgentRuntimeUsage(100, 100, 100, 0, 1)
                    )
                }
            ).runSingle(
                request(),
                AgentPopulationSnapshot(0, 0, 0),
                AgentInstanceGeneration(1),
                now,
                expires,
                listOf("evidence:a")
            )
        )

        assertEquals(AgentLifecycleState.FAILED, result.instance.lifecycle)
        assertNull(result.artifact)
        assertTrue(result.workspaceDisposed)
        assertEquals(AgentAuditEventKind.FAILED, events.dropLast(1).last().kind)
        assertEquals(AgentAuditEventKind.WORKSPACE_DISPOSED, events.last().kind)
    }

    @Test
    fun budget_overrun_is_fail_closed_and_discards_artifact() {
        val result = assertIs<AgentFactoryResult.Terminal>(
            factory(
                mutableListOf(),
                AgentRuntimeAdapter {
                    AgentRuntimeOutcome.Completed(
                        "research-report",
                        "sha256:too-expensive",
                        listOf("evidence:a"),
                        AgentRuntimeUsage(
                            wallClockMillis = 100,
                            inferenceUnits = budget.maxInferenceUnits + 1,
                            contextBytes = 1024,
                            retrievalItems = 1,
                            artifactCount = 1
                        )
                    )
                }
            ).runSingle(
                request(), AgentPopulationSnapshot(0, 0, 0), AgentInstanceGeneration(1),
                now, expires, listOf("evidence:a")
            )
        )
        assertEquals(AgentLifecycleState.BUDGET_EXHAUSTED, result.instance.lifecycle)
        assertNull(result.artifact)
    }

    @Test
    fun adapter_failure_is_terminal_and_never_respawns() {
        var calls = 0
        val result = assertIs<AgentFactoryResult.Terminal>(
            factory(
                mutableListOf(),
                AgentRuntimeAdapter {
                    calls++
                    error("adapter crash")
                }
            ).runSingle(
                request(), AgentPopulationSnapshot(0, 0, 0), AgentInstanceGeneration(1),
                now, expires, listOf("evidence:a")
            )
        )
        assertEquals(1, calls)
        assertEquals(AgentLifecycleState.FAILED, result.instance.lifecycle)
        assertNull(result.artifact)
        assertFailsWith<IllegalArgumentException> {
            result.instance.transition(AgentLifecycleState.RUNNING)
        }
    }

    @Test
    fun workspace_duplicate_provenance_fails_closed() {
        assertFailsWith<IllegalArgumentException> {
            AgentWorkspace.create(
                AgentInstanceId("agent-1"),
                listOf("evidence:a", "evidence:a"),
                maxContextBytes = 128
            )
        }
    }

    @Test
    fun workspace_context_is_hard_bounded_by_agent_budget() {
        assertFailsWith<IllegalArgumentException> {
            AgentWorkspace.create(
                AgentInstanceId("agent-1"),
                listOf("x".repeat(129)),
                maxContextBytes = 128
            )
        }
    }

    @Test
    fun runtime_and_factory_contracts_contain_no_authority_execution_or_secret_fields() {
        val forbidden = listOf(
            "authority", "permission", "credential", "secret",
            "token", "license", "executiongrant", "principal"
        )
        listOf(
            AgentRuntimeContext::class.java,
            AgentWorkspace::class.java,
            AgentRuntimeUsage::class.java,
            AgentAuditEvent::class.java,
            AgentFactoryResult.Terminal::class.java
        ).forEach { type ->
            val fields = type.declaredFields.map { it.name.lowercase() }
            forbidden.forEach { word ->
                assertTrue(fields.none { word in it }, "${type.simpleName} contains forbidden field: $word")
            }
        }
    }

    private fun factory(
        events: MutableList<AgentAuditEvent>,
        adapter: AgentRuntimeAdapter,
        timeSource: () -> Instant = { now }
    ): AgentFactory {
        val registry = AgentBlueprintRegistry(listOf(blueprint))
        val policy = AgentAdmissionPolicy(
            AgentFactoryBounds.PROTOTYPE,
            AgentCognitiveScope.create(listOf("research", "verification")),
            budget.copy(
                maxWallClockMillis = 120_000,
                maxInferenceUnits = 100_000,
                maxContextBytes = 1_000_000,
                maxRetrievalItems = 128,
                maxArtifacts = 32,
                maxDescendants = 12
            )
        )
        return AgentFactory(
            registry,
            policy,
            adapter,
            AgentAuditLedger { events += it },
            timeSource
        )
    }

    private fun request() = AgentSpawnRequest.create(
        AgentBlueprintReference(blueprint.id, blueprint.version),
        AgentSpawnProvenance(AgentRootTaskId("root-1"), null, null, 0),
        scope,
        budget
    )
}
