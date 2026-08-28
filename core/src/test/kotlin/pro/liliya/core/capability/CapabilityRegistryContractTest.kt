package pro.liliya.core.capability

import pro.liliya.core.authority.AuthorityDecision
import pro.liliya.core.authority.AuthorityManager
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.AuthorityRequest
import pro.liliya.core.authority.AuthorityScope
import pro.liliya.core.authority.CapabilityId
import pro.liliya.core.authority.ScopedGrantAuthorityPolicy
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.LogContextPropagation
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.CoreObservability
import pro.liliya.core.observability.LoggerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CapabilityRegistryContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val diagnostics: InMemoryDiagnosticSink,
        val observability: CoreObservability
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val diagnostics = InMemoryDiagnosticSink()
        return Fixture(
            logs = logs,
            diagnostics = diagnostics,
            observability = CoreObservability(
                loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
                diagnostics = DiagnosticRecorder(diagnostics)
            )
        )
    }

    private val capabilityId = CapabilityId("device.launch")
    private val providerA = CapabilityProviderId("android.intent")
    private val providerB = CapabilityProviderId("android.accessibility")

    @Test
    fun provider_id_requires_explicit_identity() {
        assertFailsWith<IllegalArgumentException> { CapabilityProviderId(" ") }
    }

    @Test
    fun register_find_snapshot_and_unregister_are_observable() {
        val f = fixture()
        val registry = CapabilityRegistry(f.observability)
        val descriptor = CapabilityDescriptor(capabilityId, providerA)
        val context = context("capability-lifecycle")

        val registration = assertIs<CapabilityRegistrationResult.Registered>(
            registry.register(descriptor, context)
        ).registration

        assertEquals(descriptor, registry.find(capabilityId))
        assertEquals(mapOf(capabilityId to descriptor), registry.snapshot())
        assertTrue(registration.unregister(context))
        assertEquals(null, registry.find(capabilityId))
        assertEquals(
            listOf("CAPABILITY_REGISTERED", "CAPABILITY_UNREGISTERED"),
            f.logs.snapshot().map { it.marker }
        )
        assertEquals(
            listOf("CAPABILITY_REGISTERED", "CAPABILITY_UNREGISTERED"),
            f.diagnostics.snapshot().map { it.code }
        )
    }

    @Test
    fun duplicate_capability_id_is_rejected_and_original_owner_remains() {
        val f = fixture()
        val registry = CapabilityRegistry(f.observability)
        val first = CapabilityDescriptor(capabilityId, providerA)
        val duplicate = CapabilityDescriptor(capabilityId, providerB)
        val context = context("capability-duplicate")

        assertIs<CapabilityRegistrationResult.Registered>(registry.register(first, context))
        val rejected = assertIs<CapabilityRegistrationResult.Rejected>(
            registry.register(duplicate, context)
        )

        assertTrue(rejected.reason.contains("already registered"))
        assertEquals(first, registry.find(capabilityId))
        assertEquals(
            listOf("CAPABILITY_REGISTERED", "CAPABILITY_REGISTRATION_REJECTED"),
            f.logs.snapshot().map { it.marker }
        )
    }

    @Test
    fun stale_registration_cannot_remove_replacement_owner() {
        val f = fixture()
        val registry = CapabilityRegistry(f.observability)
        val context = context("capability-stale")
        val first = assertIs<CapabilityRegistrationResult.Registered>(
            registry.register(CapabilityDescriptor(capabilityId, providerA), context)
        ).registration

        assertTrue(first.unregister(context))

        val replacement = CapabilityDescriptor(capabilityId, providerB)
        assertIs<CapabilityRegistrationResult.Registered>(registry.register(replacement, context))

        assertEquals(false, first.unregister(context))
        assertEquals(replacement, registry.find(capabilityId))
        assertEquals("CAPABILITY_UNREGISTER_REJECTED", f.logs.snapshot().last().marker)
    }

    @Test
    fun capability_presence_does_not_grant_authority() {
        val f = fixture()
        val registry = CapabilityRegistry(f.observability)
        val context = context("capability-not-authority")
        assertIs<CapabilityRegistrationResult.Registered>(
            registry.register(CapabilityDescriptor(capabilityId, providerA), context)
        )

        val authority = AuthorityManager(
            policy = ScopedGrantAuthorityPolicy(),
            observability = f.observability
        )

        val decision = authority.authorize(
            AuthorityRequest(
                principal = AuthorityPrincipal("caller"),
                capability = capabilityId,
                reason = "launch maps",
                scope = AuthorityScope("app:maps")
            ),
            context
        )

        assertIs<AuthorityDecision.Denied>(decision)
    }

    private fun context(correlationId: String) = LogContextPropagation.root(
        module = "CORE",
        component = "CapabilityRegistry",
        operation = "contract",
        generator = CorrelationIdGenerator { correlationId }
    )
}
