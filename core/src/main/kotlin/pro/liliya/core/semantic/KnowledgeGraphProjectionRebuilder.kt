package pro.liliya.core.semantic

import java.util.UUID
import pro.liliya.core.persistence.PersistentBackendPageCursor

internal interface KnowledgeGraphProjectionClaimSource {
    fun sourceCheckpoint(): SemanticClaimSourceCheckpoint

    fun claimPage(
        limit: Int,
        cursorExclusive: PersistentBackendPageCursor?
    ): SemanticClaimPageResult
}

internal interface KnowledgeGraphProjectionWriter {
    fun writeManifest(
        manifest: KnowledgeGraphProjectionManifest
    ): KnowledgeGraphProjectionWriteResult

    fun writeFragment(
        fragment: KnowledgeGraphStoredFragment
    ): KnowledgeGraphProjectionWriteResult
}

private class RepositoryKnowledgeGraphProjectionClaimSource(
    private val repository: EncryptedPersistentSemanticClaimRepository
) : KnowledgeGraphProjectionClaimSource {
    override fun sourceCheckpoint(): SemanticClaimSourceCheckpoint =
        repository.sourceCheckpoint()

    override fun claimPage(
        limit: Int,
        cursorExclusive: PersistentBackendPageCursor?
    ): SemanticClaimPageResult =
        repository.claimPage(limit, cursorExclusive)
}

sealed interface KnowledgeGraphProjectionRebuildResult {
    data class Rebuilt(
        val manifest: KnowledgeGraphProjectionManifest
    ) : KnowledgeGraphProjectionRebuildResult

    data class SourceDrift(
        val started: SemanticClaimSourceCheckpoint,
        val ended: SemanticClaimSourceCheckpoint
    ) : KnowledgeGraphProjectionRebuildResult

    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : KnowledgeGraphProjectionRebuildResult
}

class KnowledgeGraphProjectionRebuilder internal constructor(
    private val source: KnowledgeGraphProjectionClaimSource,
    private val writer: KnowledgeGraphProjectionWriter,
    private val policy: KnowledgeGraphProjectionPolicy = KnowledgeGraphProjectionPolicy(),
    private val epochFactory: () -> String = { UUID.randomUUID().toString() }
) {
    constructor(
        repository: EncryptedPersistentSemanticClaimRepository,
        store: EncryptedPersistentKnowledgeGraphProjectionStore,
        policy: KnowledgeGraphProjectionPolicy = KnowledgeGraphProjectionPolicy()
    ) : this(
        source = RepositoryKnowledgeGraphProjectionClaimSource(repository),
        writer = store,
        policy = policy
    )

    fun rebuild(): KnowledgeGraphProjectionRebuildResult {
        val started = source.sourceCheckpoint()
        val epoch = epochFactory()

        if (epoch.isBlank() || epoch.length > 64) {
            return failed("knowledge graph build epoch is invalid")
        }

        val initial = KnowledgeGraphProjectionManifest(
            buildEpoch = epoch,
            source = started,
            state = KnowledgeGraphProjectionState.INCOMPLETE,
            policyVersion = policy.version,
            claimPageSize = policy.claimPageSize,
            projectedClaimCount = 0L,
            projectedNodeCount = 0L,
            projectedEdgeCount = 0L
        )
        writeManifest(initial)?.let { return it }

        var cursor: PersistentBackendPageCursor? = null
        var claimCount = 0L
        var nodeCount = 0L
        var edgeCount = 0L

        while (true) {
            when (val page = source.claimPage(policy.claimPageSize, cursor)) {
                SemanticClaimPageResult.Empty -> break

                is SemanticClaimPageResult.Loaded -> {
                    if (page.records.isEmpty()) {
                        return failed("semantic claim source returned an empty loaded page")
                    }

                    for (record in page.records) {
                        val fragment = KnowledgeGraphSingleClaimProjector.project(record)
                        val stored = KnowledgeGraphStoredFragment.from(epoch, fragment)

                        when (val written = writer.writeFragment(stored)) {
                            KnowledgeGraphProjectionWriteResult.Written -> Unit
                            is KnowledgeGraphProjectionWriteResult.Rejected ->
                                return failed(written.reason)
                            is KnowledgeGraphProjectionWriteResult.Failed ->
                                return KnowledgeGraphProjectionRebuildResult.Failed(
                                    written.reason,
                                    written.throwable
                                )
                        }

                        claimCount = addExact(claimCount, 1L, "projected claim count overflow")
                            ?: return failed("projected claim count overflow")
                        nodeCount = addExact(
                            nodeCount,
                            stored.nodes.size.toLong(),
                            "projected node count overflow"
                        ) ?: return failed("projected node count overflow")
                        edgeCount = addExact(
                            edgeCount,
                            stored.edges.size.toLong(),
                            "projected edge count overflow"
                        ) ?: return failed("projected edge count overflow")
                    }

                    val next = page.nextCursor
                    if (next == null) break
                    if (next == cursor) {
                        return failed("semantic claim source cursor did not advance")
                    }
                    cursor = next
                }

                SemanticClaimPageResult.Corrupt ->
                    return failed("semantic claim source is corrupt")

                is SemanticClaimPageResult.Incompatible ->
                    return failed(page.reason)

                is SemanticClaimPageResult.EncryptionUnavailable ->
                    return failed(
                        "semantic claim source encryption unavailable: " + page.category
                    )

                is SemanticClaimPageResult.Failed ->
                    return KnowledgeGraphProjectionRebuildResult.Failed(
                        page.reason,
                        page.throwable
                    )
            }
        }

        val ended = source.sourceCheckpoint()
        if (ended != started) {
            return KnowledgeGraphProjectionRebuildResult.SourceDrift(started, ended)
        }

        val complete = KnowledgeGraphProjectionManifest(
            buildEpoch = epoch,
            source = started,
            state = KnowledgeGraphProjectionState.COMPLETE,
            policyVersion = policy.version,
            claimPageSize = policy.claimPageSize,
            projectedClaimCount = claimCount,
            projectedNodeCount = nodeCount,
            projectedEdgeCount = edgeCount
        )
        writeManifest(complete)?.let { return it }

        return KnowledgeGraphProjectionRebuildResult.Rebuilt(complete)
    }

    private fun writeManifest(
        manifest: KnowledgeGraphProjectionManifest
    ): KnowledgeGraphProjectionRebuildResult.Failed? =
        when (val written = writer.writeManifest(manifest)) {
            KnowledgeGraphProjectionWriteResult.Written -> null
            is KnowledgeGraphProjectionWriteResult.Rejected -> failed(written.reason)
            is KnowledgeGraphProjectionWriteResult.Failed ->
                KnowledgeGraphProjectionRebuildResult.Failed(
                    written.reason,
                    written.throwable
                )
        }

    private fun failed(reason: String): KnowledgeGraphProjectionRebuildResult.Failed =
        KnowledgeGraphProjectionRebuildResult.Failed(reason)

    private fun addExact(left: Long, right: Long, reason: String): Long? =
        try {
            Math.addExact(left, right)
        } catch (_: ArithmeticException) {
            null
        }
}
