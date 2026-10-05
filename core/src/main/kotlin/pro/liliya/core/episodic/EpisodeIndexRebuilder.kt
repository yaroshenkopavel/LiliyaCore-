package pro.liliya.core.episodic

import java.time.Instant
import pro.liliya.core.persistence.PersistentBackendPageCursor
import pro.liliya.core.persistence.PersistentBackendPageOrder
import pro.liliya.core.persistence.PersistentBackendPageRequest

sealed interface EpisodeIndexRebuildResult {
    data class Complete(
        val source: EpisodeIndexSourceCheckpoint,
        val episodesScanned: Int,
        val projectedEntries: Int,
        val indexedEntries: Int,
        val alreadyIndexedEntries: Int
    ) : EpisodeIndexRebuildResult

    data class Incomplete(
        val episodesScanned: Int,
        val projectedEntries: Int,
        val indexedEntries: Int,
        val alreadyIndexedEntries: Int,
        val reason: String
    ) : EpisodeIndexRebuildResult
}

class EpisodeIndexRebuilder(
    private val source: EpisodicIndexSource,
    private val index: EpisodicIndexProjectionStore
) {
    fun rebuild(
        rebuiltAt: Instant,
        pageSize: Int = PersistentBackendPageRequest.MAX_PAGE_SIZE
    ): EpisodeIndexRebuildResult {
        require(pageSize in 1..PersistentBackendPageRequest.MAX_PAGE_SIZE) {
            "episodic index rebuild page size must be valid"
        }

        val start = source.indexSourceCheckpoint()
            ?: return EpisodeIndexRebuildResult.Incomplete(
                episodesScanned = 0,
                projectedEntries = 0,
                indexedEntries = 0,
                alreadyIndexedEntries = 0,
                reason = "episodic source does not expose indexed checkpoint"
            )

        var cursor: PersistentBackendPageCursor? = null
        var episodes = 0
        var projected = 0
        var indexed = 0
        var already = 0

        while (true) {
            when (
                val page = source.page(
                    limit = pageSize,
                    order = PersistentBackendPageOrder.OLDEST_FIRST,
                    cursorExclusive = cursor
                )
            ) {
                EpisodePageResult.Empty -> break
                is EpisodePageResult.Loaded -> {
                    for (snapshot in page.entries) {
                        when (val result = index.project(snapshot)) {
                            is EpisodeIndexProjectionResult.Complete -> {
                                episodes += 1
                                projected += result.projected
                                indexed += result.indexed
                                already += result.alreadyIndexed
                            }
                            is EpisodeIndexProjectionResult.Incomplete -> {
                                projected += result.projected
                                indexed += result.indexed
                                already += result.alreadyIndexed
                                return EpisodeIndexRebuildResult.Incomplete(
                                    episodesScanned = episodes,
                                    projectedEntries = projected,
                                    indexedEntries = indexed,
                                    alreadyIndexedEntries = already,
                                    reason = "episodic index projection failed at ${result.failedEntryId.value}: ${result.reason}"
                                )
                            }
                        }
                    }
                    cursor = page.nextCursor ?: break
                }
                EpisodePageResult.Corrupt -> return incomplete(
                    episodes, projected, indexed, already, "episodic source page is corrupt"
                )
                is EpisodePageResult.Incompatible -> return incomplete(
                    episodes, projected, indexed, already, page.reason
                )
                is EpisodePageResult.EncryptionUnavailable -> return incomplete(
                    episodes,
                    projected,
                    indexed,
                    already,
                    "episodic source encryption unavailable: ${page.category}"
                )
                is EpisodePageResult.Failed -> return incomplete(
                    episodes, projected, indexed, already, page.reason
                )
            }
        }

        val end = source.indexSourceCheckpoint()
            ?: return incomplete(
                episodes, projected, indexed, already,
                "episodic source checkpoint disappeared during rebuild"
            )
        if (end != start) {
            return incomplete(
                episodes,
                projected,
                indexed,
                already,
                "episodic source changed during index rebuild"
            )
        }

        return when (val manifest = index.writeManifest(EpisodeIndexManifest(start, rebuiltAt))) {
            is EpisodeIndexManifestResult.Loaded -> EpisodeIndexRebuildResult.Complete(
                source = start,
                episodesScanned = episodes,
                projectedEntries = projected,
                indexedEntries = indexed,
                alreadyIndexedEntries = already
            )
            EpisodeIndexManifestResult.Missing -> incomplete(
                episodes, projected, indexed, already,
                "episodic index manifest write returned missing"
            )
            EpisodeIndexManifestResult.Corrupt -> incomplete(
                episodes, projected, indexed, already,
                "episodic index manifest is corrupt"
            )
            is EpisodeIndexManifestResult.Incompatible -> incomplete(
                episodes, projected, indexed, already, manifest.reason
            )
            is EpisodeIndexManifestResult.Failed -> incomplete(
                episodes, projected, indexed, already, manifest.reason
            )
        }
    }

    private fun incomplete(
        episodes: Int,
        projected: Int,
        indexed: Int,
        already: Int,
        reason: String
    ) = EpisodeIndexRebuildResult.Incomplete(
        episodesScanned = episodes,
        projectedEntries = projected,
        indexedEntries = indexed,
        alreadyIndexedEntries = already,
        reason = reason
    )
}
