package pro.liliya.core.episodic

import java.time.Instant
import pro.liliya.core.persistence.PersistentBackendPageCursor
import pro.liliya.core.persistence.PersistentBackendPageOrder
import pro.liliya.core.persistence.PersistentBackendPageRequest
import pro.liliya.core.persistence.PersistentEntityId

interface EpisodicIndexReadStore {
    fun completeness(source: EpisodeIndexSourceCheckpoint): EpisodeIndexCompleteness
    fun temporalPage(
        axis: EpisodeTemporalAxis,
        limit: Int,
        order: PersistentBackendPageOrder,
        cursorExclusive: PersistentBackendPageCursor? = null
    ): EpisodeIndexPageResult

    fun provenancePage(
        limit: Int,
        order: PersistentBackendPageOrder,
        cursorExclusive: PersistentBackendPageCursor? = null
    ): EpisodeIndexPageResult
}

data class EpisodeIndexQueryCursor(
    val indexedAt: Instant,
    val indexEntryId: EpisodeIndexEntryId
)

data class EpisodeIndexedTemporalQuery(
    val axis: EpisodeTemporalAxis,
    val window: EpisodeTimeWindow,
    val order: EpisodeTemporalOrder = EpisodeTemporalOrder.NEWEST_FIRST,
    val limit: Int = 50,
    val maxScannedIndexEntries: Int = 256,
    val cursorExclusive: EpisodeIndexQueryCursor? = null
) {
    init {
        require(limit in 1..MAX_RESULT_LIMIT) {
            "indexed episode query limit must be in 1..$MAX_RESULT_LIMIT"
        }
        require(maxScannedIndexEntries in 1..MAX_SCAN_BUDGET) {
            "indexed episode scan budget must be in 1..$MAX_SCAN_BUDGET"
        }
    }

    companion object {
        const val MAX_RESULT_LIMIT: Int = 128
        const val MAX_SCAN_BUDGET: Int = 4096
    }
}

data class EpisodeIndexedProvenanceQuery(
    val namespace: RawEvidenceNamespace,
    val order: EpisodeTemporalOrder = EpisodeTemporalOrder.NEWEST_FIRST,
    val limit: Int = 50,
    val maxScannedIndexEntries: Int = 256,
    val cursorExclusive: EpisodeIndexQueryCursor? = null
) {
    init {
        require(limit in 1..EpisodeIndexedTemporalQuery.MAX_RESULT_LIMIT) {
            "indexed provenance query limit must be in 1..${EpisodeIndexedTemporalQuery.MAX_RESULT_LIMIT}"
        }
        require(maxScannedIndexEntries in 1..EpisodeIndexedTemporalQuery.MAX_SCAN_BUDGET) {
            "indexed provenance scan budget must be in 1..${EpisodeIndexedTemporalQuery.MAX_SCAN_BUDGET}"
        }
    }
}

sealed interface EpisodeIndexedRetrievalResult {
    data class Loaded(
        val entries: List<EpisodeSnapshot>,
        val nextCursor: EpisodeIndexQueryCursor?,
        val exhausted: Boolean,
        val scannedIndexEntries: Int
    ) : EpisodeIndexedRetrievalResult {
        init {
            require(scannedIndexEntries >= 0) { "scanned index entries must not be negative" }
            require(exhausted || nextCursor != null) {
                "non-exhausted indexed retrieval requires a continuation cursor"
            }
        }
    }

    data class FallbackRequired(val reason: String) : EpisodeIndexedRetrievalResult
    data object Corrupt : EpisodeIndexedRetrievalResult
    data class Incompatible(val reason: String) : EpisodeIndexedRetrievalResult
    data class Failed(val reason: String, val throwable: Throwable? = null) : EpisodeIndexedRetrievalResult
}

class EpisodeIndexedRetrievalCoordinator(
    private val source: EpisodicIndexSource,
    private val repository: EpisodicMemoryRepository,
    private val index: EpisodicIndexReadStore
) {
    fun temporal(query: EpisodeIndexedTemporalQuery): EpisodeIndexedRetrievalResult {
        val checkpoint = source.indexSourceCheckpoint()
            ?: return EpisodeIndexedRetrievalResult.FallbackRequired(
                "episodic source checkpoint unavailable"
            )
        if (index.completeness(checkpoint) != EpisodeIndexCompleteness.COMPLETE) {
            return EpisodeIndexedRetrievalResult.FallbackRequired(
                "episodic secondary index is not complete for current source checkpoint"
            )
        }
        return execute(
            limit = query.limit,
            maxScanned = query.maxScannedIndexEntries,
            order = query.order,
            cursorExclusive = query.cursorExclusive,
            pageLoader = { pageLimit, order, cursor ->
                index.temporalPage(query.axis, pageLimit, order, cursor)
            },
            predicate = { entry ->
                entry is EpisodeTemporalIndexEntry && query.window.contains(entry.indexedAt)
            },
            canStop = { entry ->
                if (entry !is EpisodeTemporalIndexEntry) {
                    false
                } else {
                    when (query.order) {
                        EpisodeTemporalOrder.OLDEST_FIRST ->
                            query.window.endExclusive?.let { entry.indexedAt >= it } ?: false
                        EpisodeTemporalOrder.NEWEST_FIRST ->
                            query.window.startInclusive?.let { entry.indexedAt < it } ?: false
                    }
                }
            }
        )
    }

    fun provenance(query: EpisodeIndexedProvenanceQuery): EpisodeIndexedRetrievalResult {
        val checkpoint = source.indexSourceCheckpoint()
            ?: return EpisodeIndexedRetrievalResult.FallbackRequired(
                "episodic source checkpoint unavailable"
            )
        if (index.completeness(checkpoint) != EpisodeIndexCompleteness.COMPLETE) {
            return EpisodeIndexedRetrievalResult.FallbackRequired(
                "episodic secondary index is not complete for current source checkpoint"
            )
        }
        return execute(
            limit = query.limit,
            maxScanned = query.maxScannedIndexEntries,
            order = query.order,
            cursorExclusive = query.cursorExclusive,
            pageLoader = { pageLimit, order, cursor ->
                index.provenancePage(pageLimit, order, cursor)
            },
            predicate = { entry ->
                entry is EpisodeProvenanceIndexEntry && entry.namespace == query.namespace
            },
            canStop = { false }
        )
    }

    private fun execute(
        limit: Int,
        maxScanned: Int,
        order: EpisodeTemporalOrder,
        cursorExclusive: EpisodeIndexQueryCursor?,
        pageLoader: (Int, PersistentBackendPageOrder, PersistentBackendPageCursor?) -> EpisodeIndexPageResult,
        predicate: (EpisodeIndexEntry) -> Boolean,
        canStop: (EpisodeIndexEntry) -> Boolean
    ): EpisodeIndexedRetrievalResult {
        val results = ArrayList<EpisodeSnapshot>(limit)
        var scanned = 0
        var backendCursor = cursorExclusive?.toPersistentCursor()
        var lastScanned = cursorExclusive

        while (results.size < limit && scanned < maxScanned) {
            val pageLimit = minOf(
                maxScanned - scanned,
                PersistentBackendPageRequest.MAX_PAGE_SIZE
            )
            when (val page = pageLoader(pageLimit, order.toPersistentOrder(), backendCursor)) {
                EpisodeIndexPageResult.Empty -> return EpisodeIndexedRetrievalResult.Loaded(
                    entries = results,
                    nextCursor = null,
                    exhausted = true,
                    scannedIndexEntries = scanned
                )
                EpisodeIndexPageResult.Corrupt -> return EpisodeIndexedRetrievalResult.Corrupt
                is EpisodeIndexPageResult.Incompatible ->
                    return EpisodeIndexedRetrievalResult.Incompatible(page.reason)
                is EpisodeIndexPageResult.EncryptionUnavailable ->
                    return EpisodeIndexedRetrievalResult.Failed(
                        "episodic index encryption unavailable: ${page.category}"
                    )
                is EpisodeIndexPageResult.Failed ->
                    return EpisodeIndexedRetrievalResult.Failed(page.reason, page.throwable)
                is EpisodeIndexPageResult.Loaded -> {
                    if (page.entries.isEmpty()) {
                        return if (page.nextCursor == null) {
                            EpisodeIndexedRetrievalResult.Loaded(
                                results,
                                null,
                                true,
                                scanned
                            )
                        } else {
                            EpisodeIndexedRetrievalResult.Failed(
                                "episodic index returned empty page with continuation"
                            )
                        }
                    }
                    var processedWholePage = true
                    for (entry in page.entries) {
                        if (results.size >= limit || scanned >= maxScanned) {
                            processedWholePage = false
                            break
                        }
                        scanned += 1
                        lastScanned = EpisodeIndexQueryCursor(entry.sortAt, entry.id)

                        if (canStop(entry)) {
                            return EpisodeIndexedRetrievalResult.Loaded(
                                entries = results,
                                nextCursor = null,
                                exhausted = true,
                                scannedIndexEntries = scanned
                            )
                        }
                        if (!predicate(entry)) continue

                        val episode = when (val lookup = repository.lookup(entry.episodeId)) {
                            EpisodeLookupResult.Missing -> return stale(entry, "canonical episode is missing")
                            is EpisodeLookupResult.Found -> lookup.snapshot
                            EpisodeLookupResult.Corrupt -> return EpisodeIndexedRetrievalResult.Corrupt
                            is EpisodeLookupResult.Incompatible ->
                                return EpisodeIndexedRetrievalResult.Incompatible(lookup.reason)
                            is EpisodeLookupResult.EncryptionUnavailable ->
                                return EpisodeIndexedRetrievalResult.Failed(
                                    "episodic memory encryption unavailable: ${lookup.category}"
                                )
                            is EpisodeLookupResult.Failed ->
                                return EpisodeIndexedRetrievalResult.Failed(lookup.reason, lookup.throwable)
                        }
                        if (episode.generation != entry.episodeGeneration) {
                            return stale(entry, "canonical episode generation does not match index entry")
                        }
                        if (!entry.matchesCanonical(episode.record)) {
                            return stale(entry, "canonical episode no longer matches index projection")
                        }
                        results += episode
                    }

                    val exhausted = processedWholePage && page.nextCursor == null
                    if (exhausted) {
                        return EpisodeIndexedRetrievalResult.Loaded(
                            results,
                            null,
                            true,
                            scanned
                        )
                    }
                    if (results.size >= limit || scanned >= maxScanned) {
                        return EpisodeIndexedRetrievalResult.Loaded(
                            results,
                            lastScanned,
                            false,
                            scanned
                        )
                    }
                    backendCursor = page.nextCursor
                        ?: return EpisodeIndexedRetrievalResult.Loaded(
                            results,
                            null,
                            true,
                            scanned
                        )
                }
            }
        }
        return EpisodeIndexedRetrievalResult.Loaded(
            results,
            lastScanned,
            false,
            scanned
        )
    }

    private fun stale(entry: EpisodeIndexEntry, reason: String) =
        EpisodeIndexedRetrievalResult.FallbackRequired(
            "stale episodic index entry ${entry.id.value}: $reason"
        )

    private fun EpisodeIndexEntry.matchesCanonical(record: EpisodeRecord): Boolean = when (this) {
        is EpisodeTemporalIndexEntry -> when (axis) {
            EpisodeTemporalAxis.DERIVED -> record.derivedAt == indexedAt
            EpisodeTemporalAxis.OBSERVED -> record.observedAt == indexedAt
            EpisodeTemporalAxis.EVENT -> record.eventAt == indexedAt
        }
        is EpisodeProvenanceIndexEntry ->
            record.derivedAt == episodeDerivedAt && record.evidence.any { it.namespace == namespace }
    }

    private fun EpisodeIndexQueryCursor.toPersistentCursor() =
        PersistentBackendPageCursor(
            createdAt = indexedAt,
            entityId = PersistentEntityId(indexEntryId.value)
        )

    private fun EpisodeTemporalOrder.toPersistentOrder() = when (this) {
        EpisodeTemporalOrder.OLDEST_FIRST -> PersistentBackendPageOrder.OLDEST_FIRST
        EpisodeTemporalOrder.NEWEST_FIRST -> PersistentBackendPageOrder.NEWEST_FIRST
    }
}
