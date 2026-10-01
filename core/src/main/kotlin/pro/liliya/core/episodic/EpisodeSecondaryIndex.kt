package pro.liliya.core.episodic

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

@JvmInline
value class EpisodeIndexEntryId(val value: String) {
    init { require(value.isNotBlank()) { "episode index entry id must not be blank" } }
    override fun toString(): String = value
}

sealed interface EpisodeIndexEntry {
    val id: EpisodeIndexEntryId
    val episodeId: EpisodeId
    val episodeGeneration: Long
    val sortAt: Instant
}

data class EpisodeTemporalIndexEntry(
    val axis: EpisodeTemporalAxis,
    val indexedAt: Instant,
    override val episodeId: EpisodeId,
    override val episodeGeneration: Long,
    override val id: EpisodeIndexEntryId = EpisodeIndexIds.temporal(
        axis = axis,
        indexedAt = indexedAt,
        episodeId = episodeId,
        episodeGeneration = episodeGeneration
    )
) : EpisodeIndexEntry {
    init { require(episodeGeneration > 0L) { "episode index generation must be positive" } }
    override val sortAt: Instant get() = indexedAt
}

data class EpisodeProvenanceIndexEntry(
    val namespace: RawEvidenceNamespace,
    val episodeDerivedAt: Instant,
    override val episodeId: EpisodeId,
    override val episodeGeneration: Long,
    override val id: EpisodeIndexEntryId = EpisodeIndexIds.provenance(
        namespace = namespace,
        episodeDerivedAt = episodeDerivedAt,
        episodeId = episodeId,
        episodeGeneration = episodeGeneration
    )
) : EpisodeIndexEntry {
    init { require(episodeGeneration > 0L) { "episode index generation must be positive" } }
    override val sortAt: Instant get() = episodeDerivedAt
}

enum class EpisodeIndexCompleteness {
    UNKNOWN,
    INCOMPLETE,
    COMPLETE
}

object EpisodeIndexProjector {
    fun project(snapshot: EpisodeSnapshot): List<EpisodeIndexEntry> {
        val record = snapshot.record
        val entries = ArrayList<EpisodeIndexEntry>()
        entries += EpisodeTemporalIndexEntry(
            axis = EpisodeTemporalAxis.DERIVED,
            indexedAt = record.derivedAt,
            episodeId = record.id,
            episodeGeneration = snapshot.generation
        )
        entries += EpisodeTemporalIndexEntry(
            axis = EpisodeTemporalAxis.OBSERVED,
            indexedAt = record.observedAt,
            episodeId = record.id,
            episodeGeneration = snapshot.generation
        )
        record.eventAt?.let { eventAt ->
            entries += EpisodeTemporalIndexEntry(
                axis = EpisodeTemporalAxis.EVENT,
                indexedAt = eventAt,
                episodeId = record.id,
                episodeGeneration = snapshot.generation
            )
        }
        record.evidence
            .map { it.namespace }
            .distinct()
            .sortedBy { it.value }
            .forEach { namespace ->
                entries += EpisodeProvenanceIndexEntry(
                    namespace = namespace,
                    episodeDerivedAt = record.derivedAt,
                    episodeId = record.id,
                    episodeGeneration = snapshot.generation
                )
            }
        return entries
    }
}

internal object EpisodeIndexIds {
    fun temporal(
        axis: EpisodeTemporalAxis,
        indexedAt: Instant,
        episodeId: EpisodeId,
        episodeGeneration: Long
    ): EpisodeIndexEntryId = hashId(
        "temporal",
        axis.name,
        indexedAt.toString(),
        episodeId.value,
        episodeGeneration.toString()
    )

    fun provenance(
        namespace: RawEvidenceNamespace,
        episodeDerivedAt: Instant,
        episodeId: EpisodeId,
        episodeGeneration: Long
    ): EpisodeIndexEntryId = hashId(
        "provenance",
        namespace.value,
        episodeDerivedAt.toString(),
        episodeId.value,
        episodeGeneration.toString()
    )

    private fun hashId(vararg values: String): EpisodeIndexEntryId {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEach { value ->
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        return EpisodeIndexEntryId("episode-index-$hex")
    }
}

data class EpisodeIndexSourceCheckpoint(
    val revision: Long,
    val highWatermark: Long,
    val entryCount: Long
) {
    init {
        require(revision >= 0L) { "episode index source revision must not be negative" }
        require(highWatermark >= 0L) { "episode index source high watermark must not be negative" }
        require(entryCount >= 0L) { "episode index source entry count must not be negative" }
    }
}

data class EpisodeIndexManifest(
    val source: EpisodeIndexSourceCheckpoint,
    val rebuiltAt: Instant
)
