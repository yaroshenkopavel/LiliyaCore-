package pro.liliya.core.protectedmodel

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtectedModelAccessCoordinatorConcurrencyContractTest {
    @Test
    fun replacement_cannot_commit_between_final_stale_check_and_publish() {
        val packageId = ProtectedModelPackageId("concurrency-model")
        val oldReference = ProtectedModelReference(packageId, ProtectedModelGeneration(1))
        val newReference = ProtectedModelReference(packageId, ProtectedModelGeneration(2))
        val ownership = ProtectedModelRuntimeOwnership()
        val ticket = ownership.replaceTarget(oldReference)

        val publishEntered = CountDownLatch(1)
        val releasePublish = CountDownLatch(1)
        val replacementStarted = CountDownLatch(1)
        val replacementFinished = CountDownLatch(1)
        val published = AtomicBoolean(false)

        val publishThread = Thread {
            val committed = ownership.publishIfCurrent(ticket) {
                publishEntered.countDown()
                check(releasePublish.await(5, TimeUnit.SECONDS))
                published.set(true)
            }
            check(committed)
        }
        publishThread.start()
        assertTrue(publishEntered.await(5, TimeUnit.SECONDS))

        val replacementThread = Thread {
            replacementStarted.countDown()
            ownership.replaceTarget(newReference)
            replacementFinished.countDown()
        }
        replacementThread.start()
        assertTrue(replacementStarted.await(5, TimeUnit.SECONDS))

        assertFalse(
            replacementFinished.await(150, TimeUnit.MILLISECONDS),
            "replacement must remain behind the atomic publication ownership barrier"
        )

        releasePublish.countDown()
        publishThread.join(5_000)
        replacementThread.join(5_000)

        assertTrue(published.get())
        assertEquals(0L, replacementFinished.count)
        assertEquals(newReference, ownership.currentReference())
        assertFalse(ownership.isCurrent(ticket))
    }

    @Test
    fun reentrant_replacement_is_rejected_inside_publication_barrier() {
        val packageId = ProtectedModelPackageId("reentrant-model")
        val oldReference = ProtectedModelReference(packageId, ProtectedModelGeneration(1))
        val newReference = ProtectedModelReference(packageId, ProtectedModelGeneration(2))
        val ownership = ProtectedModelRuntimeOwnership()
        val ticket = ownership.replaceTarget(oldReference)

        assertFailsWith<IllegalStateException> {
            ownership.publishIfCurrent(ticket) {
                ownership.replaceTarget(newReference)
            }
        }

        assertEquals(oldReference, ownership.currentReference())
        assertTrue(ownership.isCurrent(ticket))
    }
}
