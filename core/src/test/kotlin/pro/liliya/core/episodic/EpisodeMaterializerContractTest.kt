package pro.liliya.core.episodic

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EpisodeMaterializerContractTest {
    @Test
    fun missing_or_mismatched_evidence_fails_closed_before_store() {
        val repository = FakeRepository()
        val candidate = candidate(listOf(ref("conversation-v3", "chunk-1")))

        val missing = EpisodeMaterializer(repository) { RawEvidenceVerificationResult.Missing }
        assertEquals(
            EpisodeMaterializationRejection.MISSING_EVIDENCE,
            assertIs<EpisodeMaterializationResult.Rejected>(missing.materialize(candidate)).reason
        )
        assertEquals(0, repository.storeCalls)

        val mismatch = EpisodeMaterializer(repository) {
            RawEvidenceVerificationResult.Present(ref("conversation-v3", "different"))
        }
        assertEquals(
            EpisodeMaterializationRejection.EVIDENCE_MISMATCH,
            assertIs<EpisodeMaterializationResult.Rejected>(mismatch.materialize(candidate)).reason
        )
        assertEquals(0, repository.storeCalls)
    }

    @Test
    fun duplicate_evidence_is_rejected_before_verification_or_store() {
        val repository = FakeRepository()
        var verifierCalls = 0
        val evidence = ref("conversation-v3", "chunk-1")
        val materializer = EpisodeMaterializer(repository) {
            verifierCalls += 1
            RawEvidenceVerificationResult.Present(it)
        }
        val result = assertIs<EpisodeMaterializationResult.Rejected>(
            materializer.materialize(candidate(listOf(evidence, evidence)))
        )
        assertEquals(EpisodeMaterializationRejection.DUPLICATE_EVIDENCE, result.reason)
        assertEquals(0, verifierCalls)
        assertEquals(0, repository.storeCalls)
    }

    @Test
    fun successful_materialization_persists_verified_provenance_and_extraction_metadata() {
        val repository = FakeRepository()
        val candidate = candidate(
            listOf(ref("action-log", "action-2"), ref("conversation-v3", "chunk-1"))
        )
        val materializer = EpisodeMaterializer(repository) {
            RawEvidenceVerificationResult.Present(it)
        }

        val result = assertIs<EpisodeMaterializationResult.Materialized>(
            materializer.materialize(candidate)
        )
        assertEquals(1, repository.storeCalls)
        assertEquals(
            listOf("action-log:action-2", "conversation-v3:chunk-1"),
            result.snapshot.record.evidence.map { "${it.namespace.value}:${it.id.value}" }
        )
        assertEquals(candidate.extraction, result.snapshot.record.extraction)
        assertEquals(candidate.extraction.extractedAt, result.snapshot.record.derivedAt)
    }

    @Test
    fun replay_uses_same_identity_and_does_not_create_duplicate_episode() {
        val repository = FakeRepository()
        val firstCandidate = candidate(listOf(ref("conversation-v3", "chunk-1")))
        val materializer = EpisodeMaterializer(repository) {
            RawEvidenceVerificationResult.Present(it)
        }

        val first = assertIs<EpisodeMaterializationResult.Materialized>(
            materializer.materialize(firstCandidate)
        )
        val replay = firstCandidate.copy(
            extraction = firstCandidate.extraction.copy(
                extractedAt = firstCandidate.extraction.extractedAt.plusSeconds(300)
            )
        )
        val second = assertIs<EpisodeMaterializationResult.AlreadyMaterialized>(
            materializer.materialize(replay)
        )

        assertEquals(first.snapshot.record.id, second.snapshot.record.id)
        assertEquals(1, repository.storeCalls)
    }

    private fun candidate(evidence: List<RawEvidenceReference>) = EpisodeCandidate(
        evidence = evidence,
        description = "bounded task completed",
        observedAt = Instant.parse("2026-09-19T10:00:00Z"),
        eventAt = Instant.parse("2026-09-19T09:59:00Z"),
        extraction = EpisodeExtractionProvenance(
            extractorId = "episode-extractor",
            extractorVersion = "1.0.0",
            extractedAt = Instant.parse("2026-09-19T10:01:00Z")
        )
    )

    private fun ref(namespace: String, id: String) = RawEvidenceReference(
        RawEvidenceNamespace(namespace),
        RawEvidenceId(id)
    )

    private class FakeRepository : EpisodicMemoryRepository {
        private val entries = LinkedHashMap<EpisodeId, EpisodeSnapshot>()
        var storeCalls = 0

        override fun store(record: EpisodeRecord): EpisodeStoreResult {
            storeCalls += 1
            if (entries.containsKey(record.id)) error("duplicate store")
            val snapshot = EpisodeSnapshot(record, entries.size.toLong() + 1L)
            entries[record.id] = snapshot
            return EpisodeStoreResult.Stored(snapshot)
        }

        override fun lookup(id: EpisodeId): EpisodeLookupResult =
            entries[id]?.let(EpisodeLookupResult::Found) ?: EpisodeLookupResult.Missing
    }
}
