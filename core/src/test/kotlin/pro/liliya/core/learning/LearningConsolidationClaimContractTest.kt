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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LearningConsolidationClaimContractTest {
    private fun foundation(): FoundationComposition = FoundationComposition(
        diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
        loggerProvider = LoggerProvider { context -> StructuredLogger(context, InMemoryLogWriter()) },
        correlationIds = CorrelationIdGenerator { "consolidation-claim" }
    )

    @Test
    fun exact_active_claim_blocks_removal_until_release() {
        val foundation = foundation()
        val mutations = LearningApplicationMutationComposition(foundation)
        val consolidations = LearningConsolidationComposition(foundation, mutations)

        val mutationPlan = LearningApplicationMutationPlan(
            id = LearningApplicationMutationId("mutation-claim-source"),
            application = LearningApplicationIntentReference(
                LearningApplicationId("application-claim-source"),
                LearningApplicationGeneration(1L)
            ),
            principal = AuthorityPrincipal("learning-controller"),
            target = LearningApplicationTarget.MEMORY,
            idempotencyKey = LearningApplicationIdempotencyKey("idem-claim-source"),
            payload = LearningApplicationMutationPayload.Memory(
                MemoryRecord(
                    id = MemoryRecordId("memory-claim-source"),
                    provenance = MemoryProvenance(MemorySourceId("learning-application")),
                    content = "private source",
                    createdAt = Instant.parse("2026-08-29T11:30:00Z")
                )
            ),
            createdAt = Instant.parse("2026-08-29T11:31:00Z")
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
                MemoryRecordId("memory-claim-source"),
                MemoryGeneration(1L)
            )
        )
        assertTrue(mutationClaim.complete(mutationReceipt))

        val ownership = assertIs<LearningConsolidationInstallResult.Installed>(
            consolidations.install(
                LearningConsolidationProposal(
                    id = LearningConsolidationId("consolidation-claim-source"),
                    sources = listOf(mutationReceipt),
                    proposal = "candidate projection source",
                    createdAt = Instant.parse("2026-08-29T11:32:00Z")
                )
            )
        ).ownership
        val reference = LearningConsolidationReference(ownership.proposal.id, ownership.generation)
        val claim = assertIs<LearningConsolidationClaimResult.Claimed>(
            consolidations.claim(
                reference,
                foundation.rootContext(
                    operation = "claimLearningConsolidationForTest",
                    component = "LearningConsolidation"
                )
            )
        ).claim

        assertFalse(ownership.remove())
        assertTrue(consolidations.contains(ownership.proposal.id))
        assertTrue(claim.release())
        assertTrue(ownership.remove())
        assertFalse(consolidations.contains(ownership.proposal.id))
    }
}
