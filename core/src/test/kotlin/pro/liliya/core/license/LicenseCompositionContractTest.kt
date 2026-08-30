package pro.liliya.core.license

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider

class LicenseCompositionContractTest {
    private val issuedAt = Instant.parse("2026-08-30T17:10:00Z")

    private fun foundation(): FoundationComposition {
        val sequence = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, InMemoryLogWriter()) },
            correlationIds = CorrelationIdGenerator { "license-${sequence.incrementAndGet()}" }
        )
    }

    private fun entitlement(
        id: String = "license-1",
        issued: Instant = issuedAt,
        features: Set<LicenseFeature> = setOf(LicenseFeature("model.local"))
    ) = LicenseEntitlement(
        id = LicenseId(id),
        subject = LicenseSubject("subject-private-reference"),
        productId = LicenseProductId("liliya-pro"),
        features = features,
        version = LicenseVersion(1),
        signingKeyId = LicenseKeyId("license-signing-2026"),
        issuedAt = issued,
        notBefore = issued,
        expiresAt = issued.plusSeconds(86_400),
        offlineLeaseUntil = issued.plusSeconds(43_200),
        revocationEpoch = LicenseRevocationEpoch(4),
        replaySequence = LicenseReplaySequence(19)
    )

    @Test
    fun models_validate_structural_invariants_and_defensively_copy_features() {
        val mutable = linkedSetOf(LicenseFeature("model.local"), LicenseFeature("memory.protected"))
        val entitlement = entitlement(features = mutable)
        mutable.clear()

        assertEquals(
            setOf(LicenseFeature("model.local"), LicenseFeature("memory.protected")),
            entitlement.features
        )
        assertFalse(entitlement.toString().contains("subject-private-reference"))
    }

    @Test
    fun register_assigns_exact_positive_generation_and_duplicate_is_rejected_without_consuming_generation() {
        val composition = LicenseComposition(foundation())
        val first = entitlement(id = "first")
        val duplicate = entitlement(id = "first")
        val second = entitlement(id = "second", issued = issuedAt.plusSeconds(1))

        val firstOwnership = assertIs<LicenseRegisterResult.Registered>(composition.register(first)).ownership
        assertEquals(1L, firstOwnership.generation.value)
        assertIs<LicenseRegisterResult.Rejected>(composition.register(duplicate))
        val secondOwnership = assertIs<LicenseRegisterResult.Registered>(composition.register(second)).ownership
        assertEquals(2L, secondOwnership.generation.value)
    }

    @Test
    fun stale_owner_cannot_remove_newer_same_id_generation() {
        val composition = LicenseComposition(foundation())
        val first = entitlement()
        val firstOwnership = assertIs<LicenseRegisterResult.Registered>(composition.register(first)).ownership
        assertTrue(firstOwnership.remove())

        val replacement = entitlement()
        val replacementOwnership = assertIs<LicenseRegisterResult.Registered>(composition.register(replacement)).ownership
        assertTrue(replacementOwnership.generation.value > firstOwnership.generation.value)

        assertFalse(firstOwnership.remove())
        assertEquals(replacement, composition.find(replacement.id))
        assertEquals(replacementOwnership.generation, composition.inspect(replacement.id)?.generation)
    }

    @Test
    fun ownership_is_one_shot_and_removal_clears_only_exact_live_state() {
        val composition = LicenseComposition(foundation())
        val license = entitlement()
        val ownership = assertIs<LicenseRegisterResult.Registered>(composition.register(license)).ownership

        assertTrue(ownership.remove())
        assertFalse(ownership.remove())
        assertNull(composition.find(license.id))
    }

    @Test
    fun snapshots_are_deterministic_and_compositions_are_isolated() {
        val firstComposition = LicenseComposition(foundation())
        val secondComposition = LicenseComposition(foundation())
        val laterIdFirst = entitlement(id = "z-license", issued = issuedAt.plusSeconds(2))
        val earlierB = entitlement(id = "b-license", issued = issuedAt)
        val earlierA = entitlement(id = "a-license", issued = issuedAt)

        assertIs<LicenseRegisterResult.Registered>(firstComposition.register(laterIdFirst))
        assertIs<LicenseRegisterResult.Registered>(firstComposition.register(earlierB))
        assertIs<LicenseRegisterResult.Registered>(firstComposition.register(earlierA))

        assertEquals(
            listOf("a-license", "b-license", "z-license"),
            firstComposition.snapshot().map { it.id.value }
        )
        assertTrue(secondComposition.snapshot().isEmpty())
    }
}
