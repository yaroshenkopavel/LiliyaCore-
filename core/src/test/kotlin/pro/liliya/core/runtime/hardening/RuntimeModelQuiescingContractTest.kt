package pro.liliya.core.runtime.hardening

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import pro.liliya.core.protectedmodel.ProtectedModelGeneration
import pro.liliya.core.protectedmodel.ProtectedModelPackageId
import pro.liliya.core.protectedmodel.ProtectedModelReference

class RuntimeModelQuiescingContractTest {
    @Test
    fun quiescing_atomically_closes_new_operation_admission() {
        val registry = RuntimeModelSessionRegistry()
        val ownership = activeSession(registry, "session", 1)
        val supervisor = supervisor(registry)

        assertSame(
            RuntimeSessionQuiescenceResult.Quiescing,
            supervisor.beginQuiescing(ownership.reference)
        )
        assertEquals(RuntimeModelSessionLifecycle.QUIESCING, ownership.lifecycle())

        val rejected = assertIs<RuntimeOperationAdmissionResult.Rejected>(supervisor.admit())
        assertEquals(RuntimeHardeningFailure.SESSION_UNAVAILABLE, rejected.reason)
        assertTrue(ownership.isCurrent())
    }

    @Test
    fun retirement_requires_explicit_drain_and_never_cancels_in_flight_work() {
        val registry = RuntimeModelSessionRegistry()
        val ownership = activeSession(registry, "session", 1)
        val supervisor = supervisor(registry)
        val ticket = assertIs<RuntimeOperationAdmissionResult.Admitted>(supervisor.admit()).ticket

        assertSame(
            RuntimeSessionQuiescenceResult.Quiescing,
            supervisor.beginQuiescing(ownership.reference)
        )

        val draining = assertIs<RuntimeSessionDrainRetirementResult.DrainRequired>(
            supervisor.retireIfDrained(ownership.reference)
        )
        assertEquals(1, draining.inFlightOperations)
        assertEquals(1, supervisor.inFlightCount(ownership.reference))
        assertTrue(ownership.isCurrent())
        assertEquals(RuntimeModelSessionLifecycle.QUIESCING, ownership.lifecycle())

        val terminal = assertIs<RuntimeOperationReleaseResult.Terminated>(
            supervisor.release(ticket, RuntimeOperationTerminal.CANCELLED)
        )
        assertEquals(RuntimeHardeningFailure.OPERATION_CANCELLED, terminal.reason)
        assertEquals(0, supervisor.inFlightCount(ownership.reference))

        assertSame(
            RuntimeSessionDrainRetirementResult.Retired,
            supervisor.retireIfDrained(ownership.reference)
        )
        assertEquals(RuntimeModelSessionLifecycle.RETIRED, ownership.lifecycle())
        assertTrue(!ownership.isCurrent())
    }

    @Test
    fun successful_in_flight_operation_cannot_publish_after_quiescing_begins() {
        val registry = RuntimeModelSessionRegistry()
        val ownership = activeSession(registry, "session", 1)
        val supervisor = supervisor(registry)
        val ticket = assertIs<RuntimeOperationAdmissionResult.Admitted>(supervisor.admit()).ticket
        var publishCalls = 0

        assertSame(
            RuntimeSessionQuiescenceResult.Quiescing,
            supervisor.beginQuiescing(ownership.reference)
        )

        assertSame(
            RuntimeOperationReleaseResult.Stale,
            supervisor.release(ticket, RuntimeOperationTerminal.SUCCEEDED) {
                publishCalls += 1
            }
        )
        assertEquals(0, publishCalls)
        assertEquals(0, supervisor.inFlightCount())
        assertEquals(RuntimeModelSessionLifecycle.QUIESCING, ownership.lifecycle())
    }

    @Test
    fun repeated_quiescing_is_idempotent_for_the_same_exact_session() {
        val registry = RuntimeModelSessionRegistry()
        val ownership = activeSession(registry, "session", 1)
        val supervisor = supervisor(registry)

        assertSame(
            RuntimeSessionQuiescenceResult.Quiescing,
            supervisor.beginQuiescing(ownership.reference)
        )
        assertSame(
            RuntimeSessionQuiescenceResult.AlreadyQuiescing,
            supervisor.beginQuiescing(ownership.reference)
        )
        assertEquals(RuntimeModelSessionLifecycle.QUIESCING, ownership.lifecycle())
    }

    @Test
    fun stale_session_cannot_quiesce_or_retire_replacement() {
        val registry = RuntimeModelSessionRegistry()
        val first = activeSession(registry, "same-label", 1)
        val supervisor = supervisor(registry)

        assertSame(RuntimeSessionQuiescenceResult.Quiescing, supervisor.beginQuiescing(first.reference))
        assertSame(RuntimeSessionDrainRetirementResult.Retired, supervisor.retireIfDrained(first.reference))

        val replacement = activeSession(registry, "same-label", 2)

        assertSame(RuntimeSessionQuiescenceResult.Stale, supervisor.beginQuiescing(first.reference))
        assertSame(RuntimeSessionDrainRetirementResult.Stale, supervisor.retireIfDrained(first.reference))
        assertTrue(replacement.isCurrent())
        assertEquals(RuntimeModelSessionLifecycle.ACTIVE, replacement.lifecycle())
    }

    @Test
    fun same_thread_reentrant_quiescing_cannot_bypass_operation_publication_barrier() {
        val registry = RuntimeModelSessionRegistry()
        val ownership = activeSession(registry, "session", 1)
        val supervisor = supervisor(registry)
        val ticket = assertIs<RuntimeOperationAdmissionResult.Admitted>(supervisor.admit()).ticket

        val failed = assertIs<RuntimeOperationReleaseResult.Failed>(
            supervisor.release(ticket, RuntimeOperationTerminal.SUCCEEDED) {
                supervisor.beginQuiescing(ownership.reference)
            }
        )

        assertEquals(RuntimeHardeningFailure.OPERATION_FAILED, failed.reason)
        assertTrue(failed.throwable is IllegalStateException)
        assertTrue(ownership.isCurrent())
        assertEquals(RuntimeModelSessionLifecycle.ACTIVE, ownership.lifecycle())
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
            registry.register(
                RuntimeModelSessionId(id),
                ProtectedModelReference(
                    packageId = ProtectedModelPackageId("protected-model-secret"),
                    generation = ProtectedModelGeneration(modelGeneration)
                )
            )
        ).ownership
        assertSame(RuntimeSessionPublicationResult.Published, ownership.publishIfCurrent {})
        assertEquals(RuntimeModelSessionLifecycle.ACTIVE, ownership.lifecycle())
        return ownership
    }
}
