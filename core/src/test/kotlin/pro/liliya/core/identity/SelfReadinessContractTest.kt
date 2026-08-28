package pro.liliya.core.identity

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
import kotlin.test.assertIs

class SelfReadinessContractTest {
    private fun composition(): SelfComposition {
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context ->
                StructuredLogger(context, InMemoryLogWriter())
            },
            correlationIds = CorrelationIdGenerator {
                "self-readiness-${sequence.incrementAndGet()}"
            }
        )
        return SelfComposition(foundation)
    }

    @Test
    fun knowledge_origin_is_structural_reference_and_does_not_require_knowledge_lookup() {
        val identity = SelfIdentity(
            id = SelfIdentityId("self-unverified-knowledge-origin"),
            name = SelfName("Liliya"),
            origin = SelfOrigin.Knowledge(
                itemId = KnowledgeItemId("knowledge-not-present-in-any-composition"),
                generation = KnowledgeGeneration(999L)
            ),
            createdAt = Instant.parse("2042-01-02T03:04:05Z")
        )
        val composition = composition()

        assertIs<SelfInstallResult.Installed>(composition.install(identity))
        assertEquals(identity, composition.current())
    }

    @Test
    fun created_at_remains_caller_supplied_identity_value() {
        val callerCreatedAt = Instant.parse("2099-12-31T23:59:59Z")
        val identity = SelfIdentity(
            id = SelfIdentityId("self-caller-time"),
            name = SelfName("Liliya"),
            origin = SelfOrigin.Declared(SelfSourceId("caller")),
            createdAt = callerCreatedAt
        )
        val composition = composition()

        val ownership = assertIs<SelfInstallResult.Installed>(
            composition.install(identity)
        ).ownership

        assertEquals(callerCreatedAt, composition.current()?.createdAt)
        assertEquals(callerCreatedAt, composition.inspect()?.identity?.createdAt)
        assertEquals(callerCreatedAt, ownership.identity.createdAt)
    }
}
