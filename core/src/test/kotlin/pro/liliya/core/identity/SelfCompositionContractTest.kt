package pro.liliya.core.identity

import java.lang.reflect.Modifier
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.knowledge.KnowledgeGeneration
import pro.liliya.core.knowledge.KnowledgeItemId
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

class SelfCompositionContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val diagnostics: InMemoryDiagnosticSink,
        val composition: SelfComposition
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val diagnostics = InMemoryDiagnosticSink()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(diagnostics),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "self-${sequence.incrementAndGet()}" }
        )
        return Fixture(logs, diagnostics, SelfComposition(foundation))
    }

    private fun identity(
        id: String = "self-liliya",
        name: String = "Liliya",
        knowledgeGeneration: Long = 7L
    ) = SelfIdentity(
        id = SelfIdentityId(id),
        name = SelfName(name),
        origin = SelfOrigin.Knowledge(
            itemId = KnowledgeItemId("knowledge-self"),
            generation = KnowledgeGeneration(knowledgeGeneration)
        ),
        createdAt = Instant.parse("2026-08-28T23:00:00Z")
    )

    @Test
    fun install_read_and_remove_are_owned_by_composition() {
        val f = fixture()
        val self = identity()
        val ownership = assertIs<SelfInstallResult.Installed>(
            f.composition.install(self)
        ).ownership

        assertEquals(self, f.composition.current())
        assertEquals(ownership.generation, f.composition.inspect()?.generation)
        assertTrue(f.composition.isInstalled())
        assertTrue(ownership.remove())
        assertNull(f.composition.current())
        assertNull(f.composition.inspect())
        assertFalse(f.composition.isInstalled())
        assertEquals(
            listOf("SELF_REGISTERED", "SELF_REMOVED"),
            f.logs.snapshot().map { it.marker }
        )
    }

    @Test
    fun second_install_is_rejected_even_for_different_identity_id() {
        val f = fixture()
        val first = identity()
        val second = identity(id = "self-other", name = "Other")

        assertIs<SelfInstallResult.Installed>(f.composition.install(first))
        assertIs<SelfInstallResult.Rejected>(f.composition.install(second))

        assertEquals(first, f.composition.current())
        assertTrue(f.diagnostics.snapshot().any { it.code == "SELF_REGISTRATION_REJECTED" })
    }

    @Test
    fun stale_ownership_cannot_remove_replacement_self() {
        val f = fixture()
        val stale = assertIs<SelfInstallResult.Installed>(
            f.composition.install(identity())
        ).ownership
        assertTrue(stale.remove())

        val replacement = identity(name = "Liliya")
        val current = assertIs<SelfInstallResult.Installed>(
            f.composition.install(replacement)
        ).ownership

        assertNotEquals(stale.generation, current.generation)
        assertFalse(stale.remove())
        assertEquals(replacement, f.composition.current())
        assertEquals(current.generation, f.composition.inspect()?.generation)
    }

    @Test
    fun install_and_remove_use_fresh_foundation_contexts() {
        val f = fixture()
        val ownership = assertIs<SelfInstallResult.Installed>(
            f.composition.install(identity())
        ).ownership
        assertTrue(ownership.remove())

        val correlations = f.logs.snapshot().map { it.context.correlationId }
        assertEquals(2, correlations.size)
        assertNotEquals(correlations[0], correlations[1])
    }

    @Test
    fun composition_metadata_preserves_origin_without_name_or_semantic_trust() {
        val f = fixture()
        assertIs<SelfInstallResult.Installed>(f.composition.install(identity()))

        val metadata = f.logs.snapshot().first().metadata
        assertEquals("self-liliya", metadata["selfIdentityId"])
        assertEquals("knowledge", metadata["selfOriginType"])
        assertEquals("knowledge-self", metadata["knowledgeItemId"])
        assertEquals("7", metadata["knowledgeGeneration"])
        assertFalse(metadata.containsKey("selfName"))
        assertFalse(metadata.keys.any { it.contains("personality", ignoreCase = true) })
        assertFalse(metadata.keys.any { it.contains("trust", ignoreCase = true) })
        assertFalse(metadata.keys.any { it.contains("confidence", ignoreCase = true) })
        assertFalse(metadata.keys.any { it.contains("truth", ignoreCase = true) })
    }

    @Test
    fun declared_origin_remains_attribution_only_through_composition() {
        val f = fixture()
        val self = SelfIdentity(
            id = SelfIdentityId("self-declared"),
            name = SelfName("Liliya"),
            origin = SelfOrigin.Declared(
                sourceId = SelfSourceId("bootstrap"),
                sourceReference = SelfSourceReference("identity-config")
            ),
            createdAt = Instant.parse("2026-08-28T23:01:00Z")
        )

        assertIs<SelfInstallResult.Installed>(f.composition.install(self))

        val metadata = f.logs.snapshot().first().metadata
        assertEquals("declared", metadata["selfOriginType"])
        assertEquals("bootstrap", metadata["selfSourceId"])
        assertEquals("identity-config", metadata["selfSourceReference"])
        assertFalse(metadata.keys.any { it.contains("authority", ignoreCase = true) })
        assertFalse(metadata.keys.any { it.contains("verified", ignoreCase = true) })
    }

    @Test
    fun public_api_does_not_expose_raw_store_or_registration() {
        val forbidden = setOf(
            SelfStore::class.java,
            SelfRegistration::class.java
        )
        val exposed = SelfComposition::class.java.methods.filter { method ->
            Modifier.isPublic(method.modifiers) && method.returnType in forbidden
        }
        assertTrue(exposed.isEmpty(), "self API must not expose raw store internals: $exposed")
    }
}
