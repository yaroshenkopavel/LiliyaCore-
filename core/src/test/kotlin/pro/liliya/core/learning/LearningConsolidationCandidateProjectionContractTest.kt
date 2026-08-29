package pro.liliya.core.learning

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

class LearningConsolidationCandidateProjectionContractTest {
    private data class Fixture(
        val foundation: FoundationComposition,
        val mutations: LearningApplicationMutationComposition,
        val consolidations: LearningConsolidationComposition,
        val learning: LearningComposition,
        val projector: LearningConsolidationCandidateProjector,
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
            correlationIds = CorrelationIdGenerator { "consolidation-projection-${sequence.incrementAndGet()}" }
        )
        val mutations = LearningApplicationMutationComposition(foundation)
        val consolidations = LearningConsolidationComposition(foundation, mutations)
        val learning = LearningComposition(foundation)
        return Fixture(
            foundation = foundation,
            mutations = mutations,
            consolidations = consolidations,
            learning = learning,
            projector = LearningConsolidationCandidateProjector(foundation, consolidations, learning),
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
                LearningApplicationId("application-$mutationId"),
                LearningApplicationGeneration(1L)
            ),
            principal = AuthorityPrincipal("learning-controller"),
            target = LearningApplicationTarget.MEMORY,
            idempotencyKey = LearningApplicationIdempotencyKey(idempotencyKey),
            payload = LearningApplicationMutationPayload.Memory(
                MemoryRecord(
                    id = MemoryRecordId(memoryId),
                    provenance = MemoryProvenance(MemorySourceId("learning-application")),
                    content = "private source payload",
                    createdAt = Instant.parse("2026-08-29T11:20:00Z")
                )
            ),
            createdAt = Instant.parse("2026-08-29T11:21:00Z")
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

    private fun installConsolidation(
        f: Fixture,
        id: String = "consolidation-source",
        proposalText: String = "consolidated candidate proposal"
    ): LearningConsolidationOwnership {
        val receipt = completedReceipt(f)
        return assertIs<LearningConsolidationInstallResult.Installed>(
            f.consolidations.install(
                LearningConsolidationProposal(
                    id = LearningConsolidationId(id),
                    sources = listOf(receipt),
                    proposal = proposalText,
                    createdAt = Instant.parse("2026-08-29T11:22:00Z")
                )
            )
        ).ownership
    }

    private fun request(
        ownership: LearningConsolidationOwnership,
        candidateId: String = "candidate-from-consolidation",
        createdAt: Instant = Instant.parse("2026-08-29T11:23:00Z")
    ): LearningConsolidationCandidateProjectionRequest =
        LearningConsolidationCandidateProjectionRequest(
            consolidation = LearningConsolidationReference(
                ownership.proposal.id,
                ownership.generation
            ),
            candidateId = LearningCandidateId(candidateId),
            createdAt = createdAt
        )

    @Test
    fun exact_current_consolidation_projects_normal_learning_candidate_with_exact_origin() {
        val f = fixture()
        val consolidation = installConsolidation(f)
        val req = request(consolidation)

        val projected = assertIs<LearningConsolidationCandidateProjectionResult.Projected>(
            f.projector.project(req)
        )
        val candidate = assertNotNull(f.learning.find(projected.receipt.candidate.candidateId))

        assertEquals(
            LearningOrigin.Consolidation(consolidation.proposal.id, consolidation.generation),
            candidate.origin
        )
        assertEquals(consolidation.proposal.proposal, candidate.proposal)
        assertEquals(req.createdAt, candidate.createdAt)
        assertEquals(projected.receipt, f.projector.completedProjection(req.consolidation))
    }

    @Test
    fun stale_consolidation_generation_is_rejected_without_candidate() {
        val f = fixture()
        val consolidation = installConsolidation(f)
        val req = request(consolidation).copy(
            consolidation = LearningConsolidationReference(
                consolidation.proposal.id,
                LearningConsolidationGeneration(consolidation.generation.value + 1L)
            )
        )

        val rejected = assertIs<LearningConsolidationCandidateProjectionResult.Rejected>(
            f.projector.project(req)
        )

        assertEquals(
            LearningConsolidationCandidateProjectionRejection.CONSOLIDATION_GENERATION_MISMATCH,
            rejected.reason
        )
        assertFalse(f.learning.contains(req.candidateId))
    }

    @Test
    fun exact_same_projection_replays_previous_receipt_without_second_candidate() {
        val f = fixture()
        val consolidation = installConsolidation(f)
        val req = request(consolidation)

        val first = assertIs<LearningConsolidationCandidateProjectionResult.Projected>(
            f.projector.project(req)
        )
        val replay = assertIs<LearningConsolidationCandidateProjectionResult.AlreadyProjected>(
            f.projector.project(req.copy())
        )

        assertEquals(first.receipt, replay.receipt)
        assertEquals(1, f.learning.snapshotEntries().size)
    }

    @Test
    fun same_exact_consolidation_cannot_project_a_different_candidate() {
        val f = fixture()
        val consolidation = installConsolidation(f)
        val first = request(consolidation, candidateId = "candidate-a")
        val second = request(consolidation, candidateId = "candidate-b")

        assertIs<LearningConsolidationCandidateProjectionResult.Projected>(f.projector.project(first))
        val rejected = assertIs<LearningConsolidationCandidateProjectionResult.Rejected>(
            f.projector.project(second)
        )

        assertEquals(
            LearningConsolidationCandidateProjectionRejection.ALREADY_PROJECTED_DIFFERENT_REQUEST,
            rejected.reason
        )
        assertTrue(f.learning.contains(first.candidateId))
        assertFalse(f.learning.contains(second.candidateId))
    }

    @Test
    fun candidate_id_conflict_does_not_complete_projection_and_allows_retry_with_new_id() {
        val f = fixture()
        val consolidation = installConsolidation(f)
        val conflictingId = LearningCandidateId("candidate-conflict")
        assertIs<LearningInstallResult.Installed>(
            f.learning.install(
                LearningCandidate(
                    id = conflictingId,
                    origin = LearningOrigin.Declared(LearningSourceId("test")),
                    proposal = "existing candidate",
                    createdAt = Instant.parse("2026-08-29T11:19:00Z")
                )
            )
        )

        assertIs<LearningConsolidationCandidateProjectionResult.CandidateRejected>(
            f.projector.project(request(consolidation, candidateId = conflictingId.value))
        )
        assertEquals(null, f.projector.completedProjection(request(consolidation).consolidation))

        val retry = assertIs<LearningConsolidationCandidateProjectionResult.Projected>(
            f.projector.project(request(consolidation, candidateId = "candidate-retry"))
        )
        assertTrue(f.learning.contains(retry.receipt.candidate.candidateId))
    }

    @Test
    fun projection_observability_does_not_render_consolidation_proposal_content() {
        val f = fixture()
        val secret = "CONSOLIDATION-PROJECTION-SECRET-CONTENT"
        val consolidation = installConsolidation(f, proposalText = secret)
        f.logs.clear()
        f.diagnostics.clear()

        val result = assertIs<LearningConsolidationCandidateProjectionResult.Projected>(
            f.projector.project(request(consolidation))
        )

        assertFalse(result.toString().contains(secret))
        assertFalse(f.logs.snapshot().joinToString("\n").contains(secret))
        assertFalse(f.diagnostics.snapshot().joinToString("\n").contains(secret))
    }

    @Test
    fun concurrent_same_projection_has_one_projection_and_one_replay_with_one_candidate() {
        val f = fixture()
        val consolidation = installConsolidation(f)
        val req = request(consolidation)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map {
                executor.submit<LearningConsolidationCandidateProjectionResult> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    f.projector.project(req)
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(1, results.count { it is LearningConsolidationCandidateProjectionResult.Projected })
            assertEquals(1, results.count { it is LearningConsolidationCandidateProjectionResult.AlreadyProjected })
            assertEquals(1, f.learning.snapshotEntries().size)
        } finally {
            executor.shutdownNow()
        }
    }
}
