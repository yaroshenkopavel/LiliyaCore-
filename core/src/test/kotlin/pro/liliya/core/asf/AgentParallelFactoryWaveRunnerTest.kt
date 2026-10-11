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

    private fun wave() = AgentParallelWave(
        0, listOf(a, b),
        AgentParallelWaveReservation(2_000, 40_000, 256_000, 32, 8, 2)
    )
}
