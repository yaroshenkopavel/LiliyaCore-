package pro.liliya.core.learning

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.reflection.ReflectionGeneration
import pro.liliya.core.reflection.ReflectionRecordId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LearningCompositionContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val composition: LearningComposition
    )

    private fun fixture(prefix: String = "learning-composition"): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "$prefix-${sequence.incrementAndGet()}" }
        )
        return Fixture(logs, LearningComposition(foundation))
    }

    private fun candidate(
        id: String = "candidate-1",
        proposal: String = "caller learning proposal",
        origin: LearningOrigin = LearningOrigin.Declared(
            LearningSourceId("caller"),
            LearningSourceReference("composition-contract")
        )
    ) = LearningCandidate(
        id = LearningCandidateId(id),
        origin = origin,
        proposal = proposal,
        createdAt = Instant.parse("2026-08-29T06:00:00Z")
    )

    @Test
    fun install_read_and_remove_are_owned_by_composition() {
        val f = fixture()
        val c = candidate()
        val installed = assertIs<LearningInstallResult.Installed>(f.composition.install(c))

        assertEquals(c, f.composition.find(c.id))
        assertEquals(installed.ownership.generation, f.composition.inspect(c.id)?.generation)
        assertTrue(installed.ownership.remove())
        assertNull(f.composition.find(c.id))
    }

    @Test
    fun duplicate_candidate_is_rejected_without_replacement() {
        val f = fixture()
        val first = candidate(proposal = "first")
        val second = candidate(proposal = "second")

        assertIs<LearningInstallResult.Installed>(f.composition.install(first))
        assertIs<LearningInstallResult.Rejected>(f.composition.install(second))
        assertEquals(first, f.composition.find(first.id))
    }

    @Test
    fun stale_ownership_cannot_remove_replacement_candidate() {
        val f = fixture()
        val stale = assertIs<LearningInstallResult.Installed>(f.composition.install(candidate())).ownership
        assertTrue(stale.remove())

        val replacement = candidate(proposal = "replacement")
        val current = assertIs<LearningInstallResult.Installed>(f.composition.install(replacement)).ownership
        assertNotEquals(stale.generation, current.generation)
        assertFalse(stale.remove())
        assertEquals(replacement, f.composition.find(replacement.id))
    }

    @Test
    fun install_and_remove_use_fresh_foundation_contexts() {
        val f = fixture("fresh")
        val ownership = assertIs<LearningInstallResult.Installed>(f.composition.install(candidate())).ownership
        assertTrue(ownership.remove())

        val correlations = f.logs.snapshot().map { event -> event.context.correlationId }.distinct()
        assertTrue(correlations.size >= 2)
    }

    @Test
    fun reflection_origin_remains_structural_and_proposal_stays_out_of_lifecycle_metadata() {
        val f = fixture()
        val secret = "private-learning-proposal"
        val c = candidate(
            proposal = secret,
            origin = LearningOrigin.Reflection(
                ReflectionRecordId("reflection-not-installed"),
                ReflectionGeneration(999L)
            )
        )

        assertIs<LearningInstallResult.Installed>(f.composition.install(c))

        val events = f.logs.snapshot()
        assertFalse(events.any { event -> event.metadata.values.any { value -> value == secret } })
        assertTrue(events.any { event -> event.metadata["reflectionRecordId"] == "reflection-not-installed" })
        assertTrue(events.any { event -> event.metadata["reflectionGeneration"] == "999" })
        assertFalse(events.any { event ->
            event.metadata.keys.any { key ->
                key.contains("accepted", ignoreCase = true) ||
                    key.contains("approved", ignoreCase = true) ||
                    key.contains("applied", ignoreCase = true) ||
                    key.contains("truth", ignoreCase = true) ||
                    key.contains("confidence", ignoreCase = true) ||
                    key.contains("trust", ignoreCase = true) ||
                    key.contains("authority", ignoreCase = true) ||
                    key.contains("execution", ignoreCase = true)
            }
        })
    }

    @Test
    fun public_api_does_not_expose_raw_store_or_registration() {
        val exposedTypes = LearningComposition::class.java.methods.flatMap { method ->
            listOf(method.returnType) + method.parameterTypes.toList()
        }

        assertFalse(exposedTypes.any { it.name.endsWith("LearningCandidateStore") })
        assertFalse(exposedTypes.any { it.name.endsWith("LearningCandidateRegistration") })
    }
}
