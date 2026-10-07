package pro.liliya.core.asf

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentHeterogeneousRuntimeConcurrencyContractTest {
    private val descriptor = AgentWorkerRuntimeDescriptor(
        runtimeId = "single-flight-llm-v1",
        kind = AgentWorkerRuntimeKind.LLM,
        modelId = "bounded-model"
    )
    private val scope = AgentCognitiveScope.create(listOf("analysis"))
    private val budget = AgentWorkBudget(
        maxWallClockMillis = 2_000,
        maxInferenceUnits = 100,
        maxContextBytes = 4_096,
        maxRetrievalItems = 0,
        maxArtifacts = 1,
        maxDescendants = 0
    )
    private val blueprint = AgentBlueprint.create(
        AgentBlueprintVersion(1),
        "runtime-concurrency",
        "single-flight",
        scope
    )

    @Test
    fun one_runtime_registration_serializes_concurrent_workers_and_accounts_wait() {
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val firstInside = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondNanoCalls = AtomicInteger(0)
        val firstResult = AtomicReference<AgentRuntimeOutcome>()
        val secondResult = AtomicReference<AgentRuntimeOutcome>()

        val registration = AgentCognitiveRuntimeRegistration(
            descriptor = descriptor,
            available = { true },
            maxConcurrentExecutions = 1,
            adapter = AgentCognitiveRuntimeExecutionAdapter { request ->
                val nowActive = active.incrementAndGet()
                maxActive.updateAndGet { maxOf(it, nowActive) }
                try {
                    if (request.context.instanceId.value == "agent-one") {
                        firstInside.countDown()
                        releaseFirst.await(2, TimeUnit.SECONDS)
                    }
                    AgentCognitiveRuntimeExecutionResult.Completed.create(
                        artifactKind = "llm",
                        payloadDigest = "sha256:" + request.context.instanceId.value,
                        sourceReferences = request.context.workspace.inputReferences,
                        usage = AgentRuntimeUsage(7, 3, 10, 0, 1)
                    )
                } finally {
                    active.decrementAndGet()
                }
            }
        )
        val fabric = AgentHeterogeneousRuntimeFabric(
            registrations = listOf(registration),
            nanoTime = {
                if (Thread.currentThread().name == "second-runtime") {
                    if (secondNanoCalls.getAndIncrement() == 0) 0L else 5_000_000L
                } else {
                    0L
                }
            }
        )

        val first = Thread(
            { firstResult.set(fabric.run(context("agent-one"))) },
            "first-runtime"
        )
        first.start()
        assertTrue(firstInside.await(1, TimeUnit.SECONDS))

        val second = Thread(
            { secondResult.set(fabric.run(context("agent-two"))) },
            "second-runtime"
        )
        second.start()

        Thread.sleep(25)
        releaseFirst.countDown()
        first.join(1_000)
        second.join(1_000)

        assertEquals(1, maxActive.get())
        assertIs<AgentRuntimeOutcome.Completed>(firstResult.get())
        val secondCompleted = assertIs<AgentRuntimeOutcome.Completed>(secondResult.get())
        assertEquals(12, secondCompleted.usage.wallClockMillis)
        assertEquals(3, secondCompleted.usage.inferenceUnits)
    }

    @Test
    fun interrupted_permit_wait_fails_closed_without_second_adapter_call() {
        val firstInside = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val adapterCalls = AtomicInteger(0)
        val firstResult = AtomicReference<AgentRuntimeOutcome>()
        val secondResult = AtomicReference<AgentRuntimeOutcome>()

        val fabric = AgentHeterogeneousRuntimeFabric(
            listOf(
                AgentCognitiveRuntimeRegistration(
                    descriptor = descriptor,
                    available = { true },
                    maxConcurrentExecutions = 1,
                    adapter = AgentCognitiveRuntimeExecutionAdapter { request ->
                        adapterCalls.incrementAndGet()
                        if (request.context.instanceId.value == "agent-one") {
                            firstInside.countDown()
                            releaseFirst.await(2, TimeUnit.SECONDS)
                        }
                        AgentCognitiveRuntimeExecutionResult.Completed.create(
                            "llm",
                            "sha256:ok",
                            request.context.workspace.inputReferences,
                            AgentRuntimeUsage(1, 1, 10, 0, 1)
                        )
                    }
                )
            )
        )

        val first = Thread {
            firstResult.set(fabric.run(context("agent-one")))
        }
        first.start()
        assertTrue(firstInside.await(1, TimeUnit.SECONDS))

        val second = Thread {
            secondResult.set(fabric.run(context("agent-two")))
        }
        second.start()
        Thread.sleep(25)
        second.interrupt()
        second.join(1_000)

        val failed = assertIs<AgentRuntimeOutcome.Failed>(secondResult.get())
        assertEquals(
            "heterogeneous worker runtime concurrency permit unavailable",
            failed.reason
        )
        assertEquals(1, adapterCalls.get())

        releaseFirst.countDown()
        first.join(1_000)
        assertIs<AgentRuntimeOutcome.Completed>(firstResult.get())
    }

    @Test
    fun runtime_registration_rejects_non_positive_concurrency_limit() {
        assertFailsWith<IllegalArgumentException> {
            AgentCognitiveRuntimeRegistration(
                descriptor = descriptor,
                available = { true },
                maxConcurrentExecutions = 0,
                adapter = AgentCognitiveRuntimeExecutionAdapter {
                    error("must not run")
                }
            )
        }
    }

    private fun context(
        instance: String
    ): AgentRuntimeContext {
        val id = AgentInstanceId(instance)
        return AgentRuntimeContext(
            instanceId = id,
            generation = AgentInstanceGeneration(1),
            blueprint = AgentBlueprintReference(blueprint.id, blueprint.version),
            scope = scope,
            budget = budget,
            workspace = AgentWorkspace.create(
                instanceId = id,
                inputReferences = listOf("evidence:$instance"),
                maxContextBytes = budget.maxContextBytes
            ),
            startedAt = Instant.parse("2026-10-07T19:30:00Z"),
            expiresAt = Instant.parse("2026-10-07T19:35:00Z"),
            workerRuntime = descriptor
        )
    }
}
