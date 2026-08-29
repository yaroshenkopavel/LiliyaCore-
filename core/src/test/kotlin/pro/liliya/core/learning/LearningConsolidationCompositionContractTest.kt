package pro.liliya.core.learning

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryProvenance
import pro.liliya.core.memory.MemoryRecord
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.memory.MemorySourceId
import pro.liliya.core.observability.LoggerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LearningConsolidationCompositionContractTest {
    private data class Fixture(
        val foundation: FoundationComposition,
        val mutations: LearningApplicationMutationComposition,
        val consolidation: LearningConsolidationComposition,
        val logs: InMemoryLogWriter,
        val diagnostics: InMemoryDiagnosticSink
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val diagnostics = InMemoryDiagnosticSink()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(diagnostics),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "consolidation-${sequence.incrementAndGet()}" }
        )
        val mutations = LearningApplicationMutationComposition(foundation)
        return Fixture(
            foundation = foundation,
            mutations = mutations,
            consolidation = LearningConsolidationComposition(foundation, mutations),
            logs = logs,
            diagnostics = diagnostics
        )
    }

    private fun completedReceipt(
        f: Fixture,
        mutationId: String = "mutation-source",
        idempotencyKey: String = "idem-source",
        memoryId: String = "memory-source"
    ): LearningApplicationMutationApplicationReceipt {
        val plan = LearningApplicationMutationPlan(
            id = LearningApplicationMutationId(mutationId),
            application = LearningApplicationIntentReference(
                LearningApplicationId("application-source"),
                LearningApplicationGeneration(1L)
            ),
            principal = AuthorityPrincipal("learning-controller"),
            target = LearningApplicationTarget.MEMORY,
            idempotencyKey = LearningApplicationIdempotencyKey(idempotencyKey),
            payload = LearningApplicationMutationPayload.Memory(
                MemoryRecord(
                    id = MemoryRecordId(memoryId),
                    provenance = MemoryProvenance(MemorySourceId("learning-application")),
                    content = "source payload must stay private",
                    createdAt = Instant.parse("2026-08-29T11:00:00Z")
                )
            ),
            createdAt = Instant.parse("2026-08-29T11:01:00Z")
        )
        val ownership = assertIs<LearningApplicationMutationPrepareResult.Prepared>(
            f.mutations.prepare(plan)
        ).ownership
        val claim = assertIs<LearningApplicationMutationClaimResult.Claimed>(
            f.mutations.claim(
                LearningApplicationMutationReference(ownership.plan.id, ownership.generation)
            )
        ).claim
        val receipt = LearningApplicationMutationApplicationReceipt(
            mutation = claim.reference,
            target = LearningApplicationTarget.MEMORY,
            downstream = LearningApplicationDownstreamReference.Memory(
                recordId = MemoryRecordId(memoryId),
                generation = MemoryGeneration(1L)
            )
        )
        assertTrue(claim.complete(receipt))
        return receipt
    }

    private fun proposal(
        receipt: LearningApplicationMutationApplicationReceipt,
        id: String = "consolidation-1",
        text: String = "combine exact learned outcomes"
    ): LearningConsolidationProposal = LearningConsolidationProposal(
        id = LearningConsolidationId(id),
        sources = listOf(receipt),
        proposal = text,
        createdAt = Instant.parse("2026-08-29T11:02:00Z")
    )

    @Test
    fun exact_completed_outcome_can_install_controlled_consolidation_proposal() {
        val f = fixture()
        val receipt = completedReceipt(f)
        val value = proposal(receipt)

        val ownership = assertIs<LearningConsolidationInstallResult.Installed>(
            f.consolidation.install(value)
        ).ownership

        assertSame(value, ownership.proposal)
        assertSame(value, f.consolidation.find(value.id))
        assertEquals(ownership.generation, f.consolidation.inspect(value.id)?.generation)
        assertEquals(listOf(value), f.consolidation.snapshot())
    }

    @Test
    fun forged_or_changed_completed_outcome_is_rejected() {
        val f = fixture()
        val receipt = completedReceipt(f)
        val downstream = assertIs<LearningApplicationDownstreamReference.Memory>(receipt.downstream)
        val forged = receipt.copy(
            downstream = LearningApplicationDownstreamReference.Memory(
                recordId = downstream.recordId,
                generation = MemoryGeneration(downstream.generation.value + 1L)
            )
        )
        val value = proposal(forged, id = "consolidation-forged")

        assertIs<LearningConsolidationInstallResult.Rejected>(f.consolidation.install(value))
        assertFalse(f.consolidation.contains(value.id))
    }

    @Test
    fun missing_completed_source_is_rejected() {
        val f = fixture()
        val fake = LearningApplicationMutationApplicationReceipt(
            mutation = LearningApplicationMutationReference(
                LearningApplicationMutationId("missing-mutation"),
                LearningApplicationMutationGeneration(1L)
            ),
            target = LearningApplicationTarget.MEMORY,
            downstream = LearningApplicationDownstreamReference.Memory(
                MemoryRecordId("missing-memory"),
                MemoryGeneration(1L)
            )
        )

        val value = proposal(fake, id = "consolidation-missing")
        assertIs<LearningConsolidationInstallResult.Rejected>(f.consolidation.install(value))
        assertNull(f.consolidation.find(value.id))
    }

    @Test
    fun source_list_is_defensively_copied_and_duplicate_mutation_references_reject() {
        val f = fixture()
        val receipt = completedReceipt(f)
        val mutableSources = mutableListOf(receipt)
        val value = LearningConsolidationProposal(
            id = LearningConsolidationId("consolidation-copy"),
            sources = mutableSources,
            proposal = "proposal",
            createdAt = Instant.parse("2026-08-29T11:02:00Z")
        )

        mutableSources.clear()
        assertEquals(listOf(receipt), value.sources)

        assertFailsWith<IllegalArgumentException> {
            LearningConsolidationProposal(
                id = LearningConsolidationId("consolidation-duplicate"),
                sources = listOf(receipt, receipt),
                proposal = "proposal",
                createdAt = Instant.parse("2026-08-29T11:03:00Z")
            )
        }
    }

    @Test
    fun stale_ownership_cannot_remove_replacement() {
        val f = fixture()
        val receipt = completedReceipt(f)
        val first = assertIs<LearningConsolidationInstallResult.Installed>(
            f.consolidation.install(proposal(receipt))
        ).ownership

        assertTrue(first.remove())
        val replacement = assertIs<LearningConsolidationInstallResult.Installed>(
            f.consolidation.install(proposal(receipt))
        ).ownership

        assertNotEquals(first.generation, replacement.generation)
        assertFalse(first.remove())
        assertTrue(f.consolidation.contains(replacement.proposal.id))
        assertEquals(replacement.generation, f.consolidation.inspect(replacement.proposal.id)?.generation)
    }

    @Test
    fun independent_consolidation_compositions_do_not_share_proposals() {
        val f = fixture()
        val receipt = completedReceipt(f)
        val other = LearningConsolidationComposition(f.foundation, f.mutations)
        val value = proposal(receipt)

        assertIs<LearningConsolidationInstallResult.Installed>(f.consolidation.install(value))

        assertTrue(f.consolidation.contains(value.id))
        assertFalse(other.contains(value.id))
        assertTrue(other.snapshot().isEmpty())
    }

    @Test
    fun proposal_content_is_redacted_from_rendering_and_observability() {
        val f = fixture()
        val receipt = completedReceipt(f)
        f.logs.clear()
        f.diagnostics.clear()
        val secret = "CONSOLIDATION-SECRET-PROPOSAL-CONTENT"
        val value = proposal(receipt, id = "consolidation-private", text = secret)

        assertIs<LearningConsolidationInstallResult.Installed>(f.consolidation.install(value))

        assertFalse(value.toString().contains(secret))
        assertFalse(f.logs.snapshot().joinToString("\n").contains(secret))
        assertFalse(f.diagnostics.snapshot().joinToString("\n").contains(secret))
    }

    @Test
    fun snapshots_are_deterministic_by_created_at_then_id() {
        val f = fixture()
        val firstReceipt = completedReceipt(f, "mutation-a", "idem-a", "memory-a")
        val secondReceipt = completedReceipt(f, "mutation-b", "idem-b", "memory-b")
        val sameTime = Instant.parse("2026-08-29T11:05:00Z")
        val later = LearningConsolidationProposal(
            LearningConsolidationId("z"),
            listOf(firstReceipt),
            "later",
            Instant.parse("2026-08-29T11:06:00Z")
        )
        val b = LearningConsolidationProposal(
            LearningConsolidationId("b"),
            listOf(secondReceipt),
            "same time b",
            sameTime
        )
        val a = LearningConsolidationProposal(
            LearningConsolidationId("a"),
            listOf(firstReceipt),
            "same time a",
            sameTime
        )

        listOf(later, b, a).forEach {
            assertIs<LearningConsolidationInstallResult.Installed>(f.consolidation.install(it))
        }

        assertEquals(listOf("a", "b", "z"), f.consolidation.snapshot().map { it.id.value })
    }
}
