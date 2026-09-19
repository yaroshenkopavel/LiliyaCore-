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

data class EpisodeRecord(
    val id: EpisodeId,
    val evidence: List<RawEvidenceReference>,
    val description: String,
    val observedAt: Instant,
    val eventAt: Instant? = null,
    val derivedAt: Instant
) {
    init {
        require(evidence.isNotEmpty()) { "episode must reference raw evidence" }
        require(evidence.distinct().size == evidence.size) { "episode raw evidence references must be unique" }
        require(description.isNotBlank()) { "episode description must not be blank" }
    }
}

data class EpisodeSnapshot(
    val record: EpisodeRecord,
    val generation: Long
) {
    init { require(generation > 0L) { "episode generation must be positive" } }
}
