package pro.liliya.core.asf

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentFactoryConcurrencyContractTest {
    private val now = Instant.parse("2026-10-07T18:00:00Z")
    private val expires = Instant.parse("2026-10-07T18:10:00Z")
    private val scope = AgentCognitiveScope.create(listOf("analysis"))
    private val budget = AgentWorkBudget(
        maxWallClockMillis = 10_000,
        maxInferenceUnits = 10_000,
        maxContextBytes = 16_000,
        maxRetrievalItems = 4,
        maxArtifacts = 2,
        maxDescendants = 0
    )
    private val blueprint = AgentBlueprint.create(
        AgentBlueprintVersion(1),
        "concurrency-worker",
        "bounded-concurrency",
        scope
    )
    private val runtimeA = AgentWorkerRuntimeDescriptor(
        "runtime-a",
        AgentWorkerRuntimeKind.LLM,
        "model-a"
    )
    private val runtimeB = AgentWorkerRuntimeDescriptor(
        "runtime-b",
        AgentWorkerRuntimeKind.LLM,
        "model-b"
    )

    @Test
    fun same_exact_runtime_identity_never_reenters_runtime_adapter_concurrently() {
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val factory = factory(
            runtimeAdapter = AgentRuntimeAdapter { context ->
                val current = active.incrementAndGet()
                maxActive.accumulateAndGet(current, ::maxOf)
                Thread.sleep(40)
                active.decrementAndGet()
                completed(context)
            }
        )
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { index ->
                pool.submit<AgentFactoryResult> {
                    factory.runSingle(
                        request = request("same-$index"),
                        population = AgentPopulationSnapshot(0, index - 1, 0),
                        generation = AgentInstanceGeneration(1),
                        admittedAt = now,
                        expiresAt = expires,
                        inputReferences = listOf("evidence:same-$index"),
                        workerRuntime = runtimeA
                    )
                }
            }
            futures.forEach { it.get(2, TimeUnit.SECONDS) }

            assertEquals(1, maxActive.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun different_exact_runtime_identities_may_execute_concurrently() {
        val bothRunning = CountDownLatch(2)
        val release = CountDownLatch(1)
        val factory = factory(
            runtimeAdapter = AgentRuntimeAdapter { context ->
                bothRunning.countDown()
                if (!bothRunning.await(1, TimeUnit.SECONDS)) {
                    error("different exact runtimes did not overlap")
                }
                release.await(1, TimeUnit.SECONDS)
                completed(context)
            }
        )
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<AgentFactoryResult> {
                factory.runSingle(
                    request("different-a"),
                    AgentPopulationSnapshot(1, 1, 0),
                    AgentInstanceGeneration(1),
                    now,
                    expires,
                    listOf("evidence:different-a"),
                    workerRuntime = runtimeA
                )
            }
            val second = pool.submit<AgentFactoryResult> {
                factory.runSingle(
                    request("different-b"),
                    AgentPopulationSnapshot(1, 1, 0),
                    AgentInstanceGeneration(1),
                    now,
                    expires,
                    listOf("evidence:different-b"),
                    workerRuntime = runtimeB
                )
            }

            assertTrue(bothRunning.await(1, TimeUnit.SECONDS))
            release.countDown()
            first.get(2, TimeUnit.SECONDS)
            second.get(2, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun audit_ledger_append_is_serialized_across_parallel_runtime_identities() {
        val activeAudit = AtomicInteger(0)
        val maxAudit = AtomicInteger(0)
        val ledger = AgentAuditLedger {
            val current = activeAudit.incrementAndGet()
            maxAudit.accumulateAndGet(current, ::maxOf)
            Thread.sleep(2)
            activeAudit.decrementAndGet()
        }
        val factory = factory(
            runtimeAdapter = AgentRuntimeAdapter(::completed),
            ledger = ledger
        )
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(runtimeA, runtimeB).mapIndexed { index, runtime ->
                pool.submit<AgentFactoryResult> {
                    factory.runSingle(
                        request("audit-$index"),
                        AgentPopulationSnapshot(1, 1, 0),
                        AgentInstanceGeneration(1),
                        now,
                        expires,
                        listOf("evidence:audit-$index"),
                        workerRuntime = runtime
                    )
                }
            }
            futures.forEach { it.get(2, TimeUnit.SECONDS) }

            assertEquals(1, maxAudit.get())
        } finally {
            pool.shutdownNow()
        }
    }

    private fun completed(
        context: AgentRuntimeContext
    ) = AgentRuntimeOutcome.Completed(
        kind = "concurrency-result",
        payloadDigest = "sha256:" + context.instanceId.value,
        provenanceReferences = context.workspace.inputReferences,
        usage = AgentRuntimeUsage(10, 10, 10, 0, 1)
    )

    private fun request(
        id: String
    ) = AgentSpawnRequest.create(
        blueprint = AgentBlueprintReference(blueprint.id, blueprint.version),
        provenance = AgentSpawnProvenance(
            rootTaskId = AgentRootTaskId("root-$id"),
            parentAgentId = null,
            parentGeneration = null,
            depth = 0
        ),
        cognitiveScope = scope,
        budget = budget
    )

    private fun factory(
        runtimeAdapter: AgentRuntimeAdapter,
        ledger: AgentAuditLedger = AgentAuditLedger { }
    ) = AgentFactory(
        registry = AgentBlueprintRegistry(listOf(blueprint)),
        admissionPolicy = AgentAdmissionPolicy(
            bounds = AgentFactoryBounds.PROTOTYPE,
            globalScope = scope,
            globalBudget = budget.copy(maxDescendants = 4)
        ),
        runtimeAdapter = runtimeAdapter,
        auditLedger = ledger,
        timeSource = { now }
    )
}
