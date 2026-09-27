package pro.liliya.core.episodic

import java.time.Instant

@JvmInline
value class EpisodeId(val value: String) {
    init { require(value.isNotBlank()) { "episode id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class RawEvidenceNamespace(val value: String) {
    init { require(value.isNotBlank()) { "raw evidence namespace must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class RawEvidenceId(val value: String) {
    init { require(value.isNotBlank()) { "raw evidence id must not be blank" } }
    override fun toString(): String = value
}

data class RawEvidenceReference(
    val namespace: RawEvidenceNamespace,
    val id: RawEvidenceId
)

data class EpisodeExtractionProvenance(
    val extractorId: String,
    val extractorVersion: String,
    val extractedAt: Instant
) {
    init {
        require(extractorId.isNotBlank()) { "episode extractor id must not be blank" }
        require(extractorVersion.isNotBlank()) { "episode extractor version must not be blank" }
    }
}

data class EpisodeEntityReference(
    val namespace: String,
    val id: String,
    val role: String? = null
) {
    init {
        require(namespace.isNotBlank()) { "episode entity namespace must not be blank" }
        require(id.isNotBlank()) { "episode entity id must not be blank" }
        require(role == null || role.isNotBlank()) { "episode entity role must not be blank" }
    }
}

data class EpisodeContextReference(
    val namespace: String,
    val id: String
) {
    init {
        require(namespace.isNotBlank()) { "episode context namespace must not be blank" }
        require(id.isNotBlank()) { "episode context id must not be blank" }
    }
}

data class EpisodeTimeInterval(
    val startInclusive: Instant,
    val endExclusive: Instant? = null
) {
    init {
        require(endExclusive == null || startInclusive < endExclusive) {
            "episode interval start must be before end"
        }
    }
}

enum class EpisodeSignificance {
    LOW,
    NORMAL,
    HIGH
}

enum class EpisodeExtractionConfidence {
    LOW,
    MEDIUM,
    HIGH
}

enum class EpisodeLinkType {
    RELATED,
    CONTINUES,
    CAUSED_BY,
    RESULTED_IN
}

data class EpisodeLink(
    val type: EpisodeLinkType,
    val target: EpisodeId
)

data class EpisodeStructuredContext(
    val entities: List<EpisodeEntityReference> = emptyList(),
    val task: EpisodeContextReference? = null,
    val goal: EpisodeContextReference? = null,
    val tags: Set<String> = emptySet(),
    val interval: EpisodeTimeInterval? = null,
    val significance: EpisodeSignificance? = null,
    val extractionConfidence: EpisodeExtractionConfidence? = null,
    val links: List<EpisodeLink> = emptyList()
) {
    init {
        require(entities.distinct().size == entities.size) {
            "episode entity references must be unique"
        }
        require(tags.none { it.isBlank() }) {
            "episode context tags must not be blank"
        }
        require(links.distinct().size == links.size) {
            "episode links must be unique"
        }
    }

    companion object {
        val EMPTY = EpisodeStructuredContext()
    }
}

data class EpisodeCandidate(
    val evidence: List<RawEvidenceReference>,
    val description: String,
    val observedAt: Instant,
    val eventAt: Instant? = null,
    val extraction: EpisodeExtractionProvenance,
    val context: EpisodeStructuredContext = EpisodeStructuredContext.EMPTY
) {
    init {
        require(evidence.isNotEmpty()) { "episode candidate must reference raw evidence" }
        require(description.isNotBlank()) { "episode candidate description must not be blank" }
    }
}

data class EpisodeRecord(
    val id: EpisodeId,
    val evidence: List<RawEvidenceReference>,
    val description: String,
    val observedAt: Instant,
    val eventAt: Instant? = null,
    val derivedAt: Instant,
    val extraction: EpisodeExtractionProvenance? = null,
    val context: EpisodeStructuredContext = EpisodeStructuredContext.EMPTY
) {
    init {
        require(evidence.isNotEmpty()) { "episode must reference raw evidence" }
        require(evidence.distinct().size == evidence.size) { "episode raw evidence references must be unique" }
        require(description.isNotBlank()) { "episode description must not be blank" }
        require(extraction == null || extraction.extractedAt == derivedAt) {
            "episode extraction time must equal derived time"
        }
        require(context.links.none { it.target == id }) {
            "episode must not link to itself"
        }
    }
}

data class EpisodeSnapshot(
    val record: EpisodeRecord,
    val generation: Long
) {
    init { require(generation > 0L) { "episode generation must be positive" } }
}
