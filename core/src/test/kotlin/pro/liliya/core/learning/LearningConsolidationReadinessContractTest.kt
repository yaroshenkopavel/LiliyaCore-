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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LearningConsolidationReadinessContractTest {
    private data class Fixture(
        val foundation: FoundationComposition,
        val mutations: LearningApplicationMutationComposition,
        val consolidations: LearningConsolidationComposition,
        val learning: LearningComposition
    )

    private fun fixture(label: String = "primary"): Fixture {
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, InMemoryLogWriter()) },
            correlationIds = CorrelationIdGenerator { "consolidation-readiness-$label-${sequence.incrementAndGet()}" }
        )
        val mutations = LearningApplicationMutationComposition(foundation)
        return Fixture(
            foundation = foundation,
            mutations = mutations,
            consolidations = LearningConsolidationComposition(foundation, mutations),
            learning = LearningComposition(foundation)
        )
    }

    private fun completedReceipt(f: Fixture): LearningApplicationMutationApplicationReceipt {
        val plan = LearningApplicationMutationPlan(
            id = LearningApplicationMutationId("mutation-readiness"),
            application = LearningApplicationIntentReference(
                LearningApplicationId("application-readiness"),
                LearningApplicationGeneration(1L)
            ),
            principal = AuthorityPrincipal("learning-controller"),
            target = LearningApplicationTarget.MEMORY,
            idempotencyKey = LearningApplicationIdempotencyKey("idem-readiness"),
            payload = LearningApplicationMutationPayload.Memory(
                MemoryRecord(
                    id = MemoryRecordId("memory-readiness"),
                    provenance = MemoryProvenance(MemorySourceId("learning-application")),
                    content = "readiness payload",
                    createdAt = Instant.parse("2026-08-29T11:40:00Z")
                )
            ),
            createdAt = Instant.parse("2026-08-29T11:41:00Z")
        )
        val ownership = assertIs<LearningApplicationMutationPrepareResult.Prepared>(
            f.mutations.prepare(plan)
        ).ownership
        val claim = assertIs<LearningApplicationMutationClaimResult.Claimed>(
            f.mutations.claim(LearningApplicationMutationReference(ownership.plan.id, ownership.generation))
        ).claim
        val receipt = LearningApplicationMutationApplicationReceipt(
            mutation = claim.reference,
            target = LearningApplicationTarget.MEMORY,
            downstream = LearningApplicationDownstreamReference.Memory(
                MemoryRecordId("memory-readiness"),
                MemoryGeneration(1L)
            )
        )
        assertTrue(claim.complete(receipt))
        return receipt
    }

    private fun installedConsolidation(f: Fixture): LearningConsolidationOwnership {
        val receipt = completedReceipt(f)
        return assertIs<LearningConsolidationInstallResult.Installed>(
            f.consolidations.install(
                LearningConsolidationProposal(
                    id = LearningConsolidationId("consolidation-readiness"),
                    sources = listOf(receipt),
                    proposal = "consolidate learned state",
                    createdAt = Instant.parse("2026-08-29T11:42:00Z")
                )
            )
        ).ownership
    }

    private fun reference(ownership: LearningConsolidationOwnership) =
        LearningConsolidationReference(ownership.proposal.id, ownership.generation)

    @Test
    fun separate_bridge_instances_share_exact_conversion_completion_state() {
        val f = fixture()
        val consolidation = installedConsolidation(f)
        val ref = reference(consolidation)
        val firstBridge = LearningConsolidationCandidateBridge(f.consolidations, f.learning)
        val secondBridge = LearningConsolidationCandidateBridge(f.consolidations, f.learning)

        val first = assertIs<LearningConsolidationCandidateBridgeResult.Converted>(
            firstBridge.convert(
                ref,
                LearningCandidateId("candidate-first-bridge"),
                Instant.parse("2026-08-29T11:43:00Z")
            )
        )
        val second = assertIs<LearningConsolidationCandidateBridgeResult.AlreadyConverted>(
            secondBridge.convert(
                ref,
                LearningCandidateId("candidate-second-bridge"),
                Instant.parse("2026-08-29T11:44:00Z")
            )
        )

        assertEquals(first.candidate, second.candidate)
        assertEquals(1, f.learning.snapshot().size)
        assertTrue(f.learning.contains(LearningCandidateId("candidate-first-bridge")))
        assertFalse(f.learning.contains(LearningCandidateId("candidate-second-bridge")))
    }

    @Test
    fun legitimate_consolidation_candidate_cannot_be_transplanted_through_public_install() {
        val source = fixture("source")
        val consolidation = installedConsolidation(source)
        val converted = assertIs<LearningConsolidationCandidateBridgeResult.Converted>(
            LearningConsolidationCandidateBridge(source.consolidations, source.learning).convert(
                reference(consolidation),
                LearningCandidateId("candidate-origin-owned"),
                Instant.parse("2026-08-29T11:43:00Z")
            )
        )
        val candidate = assertNotNull(source.learning.find(converted.candidate.candidateId))
        assertIs<LearningOrigin.Consolidation>(candidate.origin)

        val destination = fixture("destination")
        val rejected = assertIs<LearningInstallResult.Rejected>(
            destination.learning.install(candidate)
        )

        assertTrue(rejected.reason.contains("consolidation bridge"))
        assertFalse(destination.learning.contains(candidate.id))
        assertEquals(1, source.learning.snapshot().size)
    }
}
