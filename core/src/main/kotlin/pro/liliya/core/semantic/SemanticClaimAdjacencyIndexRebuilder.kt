package pro.liliya.core.semantic

import java.util.UUID
import pro.liliya.core.persistence.PersistentBackendPageCursor

internal interface SemanticClaimAdjacencyRelationSource {
    fun sourceCheckpoint(): SemanticClaimSourceCheckpoint
    fun relationPage(
        limit: Int,
        cursorExclusive: PersistentBackendPageCursor?
    ): SemanticRelationPageResult
}

internal interface SemanticClaimAdjacencyIndexWriter {
    fun readRoot(reference: SemanticClaimVersionReference): SemanticClaimAdjacencyRootLoadResult
    fun readPage(
        reference: SemanticClaimVersionReference,
        ordinal: Long
    ): SemanticClaimAdjacencyPageLoadResult
    fun writeManifest(manifest: SemanticClaimAdjacencyManifest): SemanticClaimAdjacencyWriteResult
    fun writeRoot(root: SemanticClaimAdjacencyRoot): SemanticClaimAdjacencyWriteResult
    fun writePage(page: SemanticClaimAdjacencyPage): SemanticClaimAdjacencyWriteResult
}

private class RepositorySemanticClaimAdjacencyRelationSource(
    private val repository: EncryptedPersistentSemanticClaimRepository
) : SemanticClaimAdjacencyRelationSource {
    override fun sourceCheckpoint(): SemanticClaimSourceCheckpoint =
        repository.sourceCheckpoint()

    override fun relationPage(
        limit: Int,
        cursorExclusive: PersistentBackendPageCursor?
    ): SemanticRelationPageResult =
        repository.relationPage(limit, cursorExclusive)
}

private class StoreSemanticClaimAdjacencyIndexWriter(
    private val store: EncryptedPersistentSemanticClaimAdjacencyIndexStore
) : SemanticClaimAdjacencyIndexWriter {
    override fun readRoot(
        reference: SemanticClaimVersionReference
    ): SemanticClaimAdjacencyRootLoadResult = store.readRoot(reference)

    override fun readPage(
        reference: SemanticClaimVersionReference,
        ordinal: Long
    ): SemanticClaimAdjacencyPageLoadResult = store.readPage(reference, ordinal)

    override fun writeManifest(
        manifest: SemanticClaimAdjacencyManifest
    ): SemanticClaimAdjacencyWriteResult = store.writeManifest(manifest)

    override fun writeRoot(
        root: SemanticClaimAdjacencyRoot
    ): SemanticClaimAdjacencyWriteResult = store.writeRoot(root)

    override fun writePage(
        page: SemanticClaimAdjacencyPage
    ): SemanticClaimAdjacencyWriteResult = store.writePage(page)
}

class SemanticClaimAdjacencyIndexRebuilder internal constructor(
    private val source: SemanticClaimAdjacencyRelationSource,
    private val index: SemanticClaimAdjacencyIndexWriter,
    private val policy: SemanticClaimAdjacencyPolicy = SemanticClaimAdjacencyPolicy(),
    private val epochFactory: () -> String = { UUID.randomUUID().toString() }
) {
    constructor(
        repository: EncryptedPersistentSemanticClaimRepository,
        indexStore: EncryptedPersistentSemanticClaimAdjacencyIndexStore,
        policy: SemanticClaimAdjacencyPolicy = SemanticClaimAdjacencyPolicy(),
        epochFactory: () -> String = { UUID.randomUUID().toString() }
    ) : this(
        RepositorySemanticClaimAdjacencyRelationSource(repository),
        StoreSemanticClaimAdjacencyIndexWriter(indexStore),
        policy,
        epochFactory
    )
    fun rebuild(): SemanticClaimAdjacencyRebuildResult {
        val started = source.sourceCheckpoint()
        val epoch = epochFactory()
        if (epoch.isBlank() || epoch.length > 64) {
            return failed("semantic adjacency build epoch is invalid")
        }

        writeManifest(
            SemanticClaimAdjacencyManifest(
                buildEpoch = epoch,
                source = started,
                state = SemanticClaimAdjacencyIndexState.INCOMPLETE,
                policyVersion = policy.version,
                pageEntries = policy.pageEntries,
                indexedRelationCount = 0L,
                adjacencyEntryCount = 0L
            )
        )?.let { return it }

        var cursor: PersistentBackendPageCursor? = null
        var relationCount = 0L
        var adjacencyCount = 0L

        while (true) {
            when (val page = source.relationPage(RELATION_PAGE_SIZE, cursor)) {
                SemanticRelationPageResult.Empty -> break
                SemanticRelationPageResult.Corrupt ->
                    return failed("canonical semantic relation page is corrupt")
                is SemanticRelationPageResult.Incompatible -> return failed(page.reason)
                is SemanticRelationPageResult.EncryptionUnavailable ->
                    return failed(
                        "canonical semantic relation page encryption unavailable: " + page.category
                    )
                is SemanticRelationPageResult.Failed ->
                    return SemanticClaimAdjacencyRebuildResult.Failed(page.reason, page.throwable)
                is SemanticRelationPageResult.Loaded -> {
                    for (relation in page.relations) {
                        when (relation.type) {
                            SemanticClaimRelationType.CONTRADICTS -> {
                                append(
                                    epoch,
                                    relation.source,
                                    SemanticClaimAdjacencyEntry(
                                        neighbor = relation.target,
                                        relationType = relation.type,
                                        direction = SemanticClaimAdjacencyDirection.SYMMETRIC,
                                        recordedAt = relation.recordedAt
                                    )
                                )?.let { return it }
                                append(
                                    epoch,
                                    relation.target,
                                    SemanticClaimAdjacencyEntry(
                                        neighbor = relation.source,
                                        relationType = relation.type,
                                        direction = SemanticClaimAdjacencyDirection.SYMMETRIC,
                                        recordedAt = relation.recordedAt
                                    )
                                )?.let { return it }
                                adjacencyCount = add(adjacencyCount, 2L)
                                    ?: return failed("semantic adjacency entry count overflow")
                            }
                            SemanticClaimRelationType.SUPERSEDES -> {
                                append(
                                    epoch,
                                    relation.source,
                                    SemanticClaimAdjacencyEntry(
                                        neighbor = relation.target,
                                        relationType = relation.type,
                                        direction = SemanticClaimAdjacencyDirection.OUTGOING,
                                        recordedAt = relation.recordedAt
                                    )
                                )?.let { return it }
                                append(
                                    epoch,
                                    relation.target,
                                    SemanticClaimAdjacencyEntry(
                                        neighbor = relation.source,
                                        relationType = relation.type,
                                        direction = SemanticClaimAdjacencyDirection.INCOMING,
                                        recordedAt = relation.recordedAt
                                    )
                                )?.let { return it }
                                adjacencyCount = add(adjacencyCount, 2L)
                                    ?: return failed("semantic adjacency entry count overflow")
                            }
                        }
                        relationCount = add(relationCount, 1L)
                            ?: return failed("semantic adjacency relation count overflow")
                    }
                    val next = page.nextCursor
                    if (next == null) break
                    cursor = next
                }
            }
        }

        val ended = source.sourceCheckpoint()
        if (ended != started) {
            return SemanticClaimAdjacencyRebuildResult.SourceDrift(started, ended)
        }

        writeManifest(
            SemanticClaimAdjacencyManifest(
                buildEpoch = epoch,
                source = started,
                state = SemanticClaimAdjacencyIndexState.COMPLETE,
                policyVersion = policy.version,
                pageEntries = policy.pageEntries,
                indexedRelationCount = relationCount,
                adjacencyEntryCount = adjacencyCount
            )
        )?.let { return it }

        return SemanticClaimAdjacencyRebuildResult.Complete(
            source = started,
            indexedRelations = relationCount,
            adjacencyEntries = adjacencyCount,
            buildEpoch = epoch
        )
    }

    private fun append(
        epoch: String,
        reference: SemanticClaimVersionReference,
        entry: SemanticClaimAdjacencyEntry
    ): SemanticClaimAdjacencyRebuildResult.Failed? {
        val root = when (val loaded = index.readRoot(reference)) {
            SemanticClaimAdjacencyRootLoadResult.Missing -> null
            is SemanticClaimAdjacencyRootLoadResult.Loaded ->
                loaded.root.takeIf { it.buildEpoch == epoch }
            SemanticClaimAdjacencyRootLoadResult.Corrupt ->
                return failed("semantic adjacency root is corrupt")
            is SemanticClaimAdjacencyRootLoadResult.Incompatible ->
                return failed(loaded.reason)
            is SemanticClaimAdjacencyRootLoadResult.EncryptionUnavailable ->
                return failed("semantic adjacency root encryption unavailable: " + loaded.category)
            is SemanticClaimAdjacencyRootLoadResult.Failed ->
                return SemanticClaimAdjacencyRebuildResult.Failed(
                    loaded.reason,
                    loaded.throwable
                )
        }

        val count = root?.entryCount ?: 0L
        val ordinal = count / policy.pageEntries.toLong()
        val offset = (count % policy.pageEntries.toLong()).toInt()
        val existing = if (offset == 0) {
            emptyList()
        } else {
            when (val loaded = index.readPage(reference, ordinal)) {
                is SemanticClaimAdjacencyPageLoadResult.Loaded -> {
                    val page = loaded.page
                    if (page.buildEpoch != epoch || page.entries.size != offset) {
                        return failed("semantic adjacency page does not match rebuild epoch")
                    }
                    page.entries
                }
                SemanticClaimAdjacencyPageLoadResult.Missing ->
                    return failed("semantic adjacency page missing during append")
                SemanticClaimAdjacencyPageLoadResult.Corrupt ->
                    return failed("semantic adjacency page corrupt")
                is SemanticClaimAdjacencyPageLoadResult.Incompatible ->
                    return failed(loaded.reason)
                is SemanticClaimAdjacencyPageLoadResult.EncryptionUnavailable ->
                    return failed("semantic adjacency page encryption unavailable: " + loaded.category)
                is SemanticClaimAdjacencyPageLoadResult.Failed ->
                    return SemanticClaimAdjacencyRebuildResult.Failed(
                        loaded.reason,
                        loaded.throwable
                    )
            }
        }

        if (existing.contains(entry)) return null

        val nextEntries = ArrayList<SemanticClaimAdjacencyEntry>(existing.size + 1)
        nextEntries.addAll(existing)
        nextEntries += entry
        when (
            val written = index.writePage(
                SemanticClaimAdjacencyPage(
                    buildEpoch = epoch,
                    reference = reference,
                    ordinal = ordinal,
                    entries = nextEntries
                )
            )
        ) {
            SemanticClaimAdjacencyWriteResult.Written -> Unit
            is SemanticClaimAdjacencyWriteResult.Rejected -> return failed(written.reason)
            is SemanticClaimAdjacencyWriteResult.Failed ->
                return SemanticClaimAdjacencyRebuildResult.Failed(
                    written.reason,
                    written.throwable
                )
        }

        val nextCount = add(count, 1L)
            ?: return failed("semantic adjacency root count overflow")
        val pageCount = ((nextCount - 1L) / policy.pageEntries.toLong()) + 1L
        return when (
            val written = index.writeRoot(
                SemanticClaimAdjacencyRoot(
                    buildEpoch = epoch,
                    reference = reference,
                    pageCount = pageCount,
                    entryCount = nextCount
                )
            )
        ) {
            SemanticClaimAdjacencyWriteResult.Written -> null
            is SemanticClaimAdjacencyWriteResult.Rejected -> failed(written.reason)
            is SemanticClaimAdjacencyWriteResult.Failed ->
                SemanticClaimAdjacencyRebuildResult.Failed(
                    written.reason,
                    written.throwable
                )
        }
    }

    private fun writeManifest(
        manifest: SemanticClaimAdjacencyManifest
    ): SemanticClaimAdjacencyRebuildResult.Failed? =
        when (val written = index.writeManifest(manifest)) {
            SemanticClaimAdjacencyWriteResult.Written -> null
            is SemanticClaimAdjacencyWriteResult.Rejected -> failed(written.reason)
            is SemanticClaimAdjacencyWriteResult.Failed ->
                SemanticClaimAdjacencyRebuildResult.Failed(
                    written.reason,
                    written.throwable
                )
        }

    private fun add(left: Long, right: Long): Long? =
        try {
            Math.addExact(left, right)
        } catch (_: ArithmeticException) {
            null
        }

    private fun failed(reason: String) =
        SemanticClaimAdjacencyRebuildResult.Failed(reason)

    private companion object {
        const val RELATION_PAGE_SIZE = 512
    }
}