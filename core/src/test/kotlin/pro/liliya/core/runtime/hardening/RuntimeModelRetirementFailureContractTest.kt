package pro.liliya.core.runtime.hardening

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import pro.liliya.core.protectedmodel.ProtectedModelAccessResult
import pro.liliya.core.protectedmodel.ProtectedModelGeneration
import pro.liliya.core.protectedmodel.ProtectedModelPackageId
import pro.liliya.core.protectedmodel.ProtectedModelReference

class RuntimeModelRetirementFailureContractTest {
    @Test
    fun retirement_cleanup_failure_is_structural_private_and_not_retried_implicitly() {
        val registry = RuntimeModelSessionRegistry()
        val ownership = activeSession(registry, "session", 1)
        val supervisor = supervisor(registry)
        var cleanupCalls = 0

        assertSame(RuntimeSessionQuiescenceResult.Quiescing, supervisor.beginQuiescing(ownership.reference))

        val failed = assertIs<RuntimeSessionDrainRetirementResult.Failed>(
            supervisor.retireIfDrained(ownership.reference) {
                cleanupCalls += 1
                throw IllegalStateException("secret-retirement-cleanup-message")
            }
        )

        assertEquals(RuntimeHardeningFailure.RETIREMENT_FAILED, failed.reason)
        assertFalse(failed.toString().contains("secret-retirement-cleanup-message"))
        assertTrue(failed.toString().contains(IllegalStateException::class.java.name))
        assertEquals(1, cleanupCalls)
        assertTrue(ownership.isCurrent())
        assertEquals(RuntimeModelSessionLifecycle.FAILED, ownership.lifecycle())
        assertEquals(RuntimeHardeningFailure.RETIREMENT_FAILED, registry.currentFailure())

        val rejected = assertIs<RuntimeOperationAdmissionResult.Rejected>(supervisor.admit())
        assertEquals(RuntimeHardeningFailure.SESSION_UNAVAILABLE, rejected.reason)
        assertFalse(ownership.retire())

        assertSame(RuntimeFailedSessionRetirementResult.Retired, supervisor.retireFailed(ownership.reference))
        assertEquals(1, cleanupCalls)
        assertFalse(ownership.isCurrent())
        assertEquals(RuntimeModelSessionLifecycle.RETIRED, ownership.lifecycle())
    }

    @Test
    fun replacement_requires_explicit_failed_retirement_after_cleanup_failure() {
        val registry = RuntimeModelSessionRegistry()
        val ownership = activeSession(registry, "same-label", 1)
        val supervisor = supervisor(registry)

        assertSame(RuntimeSessionQuiescenceResult.Quiescing, supervisor.beginQuiescing(ownership.reference))
        assertIs<RuntimeSessionDrainRetirementResult.Failed>(
            supervisor.retireIfDrained(ownership.reference) {
                error("cleanup-secret")
            }
        )

        val blocked = RuntimeModelActivationCoordinator(registry).activate(
            RuntimeModelSessionId("same-label"),
            ProtectedModelAccessResult.Opened(model(2), "replacement")
        ) { _, _ -> }
        assertEquals(
            RuntimeHardeningFailure.ACTIVATION_REJECTED,
            assertIs<RuntimeModelActivationResult.Rejected>(blocked).reason
        )

        assertSame(RuntimeFailedSessionRetirementResult.Retired, supervisor.retireFailed(ownership.reference))

        val replacement = assertIs<RuntimeModelActivationResult.Activated<String>>(
            RuntimeModelActivationCoordinator(registry).activate(
                RuntimeModelSessionId("same-label"),
                ProtectedModelAccessResult.Opened(model(2), "replacement")
            ) { _, _ -> }
        )
        assertTrue(replacement.session.generation.value > ownership.reference.generation.value)
    }

    @Test
    fun reentrant_quiescing_from_retirement_cleanup_fails_closed_without_ownership_bypass() {
        val registry = RuntimeModelSessionRegistry()
        val ownership = activeSession(registry, "session", 1)
        val supervisor = supervisor(registry)

        assertSame(RuntimeSessionQuiescenceResult.Quiescing, supervisor.beginQuiescing(ownership.reference))

        val failed = assertIs<RuntimeSessionDrainRetirementResult.Failed>(
            supervisor.retireIfDrained(ownership.reference) {
                supervisor.beginQuiescing(ownership.reference)
            }
        )

        assertEquals(RuntimeHardeningFailure.RETIREMENT_FAILED, failed.reason)
        assertTrue(failed.throwable is IllegalStateException)
        assertTrue(ownership.isCurrent())
        assertEquals(RuntimeModelSessionLifecycle.FAILED, ownership.lifecycle())
        assertEquals(RuntimeHardeningFailure.RETIREMENT_FAILED, registry.currentFailure())
    }

    private fun supervisor(registry: RuntimeModelSessionRegistry): RuntimeModelOperationSupervisor =
        RuntimeModelOperationSupervisor(
            registry = registry,
            limits = RuntimeHardeningLimits(maxInFlightOperationsPerSession = 1)
        )

    private fun activeSession(
        registry: RuntimeModelSessionRegistry,
        id: String,
        generation: Long
    ): RuntimeModelSessionOwnership {
        val ownership = assertIs<RuntimeSessionRegistrationResult.Registered>(
            registry.register(RuntimeModelSessionId(id), model(generation))
        ).ownership
        assertSame(RuntimeSessionPublicationResult.Published, ownership.publishIfCurrent {})
        return ownership
    }

    private fun model(generation: Long): ProtectedModelReference = ProtectedModelReference(
        packageId = ProtectedModelPackageId("protected-model-secret"),
        generation = ProtectedModelGeneration(generation)
    )
}
