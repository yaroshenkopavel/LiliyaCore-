package pro.liliya.core.identity

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.LogContextPropagation
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.knowledge.KnowledgeGeneration
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.observability.CoreObservability
import pro.liliya.core.observability.LoggerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelfStoreContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val diagnostics: InMemoryDiagnosticSink,
        val store: SelfStore
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val diagnostics = InMemoryDiagnosticSink()
        val observability = CoreObservability(
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            diagnostics = DiagnosticRecorder(diagnostics)
        )
        return Fixture(logs, diagnostics, SelfStore(observability))
    }

    private fun identity(
        id: String = "self-liliya",
        name: String = "Liliya",
        knowledgeGeneration: Long = 3L
    ) = SelfIdentity(
        id = SelfIdentityId(id),
        name = SelfName(name),
        origin = SelfOrigin.Knowledge(
            itemId = KnowledgeItemId("knowledge-self-origin"),
            generation = KnowledgeGeneration(knowledgeGeneration)
        ),
        createdAt = Instant.parse("2026-08-28T22:00:00Z")
    )

    @Test
    fun exactly_one_current_self_is_registered_and_observable() {
        val f = fixture()
        val self = identity()
        val registration = assertIs<SelfRegistrationResult.Registered>(
            f.store.register(self, context("register"))
        ).registration

        assertEquals(self, f.store.current())
        assertEquals(registration.generation, f.store.inspect()?.generation)
        assertTrue(f.store.isPresent())

        val log = assertNotNull(f.logs.snapshot().lastOrNull { it.marker == "SELF_REGISTERED" })
        assertEquals("self-liliya", log.metadata["selfIdentityId"])
        assertEquals(registration.generation.value.toString(), log.metadata["selfGeneration"])
        assertEquals("knowledge", log.metadata["selfOriginType"])
        assertEquals("knowledge-self-origin", log.metadata["knowledgeItemId"])
        assertEquals("3", log.metadata["knowledgeGeneration"])
        assertFalse(log.metadata.containsKey("selfName"))
        assertFalse(log.metadata.keys.any { it.contains("personality", ignoreCase = true) })
        assertFalse(log.metadata.keys.any { it.contains("trust", ignoreCase = true) })
    }

    @Test
    fun second_self_is_rejected_even_when_identity_id_is_different() {
        val f = fixture()
        val first = identity()
        val second = identity(id = "self-other", name = "Other")

        assertIs<SelfRegistrationResult.Registered>(f.store.register(first, context("first")))
        assertIs<SelfRegistrationResult.Rejected>(f.store.register(second, context("second")))

        assertEquals(first, f.store.current())
        assertTrue(f.diagnostics.snapshot().any { it.code == "SELF_REGISTRATION_REJECTED" })
        assertTrue(f.logs.snapshot().any { it.marker == "SELF_REGISTRATION_REJECTED" })
    }

    @Test
    fun stale_registration_cannot_remove_replacement_self() {
        val f = fixture()
        val first = identity()
        val stale = assertIs<SelfRegistrationResult.Registered>(
            f.store.register(first, context("first"))
        ).registration
        assertTrue(stale.remove(context("remove-first")))

        val replacement = identity(name = "Liliya")
        val current = assertIs<SelfRegistrationResult.Registered>(
            f.store.register(replacement, context("replacement"))
        ).registration

        assertNotEquals(stale.generation, current.generation)
        assertFalse(stale.remove(context("stale-remove")))
        assertEquals(replacement, f.store.current())
        assertEquals(current.generation, f.store.inspect()?.generation)
        assertTrue(f.diagnostics.snapshot().any { it.code == "SELF_REMOVAL_REJECTED" })
    }

    @Test
    fun remove_clears_identity_and_generation_together() {
        val f = fixture()
        val registration = assertIs<SelfRegistrationResult.Registered>(
            f.store.register(identity(), context("register"))
        ).registration

        assertTrue(registration.remove(context("remove")))

        assertNull(f.store.current())
        assertNull(f.store.inspect())
        assertFalse(f.store.isPresent())
    }

    @Test
    fun declared_origin_is_attribution_only_and_preserved() {
        val f = fixture()
        val self = SelfIdentity(
            id = SelfIdentityId("self-declared"),
            name = SelfName("Liliya"),
            origin = SelfOrigin.Declared(
                sourceId = SelfSourceId("bootstrap"),
                sourceReference = SelfSourceReference("identity-config")
            ),
            createdAt = Instant.parse("2026-08-28T22:01:00Z")
        )

        assertIs<SelfRegistrationResult.Registered>(f.store.register(self, context("declared")))

        val log = assertNotNull(f.logs.snapshot().lastOrNull { it.marker == "SELF_REGISTERED" })
        assertEquals("declared", log.metadata["selfOriginType"])
        assertEquals("bootstrap", log.metadata["selfSourceId"])
        assertEquals("identity-config", log.metadata["selfSourceReference"])
        assertFalse(log.metadata.keys.any { it.contains("truth", ignoreCase = true) })
        assertFalse(log.metadata.keys.any { it.contains("confidence", ignoreCase = true) })
        assertFalse(log.metadata.keys.any { it.contains("authority", ignoreCase = true) })
    }

    @Test
    fun concurrent_registration_has_exactly_one_current_self() {
        val f = fixture()
        val attempts = 32
        val executor = Executors.newFixedThreadPool(attempts)
        val ready = CountDownLatch(attempts)
        val start = CountDownLatch(1)
        val done = CountDownLatch(attempts)
        val winners = AtomicInteger(0)

        try {
            repeat(attempts) { index ->
                executor.submit {
                    try {
                        ready.countDown()
                        start.await()
                        if (
                            f.store.register(
                                identity(id = "self-$index", name = "Liliya-$index"),
                                context("candidate-$index")
                            ) is SelfRegistrationResult.Registered
                        ) {
                            winners.incrementAndGet()
                        }
                    } finally {
                        done.countDown()
                    }
                }
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertEquals(1, winners.get())
            assertTrue(f.store.isPresent())
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun structural_identity_values_reject_blank_or_non_positive_input() {
        assertFails { SelfIdentityId(" ") }
        assertFails { SelfName(" ") }
        assertFails { SelfSourceId(" ") }
        assertFails { SelfSourceReference(" ") }
        assertFails { SelfGeneration(0L) }
    }

    private fun context(operation: String) = LogContextPropagation.root(
        correlationIds = CorrelationIdGenerator { "self-$operation" },
        operation = operation,
        component = "SelfStore"
    )

    private inline fun assertFails(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException")
    }
}
