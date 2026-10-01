package pro.liliya.android.semanticprovider

import pro.liliya.core.persistence.PersistentBackendPageCursor
import pro.liliya.core.semantic.EncryptedPersistentSemanticClaimRepository
import pro.liliya.core.semantic.SemanticClaimObject
import pro.liliya.core.semantic.SemanticClaimPageResult
import pro.liliya.core.semantic.SemanticClaimSourceCheckpoint
import pro.liliya.core.semantic.SemanticClaimVersionReference

internal interface SemanticClaimVectorRebuildSource {
    fun sourceCheckpoint(): SemanticClaimSourceCheckpoint
    fun claimPage(
        limit: Int,
        cursorExclusive: PersistentBackendPageCursor?
    ): SemanticClaimPageResult
}

private class RepositorySemanticClaimVectorRebuildSource(
    private val repository: EncryptedPersistentSemanticClaimRepository
) : SemanticClaimVectorRebuildSource {
    override fun sourceCheckpoint(): SemanticClaimSourceCheckpoint =
        repository.sourceCheckpoint()

    override fun claimPage(
        limit: Int,
        cursorExclusive: PersistentBackendPageCursor?
    ): SemanticClaimPageResult =
        repository.claimPage(limit = limit, cursorExclusive = cursorExclusive)
}

internal fun interface SemanticClaimVectorPassageEmbeddingPort {
    fun embedPassage(text: String): OfflineSemanticSharedEmbeddingResult
}

internal sealed interface SemanticClaimVectorProjectionRebuildResult {
    data class Complete(
        val source: SemanticClaimSourceCheckpoint,
        val indexGeneration: Long,
        val shardCount: Long,
        val indexedEntryCount: Long,
        val routingRootSha256: String?
    ) : SemanticClaimVectorProjectionRebuildResult

    data class SourceDrift(
        val started: SemanticClaimSourceCheckpoint,
        val ended: SemanticClaimSourceCheckpoint
    ) : SemanticClaimVectorProjectionRebuildResult

    data class Failed(val reason: String, val throwable: Throwable? = null) :
        SemanticClaimVectorProjectionRebuildResult
}

internal class SemanticClaimVectorProjectionRebuilder(
    private val source: SemanticClaimVectorRebuildSource,
    private val embedding: SemanticClaimVectorPassageEmbeddingPort,
    private val projectionStore: SemanticClaimVectorProjectionStore,
    private val routingStore: SemanticClaimVectorRoutingStore
) {
    fun rebuild(indexGeneration: Long): SemanticClaimVectorProjectionRebuildResult {
        if (indexGeneration <= 0L) {
            return failed("semantic claim vector index generation must be positive")
        }

        val started = source.sourceCheckpoint()
        val incomplete = SemanticClaimVectorProjectionManifest(
            profileId = SemanticModelProfileV01.PROFILE_ID,
            profileGeneration = SemanticModelProfileV01.PROFILE_GENERATION.value,
            indexGeneration = indexGeneration,
            source = started,
            state = SemanticClaimVectorProjectionState.INCOMPLETE,
            shardEntryLimit = SHARD_ENTRY_LIMIT,
            shardCount = 0,
            indexedEntryCount = 0
        )
        if (!projectionStore.writeManifest(incomplete)) {
            return failed("semantic claim vector incomplete manifest write failed")
        }

        val routingBuilder = SemanticClaimVectorRoutingBuilder(routingStore)
        val pending = ArrayList<SemanticClaimVectorProjectionEntry>(SHARD_ENTRY_LIMIT)
        var cursor: PersistentBackendPageCursor? = null
        var shardCount = 0L
        var indexedEntryCount = 0L

        fun flush(): SemanticClaimVectorProjectionRebuildResult.Failed? {
            if (pending.isEmpty()) return null
            val ordinal = shardCount
            val shard = try {
                SemanticClaimVectorProjectionShard(
                    indexGeneration = indexGeneration,
                    ordinal = ordinal,
                    entries = pending.toList()
                )
            } catch (failure: Exception) {
                pending.forEach { it.vector.clear() }
                pending.clear()
                return failed("semantic claim vector shard construction failed", failure)
            }

            val envelope = try {
                envelopeOf(pending)
            } catch (failure: Exception) {
                pending.forEach { it.vector.clear() }
                pending.clear()
                return failed("semantic claim vector shard envelope failed", failure)
            }
            val digest = projectionStore.writeShard(shard)
            if (digest == null) {
                pending.forEach { it.vector.clear() }
                pending.clear()
                return failed("semantic claim vector shard write failed")
            }
            val descriptor = SemanticClaimVectorShardDescriptor(
                indexGeneration = indexGeneration,
                ordinal = ordinal,
                entryCount = pending.size,
                blobSha256 = digest
            )
            if (!routingBuilder.append(descriptor, envelope)) {
                pending.forEach { it.vector.clear() }
                pending.clear()
                return failed("semantic claim vector routing append failed")
            }

            val written = pending.size.toLong()
            pending.forEach { it.vector.clear() }
            pending.clear()
            shardCount = try {
                Math.addExact(shardCount, 1L)
            } catch (_: ArithmeticException) {
                return failed("semantic claim vector shard count overflow")
            }
            indexedEntryCount = try {
                Math.addExact(indexedEntryCount, written)
            } catch (_: ArithmeticException) {
                return failed("semantic claim vector entry count overflow")
            }
            return null
        }

        while (true) {
            when (val page = source.claimPage(SOURCE_PAGE_SIZE, cursor)) {
                SemanticClaimPageResult.Empty -> break
                SemanticClaimPageResult.Corrupt ->
                    return cleanupAndFail(pending, "canonical semantic claim page is corrupt")
                is SemanticClaimPageResult.Incompatible ->
                    return cleanupAndFail(pending, page.reason)
                is SemanticClaimPageResult.EncryptionUnavailable ->
                    return cleanupAndFail(
                        pending,
                        "canonical semantic claim page encryption unavailable: " + page.category
                    )
                is SemanticClaimPageResult.Failed -> {
                    pending.forEach { it.vector.clear() }
                    return SemanticClaimVectorProjectionRebuildResult.Failed(
                        page.reason, page.throwable
                    )
                }
                is SemanticClaimPageResult.Loaded -> {
                    for (record in page.records) {
                        val text = (record.objectValue as? SemanticClaimObject.Text)
                            ?.value ?: continue
                        val vector = when (val embedded = embedding.embedPassage(text)) {
                            is OfflineSemanticSharedEmbeddingResult.Embedded -> embedded.vector
                            OfflineSemanticSharedEmbeddingResult.Busy ->
                                return cleanupAndFail(
                                    pending, "semantic embedding session is busy"
                                )
                            OfflineSemanticSharedEmbeddingResult.NotReady ->
                                return cleanupAndFail(
                                    pending, "semantic embedding session is not ready"
                                )
                            OfflineSemanticSharedEmbeddingResult.ResourceRejected ->
                                return cleanupAndFail(
                                    pending, "semantic claim passage exceeds resource bounds"
                                )
                            OfflineSemanticSharedEmbeddingResult.RequestRejected ->
                                return cleanupAndFail(
                                    pending, "semantic claim passage is rejected"
                                )
                            OfflineSemanticSharedEmbeddingResult.SessionFailed ->
                                return cleanupAndFail(
                                    pending, "semantic embedding session failed"
                                )
                            OfflineSemanticSharedEmbeddingResult.OperationFailed ->
                                return cleanupAndFail(
                                    pending, "semantic embedding operation failed"
                                )
                        }
                        pending += SemanticClaimVectorProjectionEntry(
                            reference = SemanticClaimVersionReference(
                                record.id, record.version
                            ),
                            vector = vector
                        )
                        if (pending.size == SHARD_ENTRY_LIMIT) {
                            flush()?.let { return it }
                        }
                    }

                    val next = page.nextCursor
                    if (next == null) break
                    cursor = next
                }
            }
        }

        flush()?.let { return it }

        val ended = source.sourceCheckpoint()
        if (ended != started) {
            return SemanticClaimVectorProjectionRebuildResult.SourceDrift(started, ended)
        }

        val routingRoot = if (indexedEntryCount == 0L) {
            null
        } else {
            routingBuilder.finish()
                ?: return failed("semantic claim vector routing publication failed")
        }

        val complete = SemanticClaimVectorProjectionManifest(
            profileId = SemanticModelProfileV01.PROFILE_ID,
            profileGeneration = SemanticModelProfileV01.PROFILE_GENERATION.value,
            indexGeneration = indexGeneration,
            source = started,
            state = SemanticClaimVectorProjectionState.COMPLETE,
            shardEntryLimit = SHARD_ENTRY_LIMIT,
            shardCount = shardCount,
            indexedEntryCount = indexedEntryCount,
            routingRootSha256 = routingRoot
        )
        if (!projectionStore.writeManifest(complete)) {
            return failed("semantic claim vector complete manifest write failed")
        }

        return SemanticClaimVectorProjectionRebuildResult.Complete(
            source = started,
            indexGeneration = indexGeneration,
            shardCount = shardCount,
            indexedEntryCount = indexedEntryCount,
            routingRootSha256 = routingRoot
        )
    }

    private fun cleanupAndFail(
        pending: MutableList<SemanticClaimVectorProjectionEntry>,
        reason: String
    ): SemanticClaimVectorProjectionRebuildResult.Failed {
        pending.forEach { it.vector.clear() }
        pending.clear()
        return failed(reason)
    }

    private fun envelopeOf(
        entries: List<SemanticClaimVectorProjectionEntry>
    ): SemanticShardRoutingEnvelope {
        require(entries.isNotEmpty())
        val min = FloatArray(SemanticEmbeddingVector.DIMENSION) { Float.POSITIVE_INFINITY }
        val max = FloatArray(SemanticEmbeddingVector.DIMENSION) { Float.NEGATIVE_INFINITY }
        for (entry in entries) {
            val values = entry.vector.copyValues()
            try {
                for (index in values.indices) {
                    if (values[index] < min[index]) min[index] = values[index]
                    if (values[index] > max[index]) max[index] = values[index]
                }
            } finally {
                values.fill(0f)
            }
        }
        return SemanticShardRoutingEnvelope.fromBounds(min, max)
    }

    private fun failed(
        reason: String,
        throwable: Throwable? = null
    ) = SemanticClaimVectorProjectionRebuildResult.Failed(reason, throwable)

    companion object {
        const val SHARD_ENTRY_LIMIT = 128
        const val SOURCE_PAGE_SIZE = 128

        fun fromRepository(
            repository: EncryptedPersistentSemanticClaimRepository,
            provider: OfflineSemanticProviderComposition,
            storage: AndroidOfflineSemanticShardStorage
        ): SemanticClaimVectorProjectionRebuilder =
            SemanticClaimVectorProjectionRebuilder(
                source = RepositorySemanticClaimVectorRebuildSource(repository),
                embedding = SemanticClaimVectorPassageEmbeddingPort(
                    provider::embedSharedClaimPassage
                ),
                projectionStore = SemanticClaimVectorProjectionStore(storage),
                routingStore = SemanticClaimVectorRoutingStore(storage)
            )
    }
}