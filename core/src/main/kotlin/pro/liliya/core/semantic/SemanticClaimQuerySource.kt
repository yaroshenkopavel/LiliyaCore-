package pro.liliya.core.semantic

class SemanticClaimQuerySource(
    private val repository: EncryptedPersistentSemanticClaimRepository,
    private val indexStore: EncryptedPersistentSemanticClaimQueryIndexStore
) {
    fun query(
        identity: SemanticClaimIdentity,
        limit: Int,
        cursorExclusive: SemanticClaimQueryCursor? = null
    ): SemanticClaimQueryResult =
        query(
            conflictGroupId = SemanticClaimIds.forConflictGroup(identity),
            limit = limit,
            cursorExclusive = cursorExclusive
        )

    fun query(
        conflictGroupId: SemanticClaimConflictGroupId,
        limit: Int,
        cursorExclusive: SemanticClaimQueryCursor? = null
    ): SemanticClaimQueryResult {
        if (limit !in 1..MAX_QUERY_LIMIT) {
            return SemanticClaimQueryResult.Rejected(
                "semantic claim query limit must be in 1..$MAX_QUERY_LIMIT"
            )
        }

        val manifest = when (val loaded = indexStore.readManifest()) {
            SemanticClaimQueryManifestLoadResult.Missing ->
                return SemanticClaimQueryResult.FallbackRequired(
                    "semantic claim query index manifest is missing"
                )
            is SemanticClaimQueryManifestLoadResult.Loaded -> loaded.manifest
            SemanticClaimQueryManifestLoadResult.Corrupt ->
                return SemanticClaimQueryResult.FallbackRequired(
                    "semantic claim query index manifest is corrupt"
                )
            is SemanticClaimQueryManifestLoadResult.Incompatible ->
                return SemanticClaimQueryResult.FallbackRequired(loaded.reason)
            is SemanticClaimQueryManifestLoadResult.EncryptionUnavailable ->
                return SemanticClaimQueryResult.FallbackRequired(
                    "semantic claim query index manifest encryption unavailable: " +
                        loaded.category
                )
            is SemanticClaimQueryManifestLoadResult.Failed ->
                return SemanticClaimQueryResult.Failed(
                    loaded.reason,
                    loaded.throwable
                )
        }

        if (manifest.state != SemanticClaimQueryIndexState.COMPLETE) {
            return SemanticClaimQueryResult.FallbackRequired(
                "semantic claim query index is incomplete"
            )
        }

        val source = repository.sourceCheckpoint()
        if (source != manifest.source) {
            return SemanticClaimQueryResult.FallbackRequired(
                "semantic claim query index source checkpoint is stale"
            )
        }

        val root = when (val loaded = indexStore.readRoot(conflictGroupId)) {
            SemanticClaimQueryRootLoadResult.Missing ->
                return SemanticClaimQueryResult.FallbackRequired(
                    "semantic claim query group root is missing"
                )
            is SemanticClaimQueryRootLoadResult.Loaded -> loaded.root
            SemanticClaimQueryRootLoadResult.Corrupt ->
                return SemanticClaimQueryResult.FallbackRequired(
                    "semantic claim query group root is corrupt"
                )
            is SemanticClaimQueryRootLoadResult.Incompatible ->
                return SemanticClaimQueryResult.FallbackRequired(loaded.reason)
            is SemanticClaimQueryRootLoadResult.EncryptionUnavailable ->
                return SemanticClaimQueryResult.FallbackRequired(
                    "semantic claim query group root encryption unavailable: " +
                        loaded.category
                )
            is SemanticClaimQueryRootLoadResult.Failed ->
                return SemanticClaimQueryResult.Failed(
                    loaded.reason,
                    loaded.throwable
                )
        }

        if (
            root.buildEpoch != manifest.buildEpoch ||
            root.conflictGroupId != conflictGroupId
        ) {
            return SemanticClaimQueryResult.FallbackRequired(
                "semantic claim query group root does not match active build"
            )
        }

        var pageOrdinal = cursorExclusive?.pageOrdinal ?: 0L
        var offset = cursorExclusive?.offset ?: 0
        if (pageOrdinal >= root.pageCount) {
            return SemanticClaimQueryResult.Rejected(
                "semantic claim query cursor is outside group page range"
            )
        }

        val records = ArrayList<SemanticClaimRecord>(limit)
        var scanned = 0
        var nextCursor: SemanticClaimQueryCursor? = null

        while (records.size < limit && pageOrdinal < root.pageCount) {
            val page = when (
                val loaded = indexStore.readPage(conflictGroupId, pageOrdinal)
            ) {
                SemanticClaimQueryPageLoadResult.Missing ->
                    return SemanticClaimQueryResult.FallbackRequired(
                        "semantic claim query page is missing"
                    )
                is SemanticClaimQueryPageLoadResult.Loaded -> loaded.page
                SemanticClaimQueryPageLoadResult.Corrupt ->
                    return SemanticClaimQueryResult.FallbackRequired(
                        "semantic claim query page is corrupt"
                    )
                is SemanticClaimQueryPageLoadResult.Incompatible ->
                    return SemanticClaimQueryResult.FallbackRequired(loaded.reason)
                is SemanticClaimQueryPageLoadResult.EncryptionUnavailable ->
                    return SemanticClaimQueryResult.FallbackRequired(
                        "semantic claim query page encryption unavailable: " +
                            loaded.category
                    )
                is SemanticClaimQueryPageLoadResult.Failed ->
                    return SemanticClaimQueryResult.Failed(
                        loaded.reason,
                        loaded.throwable
                    )
            }

            if (
                page.buildEpoch != manifest.buildEpoch ||
                page.conflictGroupId != conflictGroupId ||
                page.ordinal != pageOrdinal
            ) {
                return SemanticClaimQueryResult.FallbackRequired(
                    "semantic claim query page does not match active build"
                )
            }

            if (offset > page.entries.size) {
                return SemanticClaimQueryResult.Rejected(
                    "semantic claim query cursor offset is invalid"
                )
            }
            if (offset == page.entries.size) {
                pageOrdinal += 1L
                offset = 0
                continue
            }

            var index = offset
            while (index < page.entries.size && records.size < limit) {
                val reference = page.entries[index]
                scanned += 1
                val canonical = when (val loaded = repository.readExact(reference)) {
                    SemanticClaimReadResult.Missing ->
                        return SemanticClaimQueryResult.FallbackRequired(
                            "canonical semantic claim referenced by index is missing"
                        )
                    is SemanticClaimReadResult.Found -> loaded.record
                    SemanticClaimReadResult.Corrupt ->
                        return SemanticClaimQueryResult.FallbackRequired(
                            "canonical semantic claim referenced by index is corrupt"
                        )
                    is SemanticClaimReadResult.Incompatible ->
                        return SemanticClaimQueryResult.FallbackRequired(loaded.reason)
                    is SemanticClaimReadResult.EncryptionUnavailable ->
                        return SemanticClaimQueryResult.FallbackRequired(
                            "canonical semantic claim encryption unavailable: " +
                                loaded.category
                        )
                    is SemanticClaimReadResult.Failed ->
                        return SemanticClaimQueryResult.Failed(
                            loaded.reason,
                            loaded.throwable
                        )
                }

                if (
                    canonical.id != reference.claimId ||
                    canonical.version != reference.version ||
                    SemanticClaimIds.forConflictGroup(canonical.identity) !=
                    conflictGroupId
                ) {
                    return SemanticClaimQueryResult.FallbackRequired(
                        "semantic claim query index reference does not match canonical claim"
                    )
                }

                records += canonical
                index += 1
            }

            nextCursor = when {
                index < page.entries.size ->
                    SemanticClaimQueryCursor(pageOrdinal, index)
                pageOrdinal + 1L < root.pageCount ->
                    SemanticClaimQueryCursor(pageOrdinal + 1L, 0)
                else -> null
            }

            if (records.size == limit) break
            pageOrdinal += 1L
            offset = 0
        }

        return SemanticClaimQueryResult.Candidates(
            records = records,
            nextCursor = nextCursor,
            audit = SemanticClaimQueryAudit(
                source = source,
                conflictGroupId = conflictGroupId,
                scannedIndexEntries = scanned,
                returnedCandidates = records.size
            )
        )
    }

    companion object {
        const val MAX_QUERY_LIMIT = 512
    }
}
