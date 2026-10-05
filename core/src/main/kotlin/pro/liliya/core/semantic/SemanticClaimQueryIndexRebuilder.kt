package pro.liliya.core.semantic

import java.util.UUID
import pro.liliya.core.persistence.PersistentBackendPageCursor

class SemanticClaimQueryIndexRebuilder(
    private val repository: EncryptedPersistentSemanticClaimRepository,
    private val indexStore: EncryptedPersistentSemanticClaimQueryIndexStore,
    private val epochFactory: () -> String = { UUID.randomUUID().toString() }
) {
    fun rebuild(): SemanticClaimQueryIndexRebuildResult {
        val started = repository.sourceCheckpoint()
        val epoch = epochFactory()
        if (epoch.isBlank() ||
            epoch.length > SemanticClaimQueryIndexManifest.MAX_EPOCH_LENGTH
        ) {
            return SemanticClaimQueryIndexRebuildResult.Failed(
                "semantic claim query build epoch is invalid"
            )
        }

        when (
            val written = indexStore.writeManifest(
                SemanticClaimQueryIndexManifest(
                    buildEpoch = epoch,
                    source = started,
                    state = SemanticClaimQueryIndexState.INCOMPLETE
                )
            )
        ) {
            SemanticClaimQueryIndexWriteResult.Written -> Unit
            is SemanticClaimQueryIndexWriteResult.Rejected ->
                return SemanticClaimQueryIndexRebuildResult.Failed(written.reason)
            is SemanticClaimQueryIndexWriteResult.Failed ->
                return SemanticClaimQueryIndexRebuildResult.Failed(
                    written.reason,
                    written.throwable
                )
        }

        var cursor: PersistentBackendPageCursor? = null
        var indexed = 0L

        while (true) {
            when (
                val page = repository.claimPage(
                    limit = CLAIM_PAGE_SIZE,
                    cursorExclusive = cursor
                )
            ) {
                SemanticClaimPageResult.Empty -> break
                SemanticClaimPageResult.Corrupt ->
                    return SemanticClaimQueryIndexRebuildResult.Failed(
                        "canonical semantic claim page is corrupt"
                    )
                is SemanticClaimPageResult.Incompatible ->
                    return SemanticClaimQueryIndexRebuildResult.Failed(page.reason)
                is SemanticClaimPageResult.EncryptionUnavailable ->
                    return SemanticClaimQueryIndexRebuildResult.Failed(
                        "canonical semantic claim page encryption unavailable: " +
                            page.category
                    )
                is SemanticClaimPageResult.Failed ->
                    return SemanticClaimQueryIndexRebuildResult.Failed(
                        page.reason,
                        page.throwable
                    )
                is SemanticClaimPageResult.Loaded -> {
                    for (record in page.records) {
                        val appended = append(epoch, record)
                        if (appended != null) return appended
                        indexed = try {
                            Math.addExact(indexed, 1L)
                        } catch (_: ArithmeticException) {
                            return SemanticClaimQueryIndexRebuildResult.Failed(
                                "semantic claim query index count overflow"
                            )
                        }
                    }
                    val next = page.nextCursor
                    if (next == null) break
                    cursor = next
                }
            }
        }

        val ended = repository.sourceCheckpoint()
        if (ended != started) {
            return SemanticClaimQueryIndexRebuildResult.SourceDrift(started, ended)
        }

        return when (
            val written = indexStore.writeManifest(
                SemanticClaimQueryIndexManifest(
                    buildEpoch = epoch,
                    source = started,
                    state = SemanticClaimQueryIndexState.COMPLETE
                )
            )
        ) {
            SemanticClaimQueryIndexWriteResult.Written ->
                SemanticClaimQueryIndexRebuildResult.Complete(
                    source = started,
                    indexedClaims = indexed,
                    buildEpoch = epoch
                )
            is SemanticClaimQueryIndexWriteResult.Rejected ->
                SemanticClaimQueryIndexRebuildResult.Failed(written.reason)
            is SemanticClaimQueryIndexWriteResult.Failed ->
                SemanticClaimQueryIndexRebuildResult.Failed(
                    written.reason,
                    written.throwable
                )
        }
    }

    private fun append(
        epoch: String,
        record: SemanticClaimRecord
    ): SemanticClaimQueryIndexRebuildResult.Failed? {
        val group = SemanticClaimIds.forConflictGroup(record.identity)
        val root = when (val loaded = indexStore.readRoot(group)) {
            SemanticClaimQueryRootLoadResult.Missing -> null
            is SemanticClaimQueryRootLoadResult.Loaded ->
                loaded.root.takeIf { it.buildEpoch == epoch }
            SemanticClaimQueryRootLoadResult.Corrupt ->
                return SemanticClaimQueryIndexRebuildResult.Failed(
                    "semantic claim query group root is corrupt"
                )
            is SemanticClaimQueryRootLoadResult.Incompatible ->
                return SemanticClaimQueryIndexRebuildResult.Failed(loaded.reason)
            is SemanticClaimQueryRootLoadResult.EncryptionUnavailable ->
                return SemanticClaimQueryIndexRebuildResult.Failed(
                    "semantic claim query root encryption unavailable: " +
                        loaded.category
                )
            is SemanticClaimQueryRootLoadResult.Failed ->
                return SemanticClaimQueryIndexRebuildResult.Failed(
                    loaded.reason,
                    loaded.throwable
                )
        }

        val entryCount = root?.entryCount ?: 0L
        val pageOrdinal = entryCount / SemanticClaimQueryPage.MAX_ENTRIES
        val offset = (entryCount % SemanticClaimQueryPage.MAX_ENTRIES).toInt()

        val currentEntries = if (offset == 0) {
            emptyList()
        } else {
            when (val loaded = indexStore.readPage(group, pageOrdinal)) {
                is SemanticClaimQueryPageLoadResult.Loaded -> {
                    val page = loaded.page
                    if (
                        page.buildEpoch != epoch ||
                        page.entries.size != offset
                    ) {
                        return SemanticClaimQueryIndexRebuildResult.Failed(
                            "semantic claim query page state does not match rebuild epoch"
                        )
                    }
                    page.entries
                }
                SemanticClaimQueryPageLoadResult.Missing ->
                    return SemanticClaimQueryIndexRebuildResult.Failed(
                        "semantic claim query page is missing during append"
                    )
                SemanticClaimQueryPageLoadResult.Corrupt ->
                    return SemanticClaimQueryIndexRebuildResult.Failed(
                        "semantic claim query page is corrupt"
                    )
                is SemanticClaimQueryPageLoadResult.Incompatible ->
                    return SemanticClaimQueryIndexRebuildResult.Failed(loaded.reason)
                is SemanticClaimQueryPageLoadResult.EncryptionUnavailable ->
                    return SemanticClaimQueryIndexRebuildResult.Failed(
                        "semantic claim query page encryption unavailable: " +
                            loaded.category
                    )
                is SemanticClaimQueryPageLoadResult.Failed ->
                    return SemanticClaimQueryIndexRebuildResult.Failed(
                        loaded.reason,
                        loaded.throwable
                    )
            }
        }

        val reference = SemanticClaimVersionReference(record.id, record.version)
        val nextEntries = ArrayList<SemanticClaimVersionReference>(
            currentEntries.size + 1
        )
        nextEntries.addAll(currentEntries)
        nextEntries += reference

        when (
            val written = indexStore.writePage(
                SemanticClaimQueryPage(
                    buildEpoch = epoch,
                    conflictGroupId = group,
                    ordinal = pageOrdinal,
                    entries = nextEntries
                )
            )
        ) {
            SemanticClaimQueryIndexWriteResult.Written -> Unit
            is SemanticClaimQueryIndexWriteResult.Rejected ->
                return SemanticClaimQueryIndexRebuildResult.Failed(written.reason)
            is SemanticClaimQueryIndexWriteResult.Failed ->
                return SemanticClaimQueryIndexRebuildResult.Failed(
                    written.reason,
                    written.throwable
                )
        }

        val nextCount = try {
            Math.addExact(entryCount, 1L)
        } catch (_: ArithmeticException) {
            return SemanticClaimQueryIndexRebuildResult.Failed(
                "semantic claim query group entry count overflow"
            )
        }
        val nextPageCount =
            ((nextCount - 1L) / SemanticClaimQueryPage.MAX_ENTRIES) + 1L

        return when (
            val written = indexStore.writeRoot(
                SemanticClaimQueryGroupRoot(
                    buildEpoch = epoch,
                    conflictGroupId = group,
                    pageCount = nextPageCount,
                    entryCount = nextCount
                )
            )
        ) {
            SemanticClaimQueryIndexWriteResult.Written -> null
            is SemanticClaimQueryIndexWriteResult.Rejected ->
                SemanticClaimQueryIndexRebuildResult.Failed(written.reason)
            is SemanticClaimQueryIndexWriteResult.Failed ->
                SemanticClaimQueryIndexRebuildResult.Failed(
                    written.reason,
                    written.throwable
                )
        }
    }

    private companion object {
        const val CLAIM_PAGE_SIZE = 512
    }
}
