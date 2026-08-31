package pro.liliya.core.runtime.hardening

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import pro.liliya.core.protectedmodel.ProtectedModelAccessResult
import pro.liliya.core.protectedmodel.ProtectedModelGeneration
import pro.liliya.core.protectedmodel.ProtectedModelPackageId
import pro.liliya.core.protectedmodel.ProtectedModelReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuntimeModelActivationCoordinatorContractTest {
    @Test
    fun already_opened_exact_model_is_published_as_active_session() {
        val registry = RuntimeModelSessionRegistry()
        val coordinator = RuntimeModelActivationCoordinator(registry)
        val opened = ProtectedModelAccessResult.Opened(model(1), "runtime-handle")
        var publishedReference: RuntimeModelSessionReference? = null

        val result = coordinator.activate(
            RuntimeModelSessionId("session-secret"),
            opened
        ) { reference, value ->
            assertEquals("runtime-handle", value)
            publishedReference = reference
        }

        val activated = assertIs<RuntimeModelActivationResult.Activated<String>>(result)
        assertEquals(opened.reference, activated.session.model)
        assertEquals(activated.session, publishedReference)
        assertEquals(activated.session, registry.currentReference())
        assertEquals(RuntimeModelSessionLifecycle.ACTIVE, registry.currentLifecycle())
    }

    @Test
    fun activation_rejects_when_live_session_already_exists() {
        val registry = RuntimeModelSessionRegistry()
        val first = assertIs<RuntimeSessionRegistrationResult.Registered>(
            registry.register(RuntimeModelSessionId("first"), model(1))
        ).ownership
        val coordinator = RuntimeModelActivationCoordinator(registry)
        var published = false

        val result = coordinator.activate(
            RuntimeModelSessionId("second"),
            ProtectedModelAccessResult.Opened(model(2), "new-handle")
        ) { _, _ -> published = true }

        assertEquals(
            RuntimeHardeningFailure.ACTIVATION_REJECTED,
            assertIs<RuntimeModelActivationResult.Rejected>(result).reason
        )
        assertFalse(published)
        assertTrue(first.isCurrent())
    }

    @Test
    fun publication_failure_fails_closed_and_does_not_leave_live_session() {
        val registry = RuntimeModelSessionRegistry()
        val coordinator = RuntimeModelActivationCoordinator(registry)

        val result = coordinator.activate(
            RuntimeModelSessionId("failed-session"),
            ProtectedModelAccessResult.Opened(model(1), "handle")
        ) { _, _ -> error("secret-publication-message") }

        val failed = assertIs<RuntimeModelActivationResult.Failed>(result)
        assertEquals(RuntimeHardeningFailure.ACTIVATION_FAILED, failed.reason)
        assertFalse(failed.toString().contains("secret-publication-message"))
        assertNull(registry.currentReference())
        assertNull(registry.currentLifecycle())
    }

    @Test
    fun competing_retirement_cannot_commit_during_atomic_publication() {
        val registry = RuntimeModelSessionRegistry()
        val ownership = assertIs<RuntimeSessionRegistrationResult.Registered>(
            registry.register(RuntimeModelSessionId("session"), model(1))
        ).ownership
        val publishEntered = CountDownLatch(1)
        val releasePublish = CountDownLatch(1)
        val retirementStarted = CountDownLatch(1)
        val retirementFinished = CountDownLatch(1)
        val published = AtomicBoolean(false)

        val publisher = Thread {
            val result = ownership.publishIfCurrent {
                publishEntered.countDown()
                check(releasePublish.await(5, TimeUnit.SECONDS))
                published.set(true)
            }
            check(result == RuntimeSessionPublicationResult.Published)
        }
        publisher.start()
        assertTrue(publishEntered.await(5, TimeUnit.SECONDS))

        val retirer = Thread {
            retirementStarted.countDown()
            check(ownership.retire())
            retirementFinished.countDown()
        }
        retirer.start()
        assertTrue(retirementStarted.await(5, TimeUnit.SECONDS))
        assertFalse(
            retirementFinished.await(150, TimeUnit.MILLISECONDS),
            "retirement must remain behind the atomic publication barrier"
        )

        releasePublish.countDown()
        publisher.join(5_000)
        retirer.join(5_000)

        assertTrue(published.get())
        assertEquals(0L, retirementFinished.count)
        assertNull(registry.currentReference())
        assertEquals(RuntimeModelSessionLifecycle.RETIRED, ownership.lifecycle())
    }

    @Test
    fun same_thread_reentrant_retirement_fails_activation_and_cannot_publish_active_session() {
        val registry = RuntimeModelSessionRegistry()
        val ownership = assertIs<RuntimeSessionRegistrationResult.Registered>(
            registry.register(RuntimeModelSessionId("session"), model(1))
        ).ownership

        val result = ownership.publishIfCurrent {
            ownership.retire()
        }

        val failed = assertIs<RuntimeSessionPublicationResult.Failed>(result)
        assertTrue(failed.throwable is IllegalStateException)
        assertFalse(failed.toString().contains("not allowed from inside publication"))
        assertFalse(ownership.isCurrent())
        assertEquals(RuntimeModelSessionLifecycle.FAILED, ownership.lifecycle())
        assertNull(registry.currentReference())
    }

    private fun model(generation: Long): ProtectedModelReference = ProtectedModelReference(
        ProtectedModelPackageId("protected-model-secret"),
        ProtectedModelGeneration(generation)
    )
}
