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

class LearningConsolidationCandidateBridgeContractTest {
    private data class Fixture(
        val foundation: FoundationComposition,
        val mutations: LearningApplicationMutationComposition,
        val consolidations: LearningConsolidationComposition,
        val learning: LearningComposition,
        val bridge: LearningConsolidationCandidateBridge,
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
            correlationIds = CorrelationIdGenerator { "consolidation-bridge-${sequence.incrementAndGet()}" }
        )
        val mutations = LearningApplicationMutationComposition(foundation)
        val consolidations = LearningConsolidationComposition(foundation, mutations)
        val learning = LearningComposition(foundation)
        return Fixture(
            foundation = foundation,
            mutations = mutations,
            consolidations = consolidations,
            learning = learning,
            bridge = LearningConsolidationCandidateBridge(consolidations, learning),
            logs = logs,
            diagnostics = diagnostics
        )
    }

    private fun completedReceipt(
        f: Fixture,
        mutationId: String = "mutation-source",
        idempotencyKey: String = "idem-source"
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
                    id = MemoryRecordId("memory-$mutationId"),
                    provenance = MemoryProvenance(MemorySourceId("learning-application")),
                    content = "source content must remain private",
                    createdAt = Instant.parse("2026-08-29T11:20:00Z")
                )
            ),
            createdAt = Instant.parse("2026-08-29T11:21:00Z")
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
                recordId = MemoryRecordId("memory-$mutationId"),
                generation = MemoryGeneration(1L)
            )
        )
        assertTrue(claim.complete(receipt))
        return receipt
    }

    private fun installedConsolidation(
        f: Fixture,
        secret: String = "consolidated proposal"
    ): LearningConsolidationOwnership {
        val receipt = completedReceipt(f)
        return assertIs<LearningConsolidationInstallResult.Installed>(
            f.consolidations.install(
                LearningConsolidationProposal(
                    id = LearningConsolidationId("consolidation-1"),
                    sources = listOf(receipt),
                    proposal = secret,
                    createdAt = Instant.parse("2026-08-29T11:22:00Z")
                )
            )
        ).ownership
    }

    private fun reference(ownership: LearningConsolidationOwnership) =
        LearningConsolidationReference(ownership.proposal.id, ownership.generation)

    @Test
    fun exact_consolidation_converts_once_to_typed_learning_candidate() {
        val f = fixture()
        val consolidation = installedConsolidation(f)
        val ref = reference(consolidation)

        val converted = assertIs<LearningConsolidationCandidateBridgeResult.Converted>(
            f.bridge.convert(
                ref,
                LearningCandidateId("candidate-consolidated"),
                Instant.parse("2026-08-29T11:23:00Z")
            )
        )

        val snapshot = assertNotNull(f.learning.inspect(converted.candidate.candidateId))
        assertEquals(converted.candidate.generation, snapshot.generation)
        assertEquals(consolidation.proposal.proposal, snapshot.candidate.proposal)
        val origin = assertIs<LearningOrigin.Consolidation>(snapshot.candidate.origin)
        assertEquals(ref.consolidationId, origin.consolidationId)
        assertEquals(ref.generation, origin.generation)
        assertEquals(1, f.learning.snapshot().size)
    }

    @Test
    fun repeated_conversion_returns_same_candidate_without_duplicate_install() {
        val f = fixture()
        val consolidation = installedConsolidation(f)
        val ref = reference(consolidation)

        val first = assertIs<LearningConsolidationCandidateBridgeResult.Converted>(
            f.bridge.convert(ref, LearningCandidateId("candidate-once"), Instant.parse("2026-08-29T11:23:00Z"))
        )
        val second = assertIs<LearningConsolidationCandidateBridgeResult.AlreadyConverted>(
            f.bridge.convert(ref, LearningCandidateId("candidate-ignored"), Instant.parse("2026-08-29T11:24:00Z"))
        )

        assertEquals(first.candidate, second.candidate)
        assertTrue(f.learning.contains(first.candidate.candidateId))
        assertFalse(f.learning.contains(LearningCandidateId("candidate-ignored")))
        assertEquals(1, f.learning.snapshot().size)
    }

    @Test
    fun stale_consolidation_generation_rejects_without_candidate() {
        val f = fixture()
        val consolidation = installedConsolidation(f)
        val stale = LearningConsolidationReference(
            consolidation.proposal.id,
            LearningConsolidationGeneration(consolidation.generation.value + 1L)
        )

        val rejected = assertIs<LearningConsolidationCandidateBridgeResult.ConsolidationRejected>(
            f.bridge.convert(stale, LearningCandidateId("candidate-stale"), Instant.parse("2026-08-29T11:23:00Z"))
        )

        assertEquals(LearningConsolidationConversionRejection.CONSOLIDATION_GENERATION_MISMATCH, rejected.reason)
        assertFalse(f.learning.contains(LearningCandidateId("candidate-stale")))
    }

    @Test
    fun candidate_id_conflict_releases_conversion_and_allows_retry_with_new_id() {
        val f = fixture()
        val consolidation = installedConsolidation(f)
        val ref = reference(consolidation)
        assertIs<LearningInstallResult.Installed>(
            f.learning.install(
                LearningCandidate(
                    id = LearningCandidateId("candidate-conflict"),
                    origin = LearningOrigin.Declared(LearningSourceId("test")),
                    proposal = "existing candidate",
                    createdAt = Instant.parse("2026-08-29T11:19:00Z")
                )
            )
        )

        assertIs<LearningConsolidationCandidateBridgeResult.CandidateRejected>(
            f.bridge.convert(ref, LearningCandidateId("candidate-conflict"), Instant.parse("2026-08-29T11:23:00Z"))
        )

        val retry = assertIs<LearningConsolidationCandidateBridgeResult.Converted>(
            f.bridge.convert(ref, LearningCandidateId("candidate-retry"), Instant.parse("2026-08-29T11:24:00Z"))
        )
        assertEquals(LearningCandidateId("candidate-retry"), retry.candidate.candidateId)
        assertTrue(f.learning.contains(retry.candidate.candidateId))
    }

    @Test
    fun concurrent_conversion_has_one_candidate_winner() {
        val f = fixture()
        val consolidation = installedConsolidation(f)
        val ref = reference(consolidation)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf("a", "b").map { suffix ->
                executor.submit<LearningConsolidationCandidateBridgeResult> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    f.bridge.convert(
                        ref,
                        LearningCandidateId("candidate-$suffix"),
                        Instant.parse("2026-08-29T11:23:00Z")
                    )
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(1, results.count { it is LearningConsolidationCandidateBridgeResult.Converted })
            assertEquals(1, f.learning.snapshot().size)
            val other = results.single { it !is LearningConsolidationCandidateBridgeResult.Converted }
            assertTrue(
                other is LearningConsolidationCandidateBridgeResult.AlreadyConverted ||
                    (other is LearningConsolidationCandidateBridgeResult.ConsolidationRejected &&
                        other.reason == LearningConsolidationConversionRejection.ALREADY_CLAIMED)
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun active_conversion_claim_blocks_source_removal_until_release() {
        val f = fixture()
        val consolidation = installedConsolidation(f)
        val ref = reference(consolidation)
        val claim = assertIs<LearningConsolidationConversionResult.Claimed>(
            f.consolidations.claimCandidateConversion(
                ref,
                f.foundation.rootContext("testClaimConversion", "LearningConsolidation")
            )
        ).claim

        assertFalse(consolidation.remove())
        assertTrue(claim.release())
        assertTrue(consolidation.remove())
    }

    @Test
    fun secret_proposal_is_not_rendered_by_bridge_result_or_observability() {
        val f = fixture()
        val secret = "CONSOLIDATION-BRIDGE-SECRET-DO-NOT-LOG"
        val consolidation = installedConsolidation(f, secret)
        f.logs.clear()
        f.diagnostics.clear()

        val result = f.bridge.convert(
            reference(consolidation),
            LearningCandidateId("candidate-private"),
            Instant.parse("2026-08-29T11:23:00Z")
        )

        assertFalse(result.toString().contains(secret))
        assertFalse(f.logs.snapshot().joinToString("\n").contains(secret))
        assertFalse(f.diagnostics.snapshot().joinToString("\n").contains(secret))
    }

    @Test
    fun bridge_observability_has_explicit_parent_child_correlation() {
        val f = fixture()
        val consolidation = installedConsolidation(f)
        f.logs.clear()
        f.diagnostics.clear()

        assertIs<LearningConsolidationCandidateBridgeResult.Converted>(
            f.bridge.convert(
                reference(consolidation),
                LearningCandidateId("candidate-correlation"),
                Instant.parse("2026-08-29T11:23:00Z")
            )
        )

        val events = f.logs.snapshot()
        val finalEvent = events.last { it.marker == "LEARNING_CONSOLIDATION_CANDIDATE_CONVERTED" }
        val rootCorrelation = finalEvent.context.correlationId
        val claimEvent = events.first { it.marker == "LEARNING_CONSOLIDATION_CONVERSION_CLAIMED" }
        val candidateEvent = events.first { it.marker == "LEARNING_CANDIDATE_REGISTERED" }
        val completionEvent = events.first { it.marker == "LEARNING_CONSOLIDATION_CONVERSION_COMPLETED" }

        assertEquals(rootCorrelation, claimEvent.context.parentCorrelationId)
        assertEquals(rootCorrelation, candidateEvent.context.parentCorrelationId)
        assertEquals(claimEvent.context.correlationId, completionEvent.context.parentCorrelationId)

        listOf(finalEvent, claimEvent, candidateEvent, completionEvent).forEach { logEvent ->
            val diagnostic = f.diagnostics.snapshot().first { it.code == logEvent.marker }
            assertEquals(logEvent.context, diagnostic.context)
        }
    }
}
