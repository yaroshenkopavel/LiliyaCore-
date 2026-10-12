package pro.liliya.core.asf

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentParallelPopulationLedgerTest {
    @Test
    fun atomic_reservation_rejects_concurrent_oversubscription_and_releases_once() {
        val ledger = AgentParallelPopulationLedger(maxActive = 2, maxPerRoot = 2)
        val root = AgentRootTaskId("root")
        val first = assertNotNull(ledger.reserve(root, 2))
        val pool = Executors.newFixedThreadPool(4)
        try {
            val denied = AtomicInteger(0)
            val ready = CountDownLatch(4)
            val jobs = (0 until 4).map {
                pool.submit {
                    ready.countDown()
                    if (ledger.reserve(root, 1) == null) denied.incrementAndGet()
                }
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            jobs.forEach { it.get(2, TimeUnit.SECONDS) }
            assertEquals(4, denied.get())
            first.close()
            first.close()
            val replay = assertNotNull(ledger.reserve(root, 2))
            assertNull(ledger.reserve(AgentRootTaskId("other"), 1))
            replay.close()
            assertNotNull(ledger.reserve(AgentRootTaskId("other"), 2)).close()
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun per_root_limit_is_separate_from_global_limit() {
        val ledger = AgentParallelPopulationLedger(3, 2)
        val root = AgentRootTaskId("root")
        val a = assertNotNull(ledger.reserve(root, 2))
        assertNull(ledger.reserve(root, 1))
        val b = assertNotNull(ledger.reserve(AgentRootTaskId("second"), 1))
        assertNull(ledger.reserve(AgentRootTaskId("third"), 1))
        b.close()
        a.close()
    }
}
