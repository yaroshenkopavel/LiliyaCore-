package pro.liliya.core.episodic

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

sealed interface RawEvidenceVerificationResult {
    data class Present(val canonicalReference: RawEvidenceReference) : RawEvidenceVerificationResult
    data object Missing : RawEvidenceVerificationResult
    data class Incompatible(val reason: String) : RawEvidenceVerificationResult
    data class Failed(val reason: String, val throwable: Throwable? = null) : RawEvidenceVerificationResult
}

fun interface RawEvidenceVerifier {
    fun verify(reference: RawEvidenceReference): RawEvidenceVerificationResult
}

enum class EpisodeMaterializationRejection {
    DUPLICATE_EVIDENCE,
    MISSING_EVIDENCE,
    EVIDENCE_MISMATCH,
    EVIDENCE_INCOMPATIBLE,
    EXISTING_EPISODE_DIVERGED,
    EPISODE_CORRUPT,
    EPISODE_INCOMPATIBLE,
    EPISODE_STORE_REJECTED
}

sealed interface EpisodeMaterializationResult {
    data class Materialized(val snapshot: EpisodeSnapshot) : EpisodeMaterializationResult
    data class AlreadyMaterialized(val snapshot: EpisodeSnapshot) : EpisodeMaterializationResult
    data class Rejected(
        val reason: EpisodeMaterializationRejection,
        val evidence: RawEvidenceReference? = null
    ) : EpisodeMaterializationResult
    data class Failed(val reason: String, val throwable: Throwable? = null) : EpisodeMaterializationResult
}

class EpisodeMaterializer(
    private val repository: EpisodicMemoryRepository,
    private val evidenceVerifier: RawEvidenceVerifier
) {
    fun materialize(candidate: EpisodeCandidate): EpisodeMaterializationResult {
        if (candidate.evidence.distinct().size != candidate.evidence.size) {
            return EpisodeMaterializationResult.Rejected(EpisodeMaterializationRejection.DUPLICATE_EVIDENCE)
        }

        val canonicalEvidence = ArrayList<RawEvidenceReference>(candidate.evidence.size)
        for (reference in candidate.evidence) {
            when (val verification = evidenceVerifier.verify(reference)) {
                is RawEvidenceVerificationResult.Present -> {
                    if (verification.canonicalReference != reference) {
                        return EpisodeMaterializationResult.Rejected(
                            EpisodeMaterializationRejection.EVIDENCE_MISMATCH,
                            reference
                        )
                    }
                    canonicalEvidence += verification.canonicalReference
                }
                RawEvidenceVerificationResult.Missing -> return EpisodeMaterializationResult.Rejected(
                    EpisodeMaterializationRejection.MISSING_EVIDENCE,
                    reference
                )
                is RawEvidenceVerificationResult.Incompatible -> return EpisodeMaterializationResult.Rejected(
                    EpisodeMaterializationRejection.EVIDENCE_INCOMPATIBLE,
                    reference
                )
                is RawEvidenceVerificationResult.Failed -> return EpisodeMaterializationResult.Failed(
                    verification.reason,
                    verification.throwable
                )
            }
        }

        val orderedEvidence = canonicalEvidence.sortedWith(
            compareBy({ it.namespace.value }, { it.id.value })
        )
        val id = deterministicId(candidate, orderedEvidence)
        when (val existing = repository.lookup(id)) {
            EpisodeLookupResult.Missing -> Unit
            is EpisodeLookupResult.Found -> {
                return if (existing.snapshot.record.matches(candidate, orderedEvidence)) {
                    EpisodeMaterializationResult.AlreadyMaterialized(existing.snapshot)
                } else {
                    EpisodeMaterializationResult.Rejected(
                        EpisodeMaterializationRejection.EXISTING_EPISODE_DIVERGED
                    )
                }
            }
            EpisodeLookupResult.Corrupt -> return EpisodeMaterializationResult.Rejected(
                EpisodeMaterializationRejection.EPISODE_CORRUPT
            )
            is EpisodeLookupResult.Incompatible -> return EpisodeMaterializationResult.Rejected(
                EpisodeMaterializationRejection.EPISODE_INCOMPATIBLE
            )
            is EpisodeLookupResult.EncryptionUnavailable -> return EpisodeMaterializationResult.Failed(
                "episodic encryption unavailable: ${existing.category}"
            )
            is EpisodeLookupResult.Failed -> return EpisodeMaterializationResult.Failed(
                existing.reason,
                existing.throwable
            )
        }

        val record = EpisodeRecord(
            id = id,
            evidence = orderedEvidence,
            description = candidate.description,
            observedAt = candidate.observedAt,
            eventAt = candidate.eventAt,
            derivedAt = candidate.extraction.extractedAt,
            extraction = candidate.extraction
        )
        return when (val stored = repository.store(record)) {
            is EpisodeStoreResult.Stored -> EpisodeMaterializationResult.Materialized(stored.snapshot)
            is EpisodeStoreResult.Rejected -> EpisodeMaterializationResult.Rejected(
                EpisodeMaterializationRejection.EPISODE_STORE_REJECTED
            )
            is EpisodeStoreResult.Failed -> EpisodeMaterializationResult.Failed(
                "episodic store failed: ${stored.category}",
                stored.throwable
            )
        }
    }

    internal fun deterministicId(candidate: EpisodeCandidate): EpisodeId {
        val orderedEvidence = candidate.evidence.distinct().sortedWith(
            compareBy({ it.namespace.value }, { it.id.value })
        )
        return deterministicId(candidate, orderedEvidence)
    }

    private fun deterministicId(
        candidate: EpisodeCandidate,
        orderedEvidence: List<RawEvidenceReference>
    ): EpisodeId {
        val digest = MessageDigest.getInstance("SHA-256")
        fun put(value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        orderedEvidence.forEach {
            put(it.namespace.value)
            put(it.id.value)
        }
        put(candidate.description)
        put(candidate.observedAt.toString())
        put(candidate.eventAt?.toString() ?: "")
        put(candidate.extraction.extractorId)
        put(candidate.extraction.extractorVersion)
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        return EpisodeId("episode-$hex")
    }

    private fun EpisodeRecord.matches(
        candidate: EpisodeCandidate,
        orderedEvidence: List<RawEvidenceReference>
    ): Boolean = evidence == orderedEvidence &&
        description == candidate.description &&
        observedAt == candidate.observedAt &&
        eventAt == candidate.eventAt &&
        extraction?.extractorId == candidate.extraction.extractorId &&
        extraction.extractorVersion == candidate.extraction.extractorVersion
}
