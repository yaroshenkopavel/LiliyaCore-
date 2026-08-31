package pro.liliya.core.runtime.hardening

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import pro.liliya.core.protectedmodel.ProtectedModelGeneration
import pro.liliya.core.protectedmodel.ProtectedModelPackageId
import pro.liliya.core.protectedmodel.ProtectedModelReference

class RuntimeModelOperationSupervisorContractTest {
    @Test
    fun operation_is_admitted_only_for_current_active_session() {
        val registry = RuntimeModelSessionRegistry()
        val supervisor = supervisor(registry, maxInFlight = 1)

        val missing = assertIs<RuntimeOperationAdmissionResult.Rejected>(supervisor.admit())
        assertEquals(RuntimeHardeningFailure.SESSION_UNAVAILABLE, missing.reason)

        val ownership = register(registry, "prepared", 1)
        val prepared = assertIs<RuntimeOperationAdmissionResult.Rejected>(supervisor.admit())
        assertEquals(RuntimeHardeningFailure.SESSION_UNAVAILABLE, prepared.reason)

        publish(ownership)
        val admitted = assertIs<RuntimeOperationAdmissionResult.Admitted>(supervisor.admit())

        assertEquals(ownership.reference, admitted.ticket.session)
        assertEquals(1L, admitted.ticket.sequence.value)
        assertEquals(1, supervisor.inFlightCount(ownership.reference))
    }

    @Test
    fun configured_in_flight_limit_is_enforced_atomically_under_concurrent_admission() {
        val registry = RuntimeModelSessionRegistry()
        val ownership = register(registry, "active", 1)
        publish(ownership)
        val supervisor = supervisor(registry, maxInFlight = 1)
        val start = CountDownLatch(1)
        val results = ConcurrentLinkedQueue<RuntimeOperationAdmissionResult>()

        val workers = List(2) {
            thread(start = true) {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                results += supervisor.admit()
            }
        }

        start.countDown()
        workers.forEach { it.join() }

        assertEquals(1, results.count { it is RuntimeOperationAdmissionResult.Admitted })
        val rejected = assertIs<RuntimeOperationAdmissionResult.Rejected>(
            results.single { it is RuntimeOperationAdmissionResult.Rejected }
        )
        assertEquals(RuntimeHardeningFailure.RESOURCE_LIMIT_REJECTED, rejected.reason)
        assertEquals(1, supervisor.inFlightCount())
    }

    @Test
    fun admitted_operation_has_exactly_one_terminal_local_release() {
        val registry = RuntimeModelSessionRegistry()
        val ownership = register(registry, "active", 1)
        publish(ownership)
        val supervisor = supervisor(registry, maxInFlight = 1)
        val ticket = assertIs<RuntimeOperationAdmissionResult.Admitted>(supervisor.admit()).ticket

        assertSame(
            RuntimeOperationReleaseResult.Released,
            supervisor.release(ticket, RuntimeOperationTerminal.FAILED)
        )
        assertEquals(0, supervisor.inFlightCount())
        assertSame(
            RuntimeOperationReleaseResult.AlreadyReleased,
            supervisor.release(ticket, RuntimeOperationTerminal.FAILED)
        )

        assertIs<RuntimeOperationAdmissionResult.Admitted>(supervisor.admit())
    }

    @Test
    fun stale_success_releases_locally_but_cannot_publish_into_replacement_session() {
        val registry = RuntimeModelSessionRegistry()
        val first = register(registry, "same-label", 1)
        publish(first)
        val supervisor = supervisor(registry, maxInFlight = 1)
        val staleTicket = assertIs<RuntimeOperationAdmissionResult.Admitted>(supervisor.admit()).ticket

        assertTrue(first.retire())
        val replacement = register(registry, "same-label", 2)
        publish(replacement)
        var stalePublicationCalls = 0

        assertSame(
            RuntimeOperationReleaseResult.Stale,
            supervisor.release(staleTicket, RuntimeOperationTerminal.SUCCEEDED) {
                stalePublicationCalls += 1
            }
        )

        assertEquals(0, stalePublicationCalls)
        assertEquals(0, supervisor.inFlightCount())
        val replacementTicket = assertIs<RuntimeOperationAdmissionResult.Admitted>(supervisor.admit()).ticket
        assertEquals(replacement.reference, replacementTicket.session)
        assertTrue(replacement.reference.generation.value > staleTicket.session.generation.value)
    }

    @Test
    fun current_success_publication_is_serialized_with_session_retirement() {
        val registry = RuntimeModelSessionRegistry()
        val ownership = register(registry, "active", 1)
        publish(ownership)
        val supervisor = supervisor(registry, maxInFlight = 1)
        val ticket = assertIs<RuntimeOperationAdmissionResult.Admitted>(supervisor.admit()).ticket
        var published = false

        assertSame(
            RuntimeOperationReleaseResult.Published,
            supervisor.release(ticket, RuntimeOperationTerminal.SUCCEEDED) {
                published = true
            }
        )

        assertTrue(published)
        assertTrue(ownership.isCurrent())
        assertTrue(ownership.retire())
    }

    @Test
    fun same_thread_reentrant_retirement_cannot_bypass_operation_publication_barrier() {
        val registry = RuntimeModelSessionRegistry()
        val ownership = register(registry, "active", 1)
        publish(ownership)
        val supervisor = supervisor(registry, maxInFlight = 1)
        val ticket = assertIs<RuntimeOperationAdmissionResult.Admitted>(supervisor.admit()).ticket

        val failed = assertIs<RuntimeOperationReleaseResult.Failed>(
            supervisor.release(ticket, RuntimeOperationTerminal.SUCCEEDED) {
                ownership.retire()
            }
        )

        assertEquals(RuntimeHardeningFailure.OPERATION_FAILED, failed.reason)
        assertTrue(failed.throwable is IllegalStateException)
        assertTrue(ownership.isCurrent())
        assertEquals(RuntimeModelSessionLifecycle.ACTIVE, ownership.lifecycle())
        assertEquals(0, supervisor.inFlightCount())
    }

    @Test
    fun publication_failure_is_structural_and_does_not_render_secret_exception_message() {
        val registry = RuntimeModelSessionRegistry()
        val ownership = register(registry, "active", 1)
        publish(ownership)
        val supervisor = supervisor(registry, maxInFlight = 1)
        val ticket = assertIs<RuntimeOperationAdmissionResult.Admitted>(supervisor.admit()).ticket

        val failed = assertIs<RuntimeOperationReleaseResult.Failed>(
            supervisor.release(ticket, RuntimeOperationTerminal.SUCCEEDED) {
                throw IllegalStateException("secret-operation-publication-message")
            }
        )

        assertEquals(RuntimeHardeningFailure.OPERATION_FAILED, failed.reason)
        assertFalse(failed.toString().contains("secret-operation-publication-message"))
        assertTrue(failed.toString().contains(IllegalStateException::class.java.name))
        assertTrue(ownership.isCurrent())
        assertEquals(0, supervisor.inFlightCount())
    }

    @Test
    fun cancellation_and_timeout_are_explicit_terminal_outcomes_without_hidden_success_publication() {
        val registry = RuntimeModelSessionRegistry()
        val ownership = register(registry, "active", 1)
        publish(ownership)
        val supervisor = supervisor(registry, maxInFlight = 2)
        val cancelled = assertIs<RuntimeOperationAdmissionResult.Admitted>(supervisor.admit()).ticket
        val timedOut = assertIs<RuntimeOperationAdmissionResult.Admitted>(supervisor.admit()).ticket
        var publishCalls = 0

        assertSame(
            RuntimeOperationReleaseResult.Released,
            supervisor.release(cancelled, RuntimeOperationTerminal.CANCELLED) { publishCalls += 1 }
        )
        assertSame(
            RuntimeOperationReleaseResult.Released,
            supervisor.release(timedOut, RuntimeOperationTerminal.TIMED_OUT) { publishCalls += 1 }
        )

        assertEquals(0, publishCalls)
        assertEquals(0, supervisor.inFlightCount())
    }

    @Test
    fun operation_sequence_overflow_fails_closed_without_admission() {
        val registry = RuntimeModelSessionRegistry()
        val ownership = register(registry, "active", 1)
        publish(ownership)
        val supervisor = RuntimeModelOperationSupervisor(
            registry = registry,
            limits = RuntimeHardeningLimits(maxInFlightOperationsPerSession = 1),
            initialSequence = Long.MAX_VALUE
        )

        val rejected = assertIs<RuntimeOperationAdmissionResult.Rejected>(supervisor.admit())

        assertEquals(RuntimeHardeningFailure.OPERATION_REJECTED, rejected.reason)
        assertEquals(0, supervisor.inFlightCount())
    }

    private fun supervisor(
        registry: RuntimeModelSessionRegistry,
        maxInFlight: Int
    ): RuntimeModelOperationSupervisor = RuntimeModelOperationSupervisor(
        registry = registry,
        limits = RuntimeHardeningLimits(maxInFlightOperationsPerSession = maxInFlight)
    )

    private fun register(
        registry: RuntimeModelSessionRegistry,
        sessionId: String,
        modelGeneration: Long
    ): RuntimeModelSessionOwnership = assertIs<RuntimeSessionRegistrationResult.Registered>(
        registry.register(
            RuntimeModelSessionId(sessionId),
            ProtectedModelReference(
                packageId = ProtectedModelPackageId("protected-model-secret"),
                generation = ProtectedModelGeneration(modelGeneration)
            )
        )
    ).ownership

    private fun publish(ownership: RuntimeModelSessionOwnership) {
        assertSame(RuntimeSessionPublicationResult.Published, ownership.publishIfCurrent {})
        assertEquals(RuntimeModelSessionLifecycle.ACTIVE, ownership.lifecycle())
    }
}
