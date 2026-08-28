package pro.liliya.core.authority

import java.time.Instant
import pro.liliya.core.capability.CapabilityDescriptor
import pro.liliya.core.capability.CapabilityProviderId
import pro.liliya.core.capability.CapabilityRegistry
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapabilityAuthorityCompositionContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val diagnostics: InMemoryDiagnosticSink,
        val composition: CapabilityAuthorityComposition
    )

    private val now = Instant.parse("2026-08-28T19:00:00Z")
    private val capability = CapabilityId("device.launch")
    private val provider = CapabilityProviderId("android.intent")
    private val planner = AuthorityPrincipal("planner")
    private val executor = AuthorityPrincipal("executor")
    private val scope = AuthorityScope("app:maps")

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val diagnostics = InMemoryDiagnosticSink()
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(diagnostics),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "capability-authority" }
        )
        return Fixture(
            logs = logs,
            diagnostics = diagnostics,
            composition = CapabilityAuthorityComposition(
                foundation = foundation,
                now = { now }
            )
        )
    }

    @Test
    fun capability_presence_does_not_grant_authority() {
        val f = fixture()
        assertIs<CapabilityOwnershipResult.Registered>(
            f.composition.registerCapability(CapabilityDescriptor(capability, provider))
        )

        assertIs<AuthorityDecision.Denied>(
            f.composition.authorize(
                AuthorityRequest(planner, capability, "launch maps", scope)
            )
        )
    }

    @Test
    fun direct_grant_requires_registered_capability_and_revoke_is_immediate() {
        val f = fixture()
        val grant = DirectAuthorityGrant(planner, capability, scope)

        assertIs<DirectAuthorityGrantOwnershipResult.Rejected>(
            f.composition.registerDirectGrant(grant)
        )

        f.composition.registerCapability(CapabilityDescriptor(capability, provider))
        val ownership = assertIs<DirectAuthorityGrantOwnershipResult.Registered>(
            f.composition.registerDirectGrant(grant)
        ).ownership

        assertEquals(
            AuthorityDecision.Granted,
            f.composition.authorize(
                AuthorityRequest(planner, capability, "launch maps", scope)
            )
        )

        assertTrue(ownership.revoke())
        assertIs<AuthorityDecision.Denied>(
            f.composition.authorize(
                AuthorityRequest(planner, capability, "launch maps", scope)
            )
        )
    }

    @Test
    fun capability_unregister_fail_closes_existing_authority() {
        val f = fixture()
        val capabilityOwnership = assertIs<CapabilityOwnershipResult.Registered>(
            f.composition.registerCapability(CapabilityDescriptor(capability, provider))
        ).ownership
        f.composition.registerDirectGrant(DirectAuthorityGrant(planner, capability, scope))

        assertEquals(
            AuthorityDecision.Granted,
            f.composition.authorize(
                AuthorityRequest(planner, capability, "launch maps", scope)
            )
        )
        assertTrue(capabilityOwnership.unregister())
        assertNull(f.composition.findCapability(capability))
        assertEquals(emptyList(), f.composition.directGrantSnapshot())
        assertIs<AuthorityDecision.Denied>(
            f.composition.authorize(
                AuthorityRequest(planner, capability, "launch maps", scope)
            )
        )
    }

    @Test
    fun capability_re_registration_does_not_resurrect_old_direct_grant() {
        val f = fixture()
        val firstCapability = assertIs<CapabilityOwnershipResult.Registered>(
            f.composition.registerCapability(CapabilityDescriptor(capability, provider))
        ).ownership
        val oldGrant = assertIs<DirectAuthorityGrantOwnershipResult.Registered>(
            f.composition.registerDirectGrant(DirectAuthorityGrant(planner, capability, scope))
        ).ownership

        assertTrue(firstCapability.unregister())
        assertFalse(oldGrant.revoke())

        assertIs<CapabilityOwnershipResult.Registered>(
            f.composition.registerCapability(
                CapabilityDescriptor(capability, CapabilityProviderId("android.accessibility"))
            )
        )

        assertEquals(emptyList(), f.composition.directGrantSnapshot())
        assertIs<AuthorityDecision.Denied>(
            f.composition.authorize(
                AuthorityRequest(planner, capability, "launch maps", scope)
            )
        )
    }

    @Test
    fun delegation_is_authorizable_only_while_direct_source_is_active() {
        val f = fixture()
        f.composition.registerCapability(CapabilityDescriptor(capability, provider))
        val direct = assertIs<DirectAuthorityGrantOwnershipResult.Registered>(
            f.composition.registerDirectGrant(
                DirectAuthorityGrant(planner, capability, scope, now.plusSeconds(120))
            )
        ).ownership

        val delegated = assertIs<CapabilityAuthorityDelegationResult.Granted>(
            f.composition.delegate(
                AuthorityDelegationRequest(
                    delegator = planner,
                    delegate = executor,
                    capability = capability,
                    scope = scope,
                    reason = "execute launch",
                    expiresAt = now.plusSeconds(60)
                )
            )
        )

        assertEquals(
            AuthorityDecision.Granted,
            f.composition.authorize(
                AuthorityRequest(executor, capability, "launch maps", scope)
            )
        )

        assertTrue(direct.revoke())
        assertIs<AuthorityDecision.Denied>(
            f.composition.authorize(
                AuthorityRequest(executor, capability, "launch maps", scope)
            )
        )
        assertFalse(delegated.grant.asScopedGrant().origin == AuthorityGrantOrigin.DIRECT)
    }

    @Test
    fun direct_source_replacement_does_not_resurrect_old_delegation() {
        val f = fixture()
        f.composition.registerCapability(CapabilityDescriptor(capability, provider))
        val firstSource = assertIs<DirectAuthorityGrantOwnershipResult.Registered>(
            f.composition.registerDirectGrant(DirectAuthorityGrant(planner, capability, scope))
        ).ownership
        val delegated = assertIs<CapabilityAuthorityDelegationResult.Granted>(
            f.composition.delegate(
                AuthorityDelegationRequest(
                    planner,
                    executor,
                    capability,
                    scope,
                    "execute launch",
                    now.plusSeconds(60)
                )
            )
        )

        assertTrue(firstSource.revoke())
        assertIs<DirectAuthorityGrantOwnershipResult.Registered>(
            f.composition.registerDirectGrant(DirectAuthorityGrant(planner, capability, scope))
        )

        assertIs<AuthorityDecision.Denied>(
            f.composition.authorize(
                AuthorityRequest(executor, capability, "launch maps", scope)
            )
        )
        assertTrue(delegated.ownership.revoke())
    }

    @Test
    fun delegated_grant_has_exact_revoke_and_cannot_be_redelegated() {
        val f = fixture()
        f.composition.registerCapability(CapabilityDescriptor(capability, provider))
        f.composition.registerDirectGrant(DirectAuthorityGrant(planner, capability, scope))

        val delegated = assertIs<CapabilityAuthorityDelegationResult.Granted>(
            f.composition.delegate(
                AuthorityDelegationRequest(
                    delegator = planner,
                    delegate = executor,
                    capability = capability,
                    scope = scope,
                    reason = "execute launch",
                    expiresAt = now.plusSeconds(60)
                )
            )
        )

        assertIs<CapabilityAuthorityDelegationResult.Denied>(
            f.composition.delegate(
                AuthorityDelegationRequest(
                    delegator = executor,
                    delegate = AuthorityPrincipal("worker"),
                    capability = capability,
                    scope = scope,
                    reason = "redelegate",
                    expiresAt = now.plusSeconds(30)
                )
            )
        )

        assertTrue(delegated.ownership.revoke())
        assertFalse(delegated.ownership.revoke())
        assertIs<AuthorityDecision.Denied>(
            f.composition.authorize(
                AuthorityRequest(executor, capability, "launch maps", scope)
            )
        )
    }

    @Test
    fun public_api_does_not_expose_raw_mutable_authority_internals() {
        val forbidden = setOf(
            CapabilityRegistry::class.java,
            AuthorityGrantRegistry::class.java,
            AuthorityManager::class.java,
            AuthorityPolicy::class.java,
            AuthorityDelegationManager::class.java,
            AuthorityDelegationPolicy::class.java
        )

        val publicMethods = CapabilityAuthorityComposition::class.java.methods
            .filter { method -> method.declaringClass == CapabilityAuthorityComposition::class.java }

        assertTrue(publicMethods.isNotEmpty())
        assertTrue(publicMethods.none { method -> method.returnType in forbidden })
    }

    @Test
    fun authority_and_delegation_observations_keep_composition_created_correlation() {
        val f = fixture()
        f.composition.registerCapability(CapabilityDescriptor(capability, provider))
        f.composition.registerDirectGrant(DirectAuthorityGrant(planner, capability, scope))
        f.composition.authorize(AuthorityRequest(planner, capability, "launch maps", scope))
        f.composition.delegate(
            AuthorityDelegationRequest(
                planner,
                executor,
                capability,
                scope,
                "execute launch",
                now.plusSeconds(60)
            )
        )

        val authorityEvents = f.logs.snapshot().filter { event ->
            event.marker == "AUTHORITY_GRANTED" || event.marker == "AUTHORITY_DELEGATION_GRANTED"
        }
        assertEquals(2, authorityEvents.size)
        assertEquals(setOf("capability-authority"), authorityEvents.map { it.context.correlationId }.toSet())
        assertEquals(
            authorityEvents.map { it.marker },
            f.diagnostics.snapshot()
                .filter { event -> event.code in setOf("AUTHORITY_GRANTED", "AUTHORITY_DELEGATION_GRANTED") }
                .map { it.code }
        )
    }
}
