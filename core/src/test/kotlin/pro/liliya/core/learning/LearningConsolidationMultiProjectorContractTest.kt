package pro.liliya.core.learning

import java.time.Instant
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
import kotlin.test.assertTrue

class LearningConsolidationMultiProjectorContractTest {
    @Test
    fun projection_completion_is_shared_across_projector_instances() {
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, InMemoryLogWriter()) },
            correlationIds = CorrelationIdGenerator { "multi-projector" }
        )
        val mutations = LearningApplicationMutationComposition(foundation)
        val consolidations = LearningConsolidationComposition(foundation, mutations)
        val learning = LearningComposition(foundation)
        val firstProjector = LearningConsolidationCandidateProjector(foundation, consolidations, learning)
        val secondProjector = LearningConsolidationCandidateProjector(foundation, consolidations, learning)

        val mutationPlan = LearningApplicationMutationPlan(
            id = LearningApplicationMutationId("mutation-multi-projector"),
            application = LearningApplicationIntentReference(
                LearningApplicationId("application-multi-projector"),
                LearningApplicationGeneration(1L)
            ),
            principal = AuthorityPrincipal("learning-controller"),
            target = LearningApplicationTarget.MEMORY,
            idempotencyKey = LearningApplicationIdempotencyKey("idem-multi-projector"),
            payload = LearningApplicationMutationPayload.Memory(
                MemoryRecord(
                    id = MemoryRecordId("memory-multi-projector"),
                    provenance = MemoryProvenance(MemorySourceId("learning-application")),
                    content = "private source",
                    createdAt = Instant.parse("2026-08-29T11:40:00Z")
                )
            ),
            createdAt = Instant.parse("2026-08-29T11:41:00Z")
        )
        val mutationOwnership = assertIs<LearningApplicationMutationPrepareResult.Prepared>(
            mutations.prepare(mutationPlan)
        ).ownership
        val mutationClaim = assertIs<LearningApplicationMutationClaimResult.Claimed>(
            mutations.claim(
                LearningApplicationMutationReference(mutationOwnership.plan.id, mutationOwnership.generation)
            )
        ).claim
        val mutationReceipt = LearningApplicationMutationApplicationReceipt(
            mutation = mutationClaim.reference,
            target = LearningApplicationTarget.MEMORY,
            downstream = LearningApplicationDownstreamReference.Memory(
                MemoryRecordId("memory-multi-projector"),
                MemoryGeneration(1L)
            )
        )
        assertTrue(mutationClaim.complete(mutationReceipt))

        val consolidation = assertIs<LearningConsolidationInstallResult.Installed>(
            consolidations.install(
                LearningConsolidationProposal(
                    id = LearningConsolidationId("consolidation-multi-projector"),
                    sources = listOf(mutationReceipt),
                    proposal = "one consolidation must project once",
                    createdAt = Instant.parse("2026-08-29T11:42:00Z")
                )
            )
        ).ownership
        val reference = LearningConsolidationReference(consolidation.proposal.id, consolidation.generation)
        val firstRequest = LearningConsolidationCandidateProjectionRequest(
            consolidation = reference,
            candidateId = LearningCandidateId("candidate-a"),
            createdAt = Instant.parse("2026-08-29T11:43:00Z")
        )

        val first = assertIs<LearningConsolidationCandidateProjectionResult.Projected>(
            firstProjector.project(firstRequest)
        )
        val replay = assertIs<LearningConsolidationCandidateProjectionResult.AlreadyProjected>(
            secondProjector.project(firstRequest.copy())
        )
        assertEquals(first.receipt, replay.receipt)
        assertEquals(first.receipt, secondProjector.completedProjection(reference))
        assertEquals(1, learning.snapshotEntries().size)

        val conflicting = LearningConsolidationCandidateProjectionRequest(
            consolidation = reference,
            candidateId = LearningCandidateId("candidate-b"),
            createdAt = Instant.parse("2026-08-29T11:44:00Z")
        )
        val rejected = assertIs<LearningConsolidationCandidateProjectionResult.Rejected>(
            secondProjector.project(conflicting)
        )
        assertEquals(
            LearningConsolidationCandidateProjectionRejection.ALREADY_PROJECTED_DIFFERENT_REQUEST,
            rejected.reason
        )
        assertFalse(learning.contains(conflicting.candidateId))
        assertEquals(1, learning.snapshotEntries().size)
    }
}
