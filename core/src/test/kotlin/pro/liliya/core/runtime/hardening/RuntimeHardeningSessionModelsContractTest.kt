package pro.liliya.core.runtime.hardening

import pro.liliya.core.protectedmodel.ProtectedModelGeneration
import pro.liliya.core.protectedmodel.ProtectedModelPackageId
import pro.liliya.core.protectedmodel.ProtectedModelReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuntimeHardeningSessionModelsContractTest {
    @Test
    fun session_ownership_is_exact_and_stale_owner_cannot_retire_replacement() {
        val registry = RuntimeModelSessionRegistry()
        val first = assertIs<RuntimeSessionRegistrationResult.Registered>(
            registry.register(RuntimeModelSessionId("session-secret"), model(1))
        ).ownership

        assertEquals(1L, first.reference.generation.value)
        assertTrue(first.isCurrent())
        assertTrue(first.retire())
        assertNull(registry.currentReference())

        val second = assertIs<RuntimeSessionRegistrationResult.Registered>(
            registry.register(RuntimeModelSessionId("session-secret"), model(2))
        ).ownership

        assertEquals(2L, second.reference.generation.value)
        assertFalse(first.isCurrent())
        assertFalse(first.retire())
        assertTrue(second.isCurrent())
        assertEquals(second.reference, registry.currentReference())
    }

    @Test
    fun duplicate_live_session_fails_closed() {
        val registry = RuntimeModelSessionRegistry()
        assertIs<RuntimeSessionRegistrationResult.Registered>(
            registry.register(RuntimeModelSessionId("first"), model(1))
        )

        val rejected = assertIs<RuntimeSessionRegistrationResult.Rejected>(
            registry.register(RuntimeModelSessionId("second"), model(2))
        )

        assertEquals(RuntimeSessionRegistrationFailure.LIVE_SESSION_EXISTS, rejected.reason)
        assertEquals(1, registry.snapshot().size)
    }

    @Test
    fun generation_overflow_fails_closed_without_publishing_session() {
        val registry = RuntimeModelSessionRegistry(Long.MAX_VALUE)

        val rejected = assertIs<RuntimeSessionRegistrationResult.Rejected>(
            registry.register(RuntimeModelSessionId("overflow"), model(1))
        )

        assertEquals(RuntimeSessionRegistrationFailure.GENERATION_OVERFLOW, rejected.reason)
        assertNull(registry.currentReference())
        assertTrue(registry.snapshot().isEmpty())
    }

    @Test
    fun snapshot_is_deterministic_and_contains_only_current_exact_session() {
        val registry = RuntimeModelSessionRegistry()
        val ownership = assertIs<RuntimeSessionRegistrationResult.Registered>(
            registry.register(RuntimeModelSessionId("current"), model(7))
        ).ownership

        assertEquals(listOf(ownership.reference), registry.snapshot())
        assertEquals(listOf(ownership.reference), registry.snapshot())
    }

    @Test
    fun runtime_limits_are_explicit_and_v0_1_rejects_multi_session_configuration() {
        val limits = RuntimeHardeningLimits(
            maxLiveSessions = 1,
            maxInFlightOperationsPerSession = 4,
            maxDiagnosticSnapshotEntries = 16
        )

        assertEquals(1, limits.maxLiveSessions)
        assertEquals(4, limits.maxInFlightOperationsPerSession)
        assertEquals(16, limits.maxDiagnosticSnapshotEntries)

        assertFailsWith<IllegalArgumentException> {
            RuntimeHardeningLimits(
                maxLiveSessions = 2,
                maxInFlightOperationsPerSession = 4
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RuntimeHardeningLimits(
                maxInFlightOperationsPerSession = 0
            )
        }
    }

    @Test
    fun runtime_session_identifier_rendering_is_redacted_and_not_a_permission_token() {
        val reference = assertIs<RuntimeSessionRegistrationResult.Registered>(
            RuntimeModelSessionRegistry().register(
                RuntimeModelSessionId("runtime-session-secret"),
                model(1)
            )
        ).ownership.reference

        val rendered = reference.toString()
        assertFalse(rendered.contains("runtime-session-secret"))
        assertFalse(rendered.contains("protected-model-secret"))
        assertTrue(rendered.contains("[redacted]"))
    }

    private fun model(generation: Long): ProtectedModelReference = ProtectedModelReference(
        packageId = ProtectedModelPackageId("protected-model-secret"),
        generation = ProtectedModelGeneration(generation)
    )
}
