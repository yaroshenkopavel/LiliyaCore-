package pro.liliya.core.asf

import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SynchronizedAgentAuditLedgerContractTest {
    @Test
    fun concurrent_agent_appends_are_serialized_without_loss() {
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val recorded = Collections.synchronizedList(mutableListOf<AgentAuditEvent>())
        val delegate = AgentAuditLedger { event ->
            val nowActive = active.incrementAndGet()
            maxActive.updateAndGet { current -> maxOf(current, nowActive) }
            try {
                Thread.sleep(2)
                recorded += event
            } finally {
                active.decrementAndGet()
            }
        }
        val ledger = SynchronizedAgentAuditLedger(delegate)
        val threads = 8
        val eventsPerThread = 8
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val start = CountDownLatch(1)

        try {
            val futures = (0 until threads).map { producer ->
                pool.submit {
                    ready.countDown()
                    start.await(2, TimeUnit.SECONDS)
                    repeat(eventsPerThread) { sequence ->
                        ledger.append(event(producer, sequence))
                    }
                }
            }

            assertTrue(ready.await(2, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }

            assertEquals(1, maxActive.get())
            assertEquals(threads * eventsPerThread, recorded.size)

            (0 until threads).forEach { producer ->
                val instance = AgentInstanceId("audit-agent-$producer")
                val perAgent = recorded
                    .filter { it.instanceId == instance }
                    .map { it.occurredAt.nano }
                assertEquals((0 until eventsPerThread).toList(), perAgent)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun wrapper_exposes_no_authority_permission_or_license_state() {
        val forbidden = listOf(
            "authority",
            "permission",
            "principal",
            "license",
            "credential",
            "secret",
            "token"
        )
        val names = SynchronizedAgentAuditLedger::class.java.declaredFields
            .map { it.name.lowercase() }

        forbidden.forEach { marker ->
            assertTrue(names.none { marker in it })
        }
    }

    private fun event(
        producer: Int,
        sequence: Int
    ) = AgentAuditEvent(
        kind = AgentAuditEventKind.RUNNING,
        requestId = AgentSpawnRequestId("audit-request-$producer"),
        admissionId = AgentAdmissionId("audit-admission-$producer"),
        instanceId = AgentInstanceId("audit-agent-$producer"),
        generation = AgentInstanceGeneration(1),
        rootTaskId = AgentRootTaskId("audit-root"),
        occurredAt = Instant.ofEpochSecond(1_800_000_000L, sequence.toLong())
    )
}
