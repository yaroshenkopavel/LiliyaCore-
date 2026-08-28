package pro.liliya.core.trust

import java.lang.reflect.Modifier
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.identity.SelfGeneration
import pro.liliya.core.identity.SelfIdentityId
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrustCompositionContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val diagnostics: InMemoryDiagnosticSink,
        val composition: TrustComposition
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val diagnostics = InMemoryDiagnosticSink()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(diagnostics),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "trust-${sequence.incrementAndGet()}" }
        )
        return Fixture(logs, diagnostics, TrustComposition(foundation))
    }

    private fun anchor(
        id: String = "anchor-self",
        subject: TrustSubject = TrustSubject.Self(
            identityId = SelfIdentityId("self-liliya"),
            generation = SelfGeneration(7L)
        )
    ) = TrustAnchor(
        id = TrustAnchorId(id),
        subject = subject,
        provenance = TrustProvenance(
            sourceId = TrustSourceId("bootstrap"),
            sourceReference = TrustSourceReference("trust-config")
        ),
        createdAt = Instant.parse("2026-08-28T23:45:00Z")
    )

    @Test
    fun anchor_read_and_remove_are_owned_by_composition() {
        val f = fixture()
        val trustAnchor = anchor()
        val ownership = assertIs<TrustAnchorResult.Anchored>(
            f.composition.anchor(trustAnchor)
        ).ownership

        assertEquals(trustAnchor, f.composition.find(trustAnchor.id))
        assertEquals(ownership.generation, f.composition.inspect(trustAnchor.id)?.generation)
        assertTrue(f.composition.contains(trustAnchor.id))
        assertTrue(ownership.remove())
        assertNull(f.composition.find(trustAnchor.id))
        assertFalse(f.composition.contains(trustAnchor.id))
        assertEquals(
            listOf("TRUST_ANCHOR_REGISTERED", "TRUST_ANCHOR_REMOVED"),
            f.logs.snapshot().map { it.marker }
        )
    }

    @Test
    fun duplicate_anchor_is_rejected_without_replacement() {
        val f = fixture()
        val first = anchor()
        val second = anchor(subject = TrustSubject.Declared(TrustSubjectId("other-subject")))

        assertIs<TrustAnchorResult.Anchored>(f.composition.anchor(first))
        assertIs<TrustAnchorResult.Rejected>(f.composition.anchor(second))

        assertEquals(first, f.composition.find(first.id))
        assertTrue(f.diagnostics.snapshot().any { it.code == "TRUST_ANCHOR_REGISTRATION_REJECTED" })
    }

    @Test
    fun stale_ownership_cannot_remove_replacement_anchor() {
        val f = fixture()
        val stale = assertIs<TrustAnchorResult.Anchored>(
            f.composition.anchor(anchor())
        ).ownership
        assertTrue(stale.remove())

        val replacement = anchor(subject = TrustSubject.Declared(TrustSubjectId("replacement")))
        val current = assertIs<TrustAnchorResult.Anchored>(
            f.composition.anchor(replacement)
        ).ownership

        assertNotEquals(stale.generation, current.generation)
        assertFalse(stale.remove())
        assertEquals(replacement, f.composition.find(replacement.id))
        assertEquals(current.generation, f.composition.inspect(replacement.id)?.generation)
    }

    @Test
    fun anchor_and_remove_use_fresh_foundation_contexts() {
        val f = fixture()
        val ownership = assertIs<TrustAnchorResult.Anchored>(
            f.composition.anchor(anchor())
        ).ownership
        assertTrue(ownership.remove())

        val correlations = f.logs.snapshot().map { it.context.correlationId }
        assertEquals(2, correlations.size)
        assertNotEquals(correlations[0], correlations[1])
    }

    @Test
    fun self_subject_remains_structural_without_self_lookup_or_authority_semantics() {
        val f = fixture()
        val trustAnchor = anchor(
            subject = TrustSubject.Self(
                identityId = SelfIdentityId("self-not-installed"),
                generation = SelfGeneration(999L)
            )
        )

        assertIs<TrustAnchorResult.Anchored>(f.composition.anchor(trustAnchor))
        assertEquals(trustAnchor, f.composition.find(trustAnchor.id))

        val metadata = f.logs.snapshot().first().metadata
        assertEquals("self", metadata["trustSubjectType"])
        assertEquals("self-not-installed", metadata["selfIdentityId"])
        assertEquals("999", metadata["selfGeneration"])
        assertEquals("bootstrap", metadata["trustSourceId"])
        assertFalse(metadata.keys.any { it.contains("authorityPrincipal", ignoreCase = true) })
        assertFalse(metadata.keys.any { it.contains("capability", ignoreCase = true) })
        assertFalse(metadata.keys.any { it.contains("verified", ignoreCase = true) })
        assertFalse(metadata.keys.any { it.contains("truth", ignoreCase = true) })
        assertFalse(metadata.keys.any { it.contains("confidence", ignoreCase = true) })
    }

    @Test
    fun declared_subject_remains_explicit_non_transitive_attribution() {
        val f = fixture()
        val trustAnchor = anchor(
            id = "anchor-declared",
            subject = TrustSubject.Declared(TrustSubjectId("declared-subject"))
        )

        assertIs<TrustAnchorResult.Anchored>(f.composition.anchor(trustAnchor))

        val metadata = f.logs.snapshot().first().metadata
        assertEquals("declared", metadata["trustSubjectType"])
        assertEquals("declared-subject", metadata["trustSubjectId"])
        assertFalse(metadata.keys.any { it.contains("delegat", ignoreCase = true) })
        assertFalse(metadata.keys.any { it.contains("transitive", ignoreCase = true) })
        assertFalse(metadata.keys.any { it.contains("permission", ignoreCase = true) })
    }

    @Test
    fun public_api_does_not_expose_raw_store_or_registration() {
        val forbidden = setOf(
            TrustAnchorStore::class.java,
            TrustAnchorRegistration::class.java
        )
        val exposed = TrustComposition::class.java.methods.filter { method ->
            Modifier.isPublic(method.modifiers) && method.returnType in forbidden
        }
        assertTrue(exposed.isEmpty(), "trust API must not expose raw store internals: $exposed")
    }
}
