package pro.liliya.core.trust

import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.identity.SelfGeneration
import pro.liliya.core.identity.SelfIdentityId
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.LogContextPropagation
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.CoreObservability
import pro.liliya.core.observability.LoggerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrustAnchorStoreContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val diagnostics: InMemoryDiagnosticSink,
        val store: TrustAnchorStore,
        val sequence: AtomicInteger
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val diagnostics = InMemoryDiagnosticSink()
        val sequence = AtomicInteger(0)
        val observability = CoreObservability(
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            diagnostics = DiagnosticRecorder(diagnostics)
        )
        return Fixture(logs, diagnostics, TrustAnchorStore(observability), sequence)
    }

    private fun context(f: Fixture) = LogContextPropagation.root(
        module = "CORE",
        component = "Trust",
        operation = "testTrustAnchor",
        generator = CorrelationIdGenerator { "trust-${f.sequence.incrementAndGet()}" }
    )

    private fun anchor(
        id: String = "anchor-self",
        subject: TrustSubject = TrustSubject.Self(
            identityId = SelfIdentityId("self-liliya"),
            generation = SelfGeneration(7L)
        ),
        createdAt: Instant = Instant.parse("2026-08-28T23:30:00Z")
    ) = TrustAnchor(
        id = TrustAnchorId(id),
        subject = subject,
        provenance = TrustProvenance(
            sourceId = TrustSourceId("bootstrap"),
            sourceReference = TrustSourceReference("trust-config")
        ),
        createdAt = createdAt
    )

    @Test
    fun register_read_and_remove_use_exact_ownership() {
        val f = fixture()
        val anchor = anchor()
        val registration = assertIs<TrustAnchorRegistrationResult.Registered>(
            f.store.register(anchor, context(f))
        ).registration

        assertEquals(anchor, f.store.find(anchor.id))
        assertEquals(registration.generation, f.store.inspect(anchor.id)?.generation)
        assertTrue(f.store.contains(anchor.id))
        assertTrue(registration.remove(context(f)))
        assertNull(f.store.find(anchor.id))
        assertFalse(f.store.contains(anchor.id))
        assertEquals(
            listOf("TRUST_ANCHOR_REGISTERED", "TRUST_ANCHOR_REMOVED"),
            f.logs.snapshot().map { it.marker }
        )
    }

    @Test
    fun duplicate_anchor_id_is_rejected_without_replacement() {
        val f = fixture()
        val first = anchor()
        val second = anchor(subject = TrustSubject.Declared(TrustSubjectId("other-subject")))

        assertIs<TrustAnchorRegistrationResult.Registered>(f.store.register(first, context(f)))
        assertIs<TrustAnchorRegistrationResult.Rejected>(f.store.register(second, context(f)))

        assertEquals(first, f.store.find(first.id))
        assertTrue(f.diagnostics.snapshot().any { it.code == "TRUST_ANCHOR_REGISTRATION_REJECTED" })
    }

    @Test
    fun stale_registration_cannot_remove_same_id_replacement() {
        val f = fixture()
        val stale = assertIs<TrustAnchorRegistrationResult.Registered>(
            f.store.register(anchor(), context(f))
        ).registration
        assertTrue(stale.remove(context(f)))

        val replacement = anchor(subject = TrustSubject.Declared(TrustSubjectId("replacement")))
        val current = assertIs<TrustAnchorRegistrationResult.Registered>(
            f.store.register(replacement, context(f))
        ).registration

        assertNotEquals(stale.generation, current.generation)
        assertFalse(stale.remove(context(f)))
        assertEquals(replacement, f.store.find(replacement.id))
        assertEquals(current.generation, f.store.inspect(replacement.id)?.generation)
        assertTrue(f.diagnostics.snapshot().any { it.code == "TRUST_ANCHOR_REMOVAL_REJECTED" })
    }

    @Test
    fun self_subject_is_structural_reference_without_self_lookup() {
        val f = fixture()
        val anchor = anchor(
            subject = TrustSubject.Self(
                identityId = SelfIdentityId("self-not-present-in-any-composition"),
                generation = SelfGeneration(999L)
            )
        )

        assertIs<TrustAnchorRegistrationResult.Registered>(f.store.register(anchor, context(f)))
        assertEquals(anchor, f.store.find(anchor.id))
    }

    @Test
    fun anchor_metadata_does_not_create_authority_or_truth_semantics() {
        val f = fixture()
        assertIs<TrustAnchorRegistrationResult.Registered>(
            f.store.register(anchor(), context(f))
        )

        val metadata = f.logs.snapshot().first().metadata
        assertEquals("anchor-self", metadata["trustAnchorId"])
        assertEquals("self", metadata["trustSubjectType"])
        assertEquals("self-liliya", metadata["selfIdentityId"])
        assertEquals("7", metadata["selfGeneration"])
        assertFalse(metadata.keys.any { it.contains("authorityPrincipal", ignoreCase = true) })
        assertFalse(metadata.keys.any { it.contains("capability", ignoreCase = true) })
        assertFalse(metadata.keys.any { it.contains("truth", ignoreCase = true) })
        assertFalse(metadata.keys.any { it.contains("confidence", ignoreCase = true) })
    }

    @Test
    fun snapshots_are_deterministic_by_caller_created_at_then_id() {
        val f = fixture()
        val later = anchor(
            id = "anchor-b",
            createdAt = Instant.parse("2099-01-01T00:00:00Z")
        )
        val earlier = anchor(
            id = "anchor-z",
            createdAt = Instant.parse("2001-01-01T00:00:00Z")
        )
        val sameTimeEarlierId = anchor(
            id = "anchor-a",
            createdAt = Instant.parse("2099-01-01T00:00:00Z")
        )

        assertIs<TrustAnchorRegistrationResult.Registered>(f.store.register(later, context(f)))
        assertIs<TrustAnchorRegistrationResult.Registered>(f.store.register(earlier, context(f)))
        assertIs<TrustAnchorRegistrationResult.Registered>(f.store.register(sameTimeEarlierId, context(f)))

        assertEquals(
            listOf("anchor-z", "anchor-a", "anchor-b"),
            f.store.snapshot().map { it.id.value }
        )
    }

    @Test
    fun concurrent_same_id_registration_has_exactly_one_winner() {
        val f = fixture()
        val workers = 32
        val executor = Executors.newFixedThreadPool(workers)
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val done = CountDownLatch(workers)
        val results = ConcurrentLinkedQueue<TrustAnchorRegistrationResult>()

        repeat(workers) { index ->
            executor.execute {
                ready.countDown()
                start.await()
                try {
                    results += f.store.register(
                        anchor(subject = TrustSubject.Declared(TrustSubjectId("subject-$index"))),
                        context(f)
                    )
                } finally {
                    done.countDown()
                }
            }
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        executor.shutdownNow()

        assertEquals(1, results.count { it is TrustAnchorRegistrationResult.Registered })
        assertEquals(workers - 1, results.count { it is TrustAnchorRegistrationResult.Rejected })
        assertTrue(f.store.contains(TrustAnchorId("anchor-self")))
    }
}
