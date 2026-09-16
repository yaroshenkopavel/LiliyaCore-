package pro.liliya.core.memory

import java.time.Instant
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.CapabilityAuthorityComposition
import pro.liliya.core.authority.CapabilityOwnershipResult
import pro.liliya.core.authority.DirectAuthorityGrant
import pro.liliya.core.authority.DirectAuthorityGrantOwnershipResult
import pro.liliya.core.capability.CapabilityDescriptor
import pro.liliya.core.capability.CapabilityProviderId
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MemoryRetentionAuthorizationContractTest {
    private val principal = AuthorityPrincipal("memory-retention-system")

    @Test
    fun prune_is_denied_when_retention_capability_is_not_registered() {
        val authority = CapabilityAuthorityComposition(foundation())
        val authorizer = MemoryRetentionAuthorizer(authority)

        assertIs<MemoryRetentionAuthorizationResult.Denied>(
            authorizer.authorize(request(MemoryRetentionClass.EPISODIC), principal)
        )
    }

    @Test
    fun prune_is_denied_when_grant_scope_belongs_to_another_retention_class() {
        val authority = configuredAuthority(MemoryRetentionClass.WORKING)
        val authorizer = MemoryRetentionAuthorizer(authority)

        assertIs<MemoryRetentionAuthorizationResult.Denied>(
            authorizer.authorize(request(MemoryRetentionClass.EPISODIC), principal)
        )
    }

    @Test
    fun exact_retention_scope_authorizes_without_minting_or_reclassifying() {
        val authority = configuredAuthority(MemoryRetentionClass.EPISODIC)
        val authorizer = MemoryRetentionAuthorizer(authority)
        val request = request(MemoryRetentionClass.EPISODIC)

        val authorized = assertIs<MemoryRetentionAuthorizationResult.Authorized>(
            authorizer.authorize(request, principal)
        )

        assertEquals(request, authorized.receipt.request)
        assertEquals(principal, authorized.receipt.principal)
        assertEquals(MemoryRetentionAuthorityContract.capability, authorized.receipt.capability)
        assertEquals(
            MemoryRetentionAuthorityContract.scopeFor(MemoryRetentionClass.EPISODIC),
            authorized.receipt.scope
        )
    }

    private fun configuredAuthority(grantedClass: MemoryRetentionClass): CapabilityAuthorityComposition {
        val authority = CapabilityAuthorityComposition(foundation())
        assertIs<CapabilityOwnershipResult.Registered>(
            authority.registerCapability(
                CapabilityDescriptor(
                    id = MemoryRetentionAuthorityContract.capability,
                    providerId = CapabilityProviderId("memory-retention")
                )
            )
        )
        assertIs<DirectAuthorityGrantOwnershipResult.Registered>(
            authority.registerDirectGrant(
                DirectAuthorityGrant(
                    principal = principal,
                    capability = MemoryRetentionAuthorityContract.capability,
                    scope = MemoryRetentionAuthorityContract.scopeFor(grantedClass)
                )
            )
        )
        return authority
    }

    private fun foundation(): FoundationComposition {
        val logs = InMemoryLogWriter()
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "memory-retention-contract" }
        )
    }

    private fun request(retentionClass: MemoryRetentionClass) = MemoryRetentionPruneRequest(
        snapshot = MemoryRecordSnapshot(
            record = MemoryRecord(
                id = MemoryRecordId("prune-contract"),
                sourceId = MemorySourceId("retention-contract"),
                content = "private memory content",
                createdAt = Instant.parse("2026-09-01T10:00:00Z")
            ),
            generation = MemoryGeneration(7)
        ),
        retentionClass = retentionClass,
        disposition = MemoryRetentionDisposition.RECORD_BUDGET_REJECTED
    )
}
