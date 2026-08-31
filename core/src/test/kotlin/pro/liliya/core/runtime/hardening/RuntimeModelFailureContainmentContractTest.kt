package pro.liliya.core.runtime.hardening

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import pro.liliya.core.protectedmodel.ProtectedModelAccessResult
import pro.liliya.core.protectedmodel.ProtectedModelGeneration
import pro.liliya.core.protectedmodel.ProtectedModelPackageId
import pro.liliya.core.protectedmodel.ProtectedModelReference

class RuntimeModelFailureContainmentContractTest {
    @Test
    fun session_failure_closes_admission_and_retains_structural_reason() {
        val registry = RuntimeModelSessionRegistry()
        val ownership = activeSession(registry, "session", 1)
        val supervisor = supervisor(registry)

        val failed = assertIs<RuntimeSessionFailureResult.Failed>(
            supervisor.failSession(ownership.reference, RuntimeHardeningFailure.PROVIDER_FAILED)
        )

        assertEquals(RuntimeHardeningFailure.PROVIDER_FAILED, failed.reason)
        assertEquals(RuntimeModelSessionLifecycle.FAILED, ownership.lifecycle())
        assertEquals(RuntimeHardeningFailure.PROVIDER_FAILED, registry.currentFailure())
        val rejected = assertIs<RuntimeOperationAdmissionResult.Rejected>(supervisor.admit())
        assertEquals(RuntimeHardeningFailure.SESSION_UNAVAILABLE, rejected.reason)
        assertFalse(ownership.retire())
        assertTrue(ownership.isCurrent())
    }

    @Test
    fun repeated_failure_preserves_the_first_exact_structural_reason() {
        val registry = RuntimeModelSessionRegistry()
        val ownership = activeSession(registry, "session", 1)
        val supervisor = supervisor(registry)

        val first = assertIs<RuntimeSessionFailureResult.Failed>(
            supervisor.failSession(ownership.reference, RuntimeHardeningFailure.SESSION_FAILED)
        )
        val repeated = assertIs<RuntimeSessionFailureResult.AlreadyFailed>(
            supervisor.failSession(ownership.reference, RuntimeHardeningFailure.PROVIDER_FAILED)
        )

        assertEquals(RuntimeHardeningFailure.SESSION_FAILED, first.reason)
        assertEquals(RuntimeHardeningFailure.SESSION_FAILED, repeated.reason)
        assertEquals(RuntimeHardeningFailure.SESSION_FAILED, registry.currentFailure())
    }

    @Test
    fun failed_session_retirement_invalidates_ownership_without_hidden_ticket_cancellation() {
        val registry = RuntimeModelSessionRegistry()
        val first = activeSession(registry, "same-label", 1)
        val supervisor = supervisor(registry)
        val oldTicket = assertIs<RuntimeOperationAdmissionResult.Admitted>(supervisor.admit()).ticket

        assertIs<RuntimeSessionFailureResult.Failed>(
            supervisor.failSession(first.reference, RuntimeHardeningFailure.PROVIDER_FAILED)
        )
        assertSame(
            RuntimeFailedSessionRetirementResult.Retired,
            supervisor.retireFailed(first.reference)
        )

        assertEquals(1, supervisor.inFlightCount(first.reference))
        assertFalse(first.isCurrent())
        assertEquals(RuntimeModelSessionLifecycle.RETIRED, first.lifecycle())

        val replacementResult = RuntimeModelActivationCoordinator(registry).activate(
            RuntimeModelSessionId("same-label"),
            ProtectedModelAccessResult.Opened(model(2), "replacement-handle")
        ) { _, _ -> }
        val replacement = assertIs<RuntimeModelActivationResult.Activated<String>>(replacementResult)

        assertTrue(replacement.session.generation.value > oldTicket.session.generation.value)
        assertEquals(RuntimeModelSessionLifecycle.ACTIVE, registry.currentLifecycle())
        assertEquals(replacement.session, registry.currentReference())

        var stalePublicationCalls = 0
        assertSame(
            RuntimeOperationReleaseResult.Stale,
            supervisor.release(oldTicket, RuntimeOperationTerminal.SUCCEEDED) {
                stalePublicationCalls += 1
            }
        )
        assertEquals(0, stalePublicationCalls)
        assertEquals(0, supervisor.inFlightCount(first.reference))
    }

    @Test
    fun failed_session_cannot_reactivate_without_a_fresh_activation_attempt() {
        val registry = RuntimeModelSessionRegistry()
        val ownership = activeSession(registry, "session", 1)
        val supervisor = supervisor(registry)
        var publicationCalls = 0

        assertIs<RuntimeSessionFailureResult.Failed>(
            supervisor.failSession(ownership.reference, RuntimeHardeningFailure.SESSION_FAILED)
        )

        assertSame(
            RuntimeSessionPublicationResult.Stale,
            ownership.publishIfCurrent { publicationCalls += 1 }
        )
        assertEquals(0, publicationCalls)
        assertEquals(RuntimeModelSessionLifecycle.FAILED, ownership.lifecycle())
        assertTrue(ownership.isCurrent())
    }

    @Test
    fun stale_failed_owner_cannot_fail_or_retire_replacement() {
        val registry = RuntimeModelSessionRegistry()
        val first = activeSession(registry, "same-label", 1)
        val supervisor = supervisor(registry)

        assertIs<RuntimeSessionFailureResult.Failed>(
            supervisor.failSession(first.reference, RuntimeHardeningFailure.PROVIDER_FAILED)
        )
        assertSame(RuntimeFailedSessionRetirementResult.Retired, supervisor.retireFailed(first.reference))

        val replacement = activeSession(registry, "same-label", 2)

        assertSame(
            RuntimeSessionFailureResult.Stale,
            supervisor.failSession(first.reference, RuntimeHardeningFailure.SESSION_FAILED)
        )
        assertSame(RuntimeFailedSessionRetirementResult.Stale, supervisor.retireFailed(first.reference))
        assertTrue(replacement.isCurrent())
        assertEquals(RuntimeModelSessionLifecycle.ACTIVE, replacement.lifecycle())
        assertEquals(null, registry.currentFailure())
    }

    @Test
    fun failure_transition_rejects_non_session_failure_categories() {
        val registry = RuntimeModelSessionRegistry()
        val ownership = activeSession(registry, "session", 1)
        val supervisor = supervisor(registry)

        assertFailsWith<IllegalArgumentException> {
            supervisor.failSession(ownership.reference, RuntimeHardeningFailure.OPERATION_FAILED)
        }

        assertTrue(ownership.isCurrent())
        assertEquals(RuntimeModelSessionLifecycle.ACTIVE, ownership.lifecycle())
        assertEquals(null, registry.currentFailure())
    }

    @Test
    fun same_thread_reentrant_failure_cannot_bypass_operation_publication_barrier() {
        val registry = RuntimeModelSessionRegistry()
        val ownership = activeSession(registry, "session", 1)
        val supervisor = supervisor(registry)
        val ticket = assertIs<RuntimeOperationAdmissionResult.Admitted>(supervisor.admit()).ticket

        val failed = assertIs<RuntimeOperationReleaseResult.Failed>(
            supervisor.release(ticket, RuntimeOperationTerminal.SUCCEEDED) {
                supervisor.failSession(ownership.reference, RuntimeHardeningFailure.PROVIDER_FAILED)
            }
        )

        assertEquals(RuntimeHardeningFailure.OPERATION_FAILED, failed.reason)
        assertTrue(failed.throwable is IllegalStateException)
        assertTrue(ownership.isCurrent())
        assertEquals(RuntimeModelSessionLifecycle.ACTIVE, ownership.lifecycle())
        assertEquals(null, registry.currentFailure())
        assertEquals(0, supervisor.inFlightCount())
    }

    private fun supervisor(registry: RuntimeModelSessionRegistry): RuntimeModelOperationSupervisor =
        RuntimeModelOperationSupervisor(
            registry = registry,
            limits = RuntimeHardeningLimits(maxInFlightOperationsPerSession = 2)
        )

    private fun activeSession(
        registry: RuntimeModelSessionRegistry,
        id: String,
        modelGeneration: Long
    ): RuntimeModelSessionOwnership {
        val ownership = assertIs<RuntimeSessionRegistrationResult.Registered>(
            registry.register(RuntimeModelSessionId(id), model(modelGeneration))
        ).ownership
        assertSame(RuntimeSessionPublicationResult.Published, ownership.publishIfCurrent {})
        assertEquals(RuntimeModelSessionLifecycle.ACTIVE, ownership.lifecycle())
        return ownership
    }

    private fun model(generation: Long): ProtectedModelReference = ProtectedModelReference(
        packageId = ProtectedModelPackageId("protected-model-secret"),
        generation = ProtectedModelGeneration(generation)
    )
}
