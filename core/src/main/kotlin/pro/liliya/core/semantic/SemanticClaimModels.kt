package pro.liliya.core.semantic

import java.time.Instant
import pro.liliya.core.episodic.EpisodeId
import pro.liliya.core.episodic.RawEvidenceReference

@JvmInline
value class SemanticClaimId(val value: String) {
    init { require(value.isNotBlank()) { "semantic claim id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class SemanticClaimVersion(val value: Long) {
    init { require(value > 0L) { "semantic claim version must be positive" } }
}

data class SemanticEntityReference(
    val namespace: String,
    val id: String
) {
    init {
        require(namespace.isNotBlank()) { "semantic entity namespace must not be blank" }
        require(id.isNotBlank()) { "semantic entity id must not be blank" }
    }
}

sealed interface SemanticClaimObject {
    data class Entity(val reference: SemanticEntityReference) : SemanticClaimObject

    data class Text(val value: String) : SemanticClaimObject {
        init { require(value.isNotBlank()) { "semantic text value must not be blank" } }
    }

    data class Number(val canonical: String) : SemanticClaimObject {
        init {
            require(canonical.isNotBlank()) { "semantic numeric value must not be blank" }
            require(canonical.toBigDecimalOrNull() != null) {
                "semantic numeric value must be canonical decimal text"
            }
        }
    }

    data class BooleanValue(val value: Boolean) : SemanticClaimObject
}

data class SemanticClaimIdentity(
    val subject: SemanticEntityReference,
    val predicate: String
) {
    init { require(predicate.isNotBlank()) { "semantic claim predicate must not be blank" } }
}

data class SemanticClaimTemporalState(
    val observedAt: Instant,
    val validFrom: Instant? = null,
    val validUntil: Instant? = null,
    val supersededAt: Instant? = null
) {
    init {
        require(validFrom == null || validUntil == null || validFrom < validUntil) {
            "semantic claim validFrom must be before validUntil"
        }
        require(supersededAt == null || supersededAt >= observedAt) {
            "semantic claim supersededAt must not precede observedAt"
        }
    }
}

enum class SemanticEvidenceStrength {
    LOW,
    MEDIUM,
    HIGH
}

data class SemanticClaimExtractionProvenance(
    val extractorId: String,
    val extractorVersion: String,
    val extractedAt: Instant
) {
    init {
        require(extractorId.isNotBlank()) { "semantic claim extractor id must not be blank" }
        require(extractorVersion.isNotBlank()) { "semantic claim extractor version must not be blank" }
    }
}

data class SemanticClaimProvenance(
    val episodes: List<EpisodeId> = emptyList(),
    val rawEvidence: List<RawEvidenceReference> = emptyList(),
    val extraction: SemanticClaimExtractionProvenance,
    val evidenceStrength: SemanticEvidenceStrength? = null
) {
    init {
        require(episodes.isNotEmpty() || rawEvidence.isNotEmpty()) {
            "semantic claim must retain episode or raw evidence provenance"
        }
        require(episodes.distinct().size == episodes.size) {
            "semantic episode provenance must be unique"
        }
        require(rawEvidence.distinct().size == rawEvidence.size) {
            "semantic raw evidence provenance must be unique"
        }
    }
}

data class SemanticClaimRecord(
    val id: SemanticClaimId,
    val version: SemanticClaimVersion,
    val identity: SemanticClaimIdentity,
    val objectValue: SemanticClaimObject,
    val temporal: SemanticClaimTemporalState,
    val provenance: SemanticClaimProvenance
) {
    init {
        require(provenance.extraction.extractedAt >= temporal.observedAt) {
            "semantic extraction time must not precede observedAt"
        }
    }
}
