package pro.liliya.android.runtime

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.AuthorityScope
import pro.liliya.core.authority.CapabilityAuthorityComposition
import pro.liliya.core.authority.CapabilityId
import pro.liliya.core.authority.DirectAuthorityGrant
import pro.liliya.core.capability.CapabilityDescriptor
import pro.liliya.core.capability.CapabilityProviderId
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.LoggerFactory
import pro.liliya.core.observability.LoggerProvider

class AndroidProductRuntimeStartupAuthorityGrantAssemblyContractTest {
    @Test
    fun exact_capability_and_direct_grant_are_registered_without_replacement() {
        val authority = authority()
        val capability = CapabilityDescriptor(
            id = CapabilityId("runtime.chat"),
            providerId = CapabilityProviderId("liliya.runtime")
        )
        val grant = DirectAuthorityGrant(
            principal = AuthorityPrincipal("liliya"),
            capability = capability.id,
            scope = AuthorityScope.GLOBAL
        )

        val result = AndroidProductRuntimeStartupAuthorityGrantAssembly.install(
            authority = authority,
            plan = AndroidProductRuntimeStartupAuthorityPlan(
                capabilities = listOf(capability),
                directGrants = listOf(grant)
            )
        )

        val ready = assertIs<AndroidProductRuntimeStartupAuthorityAssemblyResult.Ready>(result)
        assertTrue(ready.ownership.authority === authority)
        assertEquals(listOf(capability), ready.ownership.capabilities.map { it.descriptor })
        assertEquals(listOf(grant), ready.ownership.directGrants.map { it.grant })
        assertEquals(capability, authority.findCapability(capability.id))
        assertEquals(listOf(grant), authority.directGrantSnapshot())
    }

    @Test
    fun direct_grant_for_missing_capability_rejects_and_leaves_authority_empty() {
        val authority = authority()
        val grant = DirectAuthorityGrant(
            principal = AuthorityPrincipal("liliya"),
            capability = CapabilityId("runtime.missing"),
            scope = AuthorityScope.GLOBAL
        )

        val result = AndroidProductRuntimeStartupAuthorityGrantAssembly.install(
            authority = authority,
            plan = AndroidProductRuntimeStartupAuthorityPlan(
                capabilities = emptyList(),
                directGrants = listOf(grant)
            )
        )

        val rejected = assertIs<AndroidProductRuntimeStartupAuthorityAssemblyResult.Rejected>(result)
        assertEquals(
            AndroidProductRuntimeStartupAuthorityAssemblyFailure.DIRECT_GRANT_REJECTED,
            rejected.reason
        )
        assertTrue(authority.capabilitySnapshot().isEmpty())
        assertTrue(authority.directGrantSnapshot().isEmpty())
    }

    @Test
    fun later_capability_failure_rolls_back_earlier_registration() {
        val authority = authority()
        val first = CapabilityDescriptor(
            id = CapabilityId("runtime.first"),
            providerId = CapabilityProviderId("liliya.runtime")
        )
        val duplicate = first.copy()

        val result = AndroidProductRuntimeStartupAuthorityGrantAssembly.install(
            authority = authority,
            plan = AndroidProductRuntimeStartupAuthorityPlan(
                capabilities = listOf(first, duplicate),
                directGrants = emptyList()
            )
        )

        val rejected = assertIs<AndroidProductRuntimeStartupAuthorityAssemblyResult.Rejected>(result)
        assertEquals(
            AndroidProductRuntimeStartupAuthorityAssemblyFailure.CAPABILITY_REJECTED,
            rejected.reason
        )
        assertTrue(authority.capabilitySnapshot().isEmpty())
        assertTrue(authority.directGrantSnapshot().isEmpty())
    }

    private fun authority(): CapabilityAuthorityComposition = CapabilityAuthorityComposition(
        FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider(LoggerFactory::create)
        )
    )
}
