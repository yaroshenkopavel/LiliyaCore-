package pro.liliya.core.episodic

import java.time.Instant
import pro.liliya.core.encryption.CognitiveEncryptionFailureCategory
import pro.liliya.core.encryption.EncryptedPersistentRecordPageResult
import pro.liliya.core.persistence.PersistentBackendPageCursor
import pro.liliya.core.persistence.PersistentBackendPageOrder
import pro.liliya.core.persistence.PersistentBackendPageRequest
import pro.liliya.core.persistence.PersistentEntityId

enum class EpisodeTemporalAxis {
    DERIVED,
    OBSERVED,
    EVENT
}

enum class EpisodeTemporalOrder {
    OLDEST_FIRST,
    NEWEST_FIRST
}

data class EpisodeTimeWindow(
    val startInclusive: Instant? = null,
    val endExclusive: Instant? = null
) {
    init {
        require(startInclusive != null || endExclusive != null) {
            "episode time window must define at least one bound"
        }
        require(startInclusive == null || endExclusive == null || startInclusive < endExclusive) {
            "episode time window start must be before end"
        }
    }

    fun contains(value: Instant): Boolean =
        (startInclusive == null || value >= startInclusive) &&
            (endExclusive == null || value < endExclusive)
}

data class EpisodeTemporalQueryCursor(
    val derivedAt: Instant,
    val episodeId: EpisodeId
)

data class EpisodeTemporalQuery(
    val axis: EpisodeTemporalAxis,
    val window: EpisodeTimeWindow,
    val evidenceNamespace: RawEvidenceNamespace? = null,
    val order: EpisodeTemporalOrder = EpisodeTemporalOrder.NEWEST_FIRST,
    val limit: Int = 50,
    val maxScannedEntries: Int = 256,
    val cursorExclusive: EpisodeTemporalQueryCursor? = null
) {
    init {
        require(limit in 1..MAX_RESULT_LIMIT) {
            "episode temporal query limit must be in 1..$MAX_RESULT_LIMIT"
        }
        require(maxScannedEntries in 1..MAX_SCAN_BUDGET) {
            "episode temporal query scan budget must be in 1..$MAX_SCAN_BUDGET"
        }
    }

    companion object {
        const val MAX_RESULT_LIMIT: Int = 128
        const val MAX_SCAN_BUDGET: Int = 4096
    }
}

sealed interface EpisodeTemporalQueryResult {
    data class Loaded(
        val entries: List<EpisodeSnapshot>,
        val nextCursor: EpisodeTemporalQueryCursor?,
        val exhausted: Boolean,
        val scannedEntries: Int
    ) : EpisodeTemporalQueryResult {
        init {
            require(scannedEntries >= 0) { "scanned entries must not be negative" }
            require(exhausted || nextCursor != null) {
                "non-exhausted episodic query result requires a continuation cursor"
            }
        }
    }

    data object Corrupt : EpisodeTemporalQueryResult
    data class Incompatible(val reason: String) : EpisodeTemporalQueryResult
    data class EncryptionUnavailable(
        val category: CognitiveEncryptionFailureCategory
    ) : EpisodeTemporalQueryResult
    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : EpisodeTemporalQueryResult
}

internal object EpisodeTemporalQueryExecutor {
    fun execute(
        query: EpisodeTemporalQuery,
        pageLoader: (PersistentBackendPageRequest) -> EncryptedPersistentRecordPageResult
    ): EpisodeTemporalQueryResult {
        val matches = ArrayList<EpisodeSnapshot>(query.limit)
        var scanned = 0
        var backendCursor = query.cursorExclusive?.toPersistentCursor()
        var lastScannedCursor: EpisodeTemporalQueryCursor? = query.cursorExclusive

        while (matches.size < query.limit && scanned < query.maxScannedEntries) {
            val remainingBudget = query.maxScannedEntries - scanned
            val pageLimit = minOf(
                remainingBudget,
                PersistentBackendPageRequest.MAX_PAGE_SIZE
            )
            val loaded = when (
                val page = pageLoader(
                    PersistentBackendPageRequest(
                        limit = pageLimit,
                        order = query.order.toPersistentOrder(),
                        cursorExclusive = backendCursor,
                        schemaId = EpisodicMemoryPersistentCodec.schemaId
                    )
                )
            ) {
                EncryptedPersistentRecordPageResult.Empty ->
                    return EpisodeTemporalQueryResult.Loaded(
                        entries = matches,
                        nextCursor = null,
                        exhausted = true,
                        scannedEntries = scanned
                    )

                EncryptedPersistentRecordPageResult.Corrupt ->
                    return EpisodeTemporalQueryResult.Corrupt

                is EncryptedPersistentRecordPageResult.Incompatible ->
                    return EpisodeTemporalQueryResult.Incompatible(page.reason)

                is EncryptedPersistentRecordPageResult.EncryptionUnavailable ->
                    return EpisodeTemporalQueryResult.EncryptionUnavailable(page.category)

                is EncryptedPersistentRecordPageResult.Failed ->
                    return EpisodeTemporalQueryResult.Failed(page.reason, page.throwable)

                is EncryptedPersistentRecordPageResult.Loaded -> page
            }

            if (loaded.entries.isEmpty()) {
                return if (loaded.nextCursor == null) {
                    EpisodeTemporalQueryResult.Loaded(
                        entries = matches,
                        nextCursor = null,
                        exhausted = true,
                        scannedEntries = scanned
                    )
                } else {
                    EpisodeTemporalQueryResult.Failed(
                        "episodic temporal backend returned empty page with continuation"
                    )
                }
            }

            var processedWholePage = true
            for (snapshot in loaded.entries) {
                if (matches.size >= query.limit || scanned >= query.maxScannedEntries) {
                    processedWholePage = false
                    break
                }
                scanned += 1
                val decoded = when (
                    val episode = EpisodicMemoryPersistentCodec.decode(snapshot.record)
                ) {
                    is EpisodePersistentDecodeResult.Decoded -> episode.record
                    EpisodePersistentDecodeResult.Corrupt ->
                        return EpisodeTemporalQueryResult.Corrupt
                    is EpisodePersistentDecodeResult.Incompatible ->
                        return EpisodeTemporalQueryResult.Incompatible(episode.reason)
                }

                val cursor = EpisodeTemporalQueryCursor(
                    derivedAt = snapshot.record.createdAt,
                    episodeId = EpisodeId(snapshot.record.id.value)
                )
                lastScannedCursor = cursor

                if (decoded.matches(query)) {
                    matches += EpisodeSnapshot(
                        record = decoded,
                        generation = snapshot.generation.value
                    )
                }
            }

            val backendExhausted = processedWholePage && loaded.nextCursor == null
            if (backendExhausted) {
                return EpisodeTemporalQueryResult.Loaded(
                    entries = matches,
                    nextCursor = null,
                    exhausted = true,
                    scannedEntries = scanned
                )
            }

            if (matches.size >= query.limit || scanned >= query.maxScannedEntries) {
                return EpisodeTemporalQueryResult.Loaded(
                    entries = matches,
                    nextCursor = lastScannedCursor,
                    exhausted = false,
                    scannedEntries = scanned
                )
            }

            backendCursor = loaded.nextCursor
                ?: return EpisodeTemporalQueryResult.Loaded(
                    entries = matches,
                    nextCursor = null,
                    exhausted = true,
                    scannedEntries = scanned
                )
        }

        return EpisodeTemporalQueryResult.Loaded(
            entries = matches,
            nextCursor = lastScannedCursor,
            exhausted = false,
            scannedEntries = scanned
        )
    }

    private fun EpisodeRecord.matches(query: EpisodeTemporalQuery): Boolean {
        val time = when (query.axis) {
            EpisodeTemporalAxis.DERIVED -> derivedAt
            EpisodeTemporalAxis.OBSERVED -> observedAt
            EpisodeTemporalAxis.EVENT -> eventAt ?: return false
        }
        if (!query.window.contains(time)) return false
        val namespace = query.evidenceNamespace ?: return true
        return evidence.any { it.namespace == namespace }
    }

    private fun EpisodeTemporalQueryCursor.toPersistentCursor() =
        PersistentBackendPageCursor(
            createdAt = derivedAt,
            entityId = PersistentEntityId(episodeId.value)
        )

    private fun EpisodeTemporalOrder.toPersistentOrder() =
        when (this) {
            EpisodeTemporalOrder.OLDEST_FIRST ->
                PersistentBackendPageOrder.OLDEST_FIRST
            EpisodeTemporalOrder.NEWEST_FIRST ->
                PersistentBackendPageOrder.NEWEST_FIRST
        }
}
