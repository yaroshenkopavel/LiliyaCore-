package pro.liliya.core.asf

import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentParallelFactoryWaveRunnerTest {
    private val a = AgentCoordinatorStepId("a")
    private val b = AgentCoordinatorStepId("b")

    @Test
    fun factory_policy_rejects_second_reserved_slot_before_dispatch() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val executions = AtomicInteger()
            val blueprint = AgentBlueprint.create(
                AgentBlueprintVersion(1), "researcher", "analysis",
                AgentCognitiveScope.create(listOf("research"))
            )
            val budget = AgentWorkBudget(30_000, 20_000, 128_000, 16, 4, 2)
            val factory = AgentFactory(
                AgentBlueprintRegistry(listOf(blueprint)),
                AgentAdmissionPolicy(
                    AgentFactoryBounds(2, 12, 3, 4, 1),
                    blueprint.cognitiveScope, budget
                ),
                AgentRuntimeAdapter {
                    executions.incrementAndGet()
                    error("must never execute rejected wave")
                },
                AgentAuditLedger { }
            )
            val requests = listOf(a, b).map { step ->
                step to AgentParallelFactoryWaveRunner.Entry(
                    request = AgentSpawnRequest.create(
                        blueprint = AgentBlueprintReference(blueprint.id, blueprint.version),
                        provenance = AgentSpawnProvenance(
                            AgentRootTaskId("root-${step.value}"), null, null, 0
                        ),
                        cognitiveScope = blueprint.cognitiveScope,
                        budget = budget
                    ),
                    generation = AgentInstanceGeneration(1),
                    inputReferences = emptyList()
                )
            }.toMap()
            // Mixed roots are structurally refused before any agent launches.
            assertFailsWith<IllegalArgumentException> {
                AgentParallelFactoryWaveRunner(
                    factory, AgentParallelWaveExecutor(pool)
                ).run(
                    wave(), requests, AgentPopulationSnapshot(1, 0, 0),
                    Instant.parse("2026-10-11T00:00:00Z"),
                    Instant.parse("2026-10-12T00:00:00Z"), 2_000
                )
            }
            val sharedRoot = requests.mapValues { (_, entry) ->
                entry.copy(request = AgentSpawnRequest.create(
                    entry.request.blueprint,
                    AgentSpawnProvenance(AgentRootTaskId("shared-root"), null, null, 0),
                    entry.request.cognitiveScope, entry.request.budget,
                    if (entry.request.provenance.rootTaskId.value == "root-a") 0 else 1
                ))
            }
            val result = AgentParallelFactoryWaveRunner(
                factory, AgentParallelWaveExecutor(pool)
            ).run(
                wave(), sharedRoot, AgentPopulationSnapshot(1, 0, 0),
                Instant.parse("2026-10-11T00:00:00Z"),
                Instant.parse("2026-10-12T00:00:00Z"), 2_000
            )
            assertEquals(AgentParallelWaveExecutionState.PARTIAL, result.state)
            assertEquals(0, executions.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun actual_factory_execution_produces_completed_wave_artifact_references() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val executions = AtomicInteger()
            val now = Instant.parse("2026-10-11T00:00:00Z")
            val blueprint = AgentBlueprint.create(
                AgentBlueprintVersion(1), "researcher", "analysis",
                AgentCognitiveScope.create(listOf("research"))
            )
            val budget = AgentWorkBudget(30_000, 20_000, 128_000, 16, 4, 2)
            val factory = AgentFactory(
                AgentBlueprintRegistry(listOf(blueprint)),
                AgentAdmissionPolicy(
                    AgentFactoryBounds.PROTOTYPE, blueprint.cognitiveScope, budget
                ),
                AgentRuntimeAdapter { context ->
                    executions.incrementAndGet()
                    AgentRuntimeOutcome.Completed(
                        "research-report", "sha256:result",
                        context.workspace.inputReferences,
                        AgentRuntimeUsage(100, 100, 1024, 1, 1)
                    )
                },
                AgentAuditLedger { },
                timeSource = { now }
            )
            val entries = listOf(a, b).mapIndexed { index, step ->
                step to AgentParallelFactoryWaveRunner.Entry(
                    request = AgentSpawnRequest.create(
                        AgentBlueprintReference(blueprint.id, blueprint.version),
                        AgentSpawnProvenance(AgentRootTaskId("shared-root"), null, null, 0),
                        blueprint.cognitiveScope, budget, index
                    ),
                    generation = AgentInstanceGeneration(1),
                    inputReferences = listOf("evidence:shared")
                )
            }.toMap()
            val result = AgentParallelFactoryWaveRunner(
                factory, AgentParallelWaveExecutor(pool)
            ).run(
                wave(), entries, AgentPopulationSnapshot(0, 0, 0),
                now, now.plusSeconds(300), 2_000
            )
            assertEquals(AgentParallelWaveExecutionState.COMPLETED, result.state)
            assertEquals(2, executions.get())
            assertEquals(listOf(a, b), result.outcomes.map {
                (it as AgentParallelWaveTaskOutcome.Completed).stepId
            })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun timeout_keeps_population_reserved_until_uncooperative_worker_exits() {
        val pool = Executors.newFixedThreadPool(2)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            val now = Instant.parse("2026-10-11T00:00:00Z")
            val blueprint = AgentBlueprint.create(
                AgentBlueprintVersion(1), "researcher", "analysis",
                AgentCognitiveScope.create(listOf("research"))
            )
            val budget = AgentWorkBudget(30_000, 20_000, 128_000, 16, 4, 2)
            val factory = AgentFactory(
                AgentBlueprintRegistry(listOf(blueprint)),
                AgentAdmissionPolicy(
                    AgentFactoryBounds.PROTOTYPE, blueprint.cognitiveScope, budget
                ),
                AgentRuntimeAdapter { context ->
                    entered.countDown()
                    // Model an uncooperative runtime that ignores interrupts.
                    while (release.count > 0) {
                        try { release.await(20, java.util.concurrent.TimeUnit.MILLISECONDS) }
                        catch (_: InterruptedException) { }
                    }
                    AgentRuntimeOutcome.Completed(
                        "research-report", "sha256:result",
                        context.workspace.inputReferences,
                        AgentRuntimeUsage(100, 100, 1024, 1, 1)
                    )
                },
                AgentAuditLedger { }, timeSource = { now }
            )
            val request = AgentSpawnRequest.create(
                AgentBlueprintReference(blueprint.id, blueprint.version),
                AgentSpawnProvenance(AgentRootTaskId("shared-root"), null, null, 0),
                blueprint.cognitiveScope, budget
            )
            val entry = AgentParallelFactoryWaveRunner.Entry(
                request, AgentInstanceGeneration(1), listOf("evidence:a")
            )
            val ledger = AgentParallelPopulationLedger(1, 1)
            val one = AgentParallelWave(0, listOf(a),
                AgentParallelWaveReservation(80, 20_000, 128_000, 16, 4, 1))
            val result = AgentParallelFactoryWaveRunner(factory, AgentParallelWaveExecutor(pool))
                .runReserved(ledger, one, mapOf(a to entry),
                    AgentPopulationSnapshot(0, 0, 0), now, now.plusSeconds(300), 80)
            assertEquals(AgentParallelWaveExecutionState.TIMED_OUT, result.state)
            kotlin.test.assertTrue(entered.count == 0L)
            kotlin.test.assertNull(ledger.reserve(AgentRootTaskId("other"), 1))
            release.countDown()
            var next: AgentParallelPopulationLedger.Lease? = null
            repeat(100) {
                if (next == null) {
                    next = ledger.reserve(AgentRootTaskId("other"), 1)
                    if (next == null) Thread.sleep(10)
                }
            }
            kotlin.test.assertNotNull(next).close()
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    private fun wave() = AgentParallelWave(
        0, listOf(a, b),
        AgentParallelWaveReservation(2_000, 40_000, 256_000, 32, 8, 2)
    )
}
