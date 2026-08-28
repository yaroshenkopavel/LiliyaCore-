package pro.liliya.core.trust

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TrustReadinessContractTest {
    private fun composition(prefix: String): TrustComposition {
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, InMemoryLogWriter()) },
            correlationIds = CorrelationIdGenerator { "$prefix-${sequence.incrementAndGet()}" }
        )
        return TrustComposition(foundation)
    }

    private fun anchor(
        id: String,
        subjectId: String,
        createdAt: Instant
    ) = TrustAnchor(
        id = TrustAnchorId(id),
        subject = TrustSubject.Declared(TrustSubjectId(subjectId)),
        provenance = TrustProvenance(
            sourceId = TrustSourceId("caller"),
            sourceReference = TrustSourceReference("readiness")
        ),
        createdAt = createdAt
    )

    @Test
    fun created_at_is_caller_supplied_and_preserved_unchanged() {
        val composition = composition("created-at")
        val callerTime = Instant.parse("2042-01-02T03:04:05Z")
        val trustAnchor = anchor(
            id = "anchor-created-at",
            subjectId = "subject-created-at",
            createdAt = callerTime
        )

        val ownership = assertIs<TrustAnchorResult.Anchored>(
            composition.anchor(trustAnchor)
        ).ownership

        assertEquals(callerTime, ownership.anchor.createdAt)
        assertEquals(callerTime, composition.find(trustAnchor.id)?.createdAt)
        assertEquals(callerTime, composition.inspect(trustAnchor.id)?.anchor?.createdAt)
    }

    @Test
    fun trust_compositions_are_isolated_even_for_the_same_anchor_id() {
        val first = composition("first")
        val second = composition("second")
        val firstAnchor = anchor(
            id = "shared-anchor",
            subjectId = "first-subject",
            createdAt = Instant.parse("2026-08-28T20:00:00Z")
        )
        val secondAnchor = anchor(
            id = "shared-anchor",
            subjectId = "second-subject",
            createdAt = Instant.parse("2026-08-28T21:00:00Z")
        )

        val firstOwnership = assertIs<TrustAnchorResult.Anchored>(first.anchor(firstAnchor)).ownership
        val secondOwnership = assertIs<TrustAnchorResult.Anchored>(second.anchor(secondAnchor)).ownership

        assertEquals(firstAnchor, first.find(firstAnchor.id))
        assertEquals(secondAnchor, second.find(secondAnchor.id))
        assertEquals(firstOwnership.generation.value, secondOwnership.generation.value)

        assertEquals(true, firstOwnership.remove())
        assertNull(first.find(firstAnchor.id))
        assertNotNull(second.find(secondAnchor.id))
    }

    @Test
    fun equal_generation_numbers_across_compositions_do_not_create_shared_ownership() {
        val first = composition("generation-first")
        val second = composition("generation-second")
        val firstAnchor = anchor(
            id = "anchor-first",
            subjectId = "subject-first",
            createdAt = Instant.parse("2026-08-28T20:10:00Z")
        )
        val secondAnchor = anchor(
            id = "anchor-second",
            subjectId = "subject-second",
            createdAt = Instant.parse("2026-08-28T20:11:00Z")
        )

        val firstOwnership = assertIs<TrustAnchorResult.Anchored>(first.anchor(firstAnchor)).ownership
        val secondOwnership = assertIs<TrustAnchorResult.Anchored>(second.anchor(secondAnchor)).ownership

        assertEquals(firstOwnership.generation.value, secondOwnership.generation.value)
        assertEquals(true, firstOwnership.remove())
        assertNotNull(second.find(secondAnchor.id))
        assertEquals(secondOwnership.generation, second.inspect(secondAnchor.id)?.generation)
    }

    @Test
    fun anchoring_one_subject_does_not_imply_trust_for_another_subject() {
        val composition = composition("non-transitive")
        val trusted = anchor(
            id = "anchor-a",
            subjectId = "subject-a",
            createdAt = Instant.parse("2026-08-28T20:20:00Z")
        )

        assertIs<TrustAnchorResult.Anchored>(composition.anchor(trusted))

        assertNotNull(composition.find(TrustAnchorId("anchor-a")))
        assertNull(composition.find(TrustAnchorId("anchor-b")))
        assertFalse(composition.contains(TrustAnchorId("anchor-b")))
    }
}
