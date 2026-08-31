package pro.liliya.core.runtime.hardening

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import pro.liliya.core.protectedmodel.ProtectedModelGeneration
import pro.liliya.core.protectedmodel.ProtectedModelPackageId
import pro.liliya.core.protectedmodel.ProtectedModelReference

class RuntimeModelQuiescingConcurrencyContractTest {
    @Test
    fun concurrent_admission_and_quiescing_have_only_linearizable_outcomes() {
        val registry = RuntimeModelSessionRegistry()
        val ownership = activeSession(registry)
        val supervisor = supervisor(registry)
        val start = CountDownLatch(1)
        val admission = AtomicReference<RuntimeOperationAdmissionResult>()
        val quiescence = AtomicReference<RuntimeSessionQuiescenceResult>()

        val admitting = thread(start = true) {
            start.await()
            admission.set(supervisor.admit())
        }
        val quiescing = thread(start = true) {
            start.await()
            quiescence.set(supervisor.beginQuiescing(ownership.reference))
        }

        start.countDown()
        admitting.join()
        quiescing.join()

        assertSame(RuntimeSessionQuiescenceResult.Quiescing, quiescence.get())
        assertEquals(RuntimeModelSessionLifecycle.QUIESCING, ownership.lifecycle())

        when (val result = admission.get()) {
            is RuntimeOperationAdmissionResult.Admitted -> {
                assertEquals(ownership.reference, result.ticket.session)
                assertEquals(1, supervisor.inFlightCount(ownership.reference))
                val released = assertIs<RuntimeOperationReleaseResult.Terminated>(
                    supervisor.release(result.ticket, RuntimeOperationTerminal.CANCELLED)
                )
                assertEquals(RuntimeHardeningFailure.OPERATION_CANCELLED, released.reason)
            }
            is RuntimeOperationAdmissionResult.Rejected -> {
                assertEquals(RuntimeHardeningFailure.SESSION_UNAVAILABLE, result.reason)
                assertEquals(0, supervisor.inFlightCount(ownership.reference))
            }
            null -> error("admission result missing")
        }

        assertSame(
            RuntimeSessionDrainRetirementResult.Retired,
            supervisor.retireIfDrained(ownership.reference)
        )
    }

    @Test
    fun concurrent_final_release_and_retirement_have_only_drain_or_retired_outcomes() {
        val registry = RuntimeModelSessionRegistry()
        val ownership = activeSession(registry)
        val supervisor = supervisor(registry)
        val ticket = assertIs<RuntimeOperationAdmissionResult.Admitted>(supervisor.admit()).ticket
        assertSame(RuntimeSessionQuiescenceResult.Quiescing, supervisor.beginQuiescing(ownership.reference))

        val start = CountDownLatch(1)
        val releaseResult = AtomicReference<RuntimeOperationReleaseResult>()
        val retirementResult = AtomicReference<RuntimeSessionDrainRetirementResult>()

        val releasing = thread(start = true) {
            start.await()
            releaseResult.set(supervisor.release(ticket, RuntimeOperationTerminal.CANCELLED))
        }
        val retiring = thread(start = true) {
            start.await()
            retirementResult.set(supervisor.retireIfDrained(ownership.reference))
        }

        start.countDown()
        releasing.join()
        retiring.join()

        val released = assertIs<RuntimeOperationReleaseResult.Terminated>(releaseResult.get())
        assertEquals(RuntimeHardeningFailure.OPERATION_CANCELLED, released.reason)
        assertEquals(0, supervisor.inFlightCount(ownership.reference))

        when (val result = retirementResult.get()) {
            RuntimeSessionDrainRetirementResult.Retired -> Unit
            is RuntimeSessionDrainRetirementResult.DrainRequired -> {
                assertEquals(1, result.inFlightOperations)
                assertSame(
                    RuntimeSessionDrainRetirementResult.Retired,
                    supervisor.retireIfDrained(ownership.reference)
                )
            }
            RuntimeSessionDrainRetirementResult.Stale ->
                error("exact quiescing session must not become stale without retirement")
            null -> error("retirement result missing")
        }

        assertTrue(!ownership.isCurrent())
        assertEquals(RuntimeModelSessionLifecycle.RETIRED, ownership.lifecycle())
    }

    private fun supervisor(registry: RuntimeModelSessionRegistry): RuntimeModelOperationSupervisor =
        RuntimeModelOperationSupervisor(
            registry = registry,
            limits = RuntimeHardeningLimits(maxInFlightOperationsPerSession = 1)
        )

    private fun activeSession(registry: RuntimeModelSessionRegistry): RuntimeModelSessionOwnership {
        val ownership = assertIs<RuntimeSessionRegistrationResult.Registered>(
            registry.register(
                RuntimeModelSessionId("session"),
                ProtectedModelReference(
                    packageId = ProtectedModelPackageId("protected-model-secret"),
                    generation = ProtectedModelGeneration(1)
                )
            )
        ).ownership
        assertSame(RuntimeSessionPublicationResult.Published, ownership.publishIfCurrent {})
        return ownership
    }
}
