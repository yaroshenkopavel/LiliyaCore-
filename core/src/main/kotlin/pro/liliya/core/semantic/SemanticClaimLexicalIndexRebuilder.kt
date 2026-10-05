package pro.liliya.core.semantic

import java.util.TreeMap
import java.util.UUID
import pro.liliya.core.persistence.PersistentBackendPageCursor
import pro.liliya.core.retrieval.compareUtf8

class SemanticClaimLexicalIndexRebuilder(
    private val repository: EncryptedPersistentSemanticClaimRepository,
    private val indexStore: EncryptedPersistentSemanticClaimLexicalIndexStore,
    private val policy: SemanticClaimLexicalPolicy = SemanticClaimLexicalPolicy(),
    private val epochFactory: () -> String = { UUID.randomUUID().toString() }
) {
    fun rebuild(): SemanticClaimLexicalRebuildResult {
        val started = repository.sourceCheckpoint()
        val epoch = epochFactory()
        if (
            epoch.isBlank() ||
            epoch.length > SemanticClaimLexicalManifest.MAX_EPOCH_LENGTH
        ) {
            return SemanticClaimLexicalRebuildResult.Failed(
                "semantic lexical build epoch is invalid"
            )
        }

        val incomplete = SemanticClaimLexicalManifest(
            buildEpoch = epoch,
            source = started,
            state = SemanticClaimLexicalIndexState.INCOMPLETE,
            policyVersion = policy.version,
            tokenizerVersion = policy.tokenizerVersion,
            postingPageEntries = policy.postingPageEntries,
            bm25K1 = policy.bm25K1,
            bm25B = policy.bm25B,
            indexedDocumentCount = 0L,
            totalIndexedTokenCount = 0L,
            truncatedDocumentCount = 0L
        )
        writeManifest(incomplete)?.let { return it }

        var cursor: PersistentBackendPageCursor? = null
        var indexedDocuments = 0L
        var totalTokens = 0L
        var truncatedDocuments = 0L

        while (true) {
            when (
                val page = repository.claimPage(
                    limit = CLAIM_PAGE_SIZE,
                    cursorExclusive = cursor
                )
            ) {
                SemanticClaimPageResult.Empty -> break
                SemanticClaimPageResult.Corrupt ->
                    return failed("canonical semantic claim page is corrupt")
                is SemanticClaimPageResult.Incompatible ->
                    return failed(page.reason)
                is SemanticClaimPageResult.EncryptionUnavailable ->
                    return failed(
                        "canonical semantic claim page encryption unavailable: " +
                            page.category
                    )
                is SemanticClaimPageResult.Failed ->
                    return SemanticClaimLexicalRebuildResult.Failed(
                        page.reason,
                        page.throwable
                    )
                is SemanticClaimPageResult.Loaded -> {
                    for (record in page.records) {
                        val text = (record.objectValue as? SemanticClaimObject.Text)
                            ?.value ?: continue
                        val tokenization =
                            SemanticClaimLexicalTokenizer.tokenizeDocument(text)
                        if (tokenization.tokens.isEmpty()) continue

                        val frequencies = TreeMap<String, Int> { left, right ->
                            compareUtf8(left, right)
                        }
                        tokenization.tokens.forEach { token ->
                            frequencies[token] = (frequencies[token] ?: 0) + 1
                        }

                        val reference =
                            SemanticClaimVersionReference(record.id, record.version)
                        for ((token, frequency) in frequencies) {
                            appendPosting(
                                epoch = epoch,
                                token = token,
                                posting = SemanticClaimLexicalPosting(
                                    reference = reference,
                                    termFrequency = frequency,
                                    documentLength = tokenization.tokens.size
                                )
                            )?.let { return it }
                        }

                        indexedDocuments = addExact(
                            indexedDocuments,
                            1L,
                            "semantic lexical indexed-document count overflow"
                        ) ?: return failed(
                            "semantic lexical indexed-document count overflow"
                        )
                        totalTokens = addExact(
                            totalTokens,
                            tokenization.tokens.size.toLong(),
                            "semantic lexical token count overflow"
                        ) ?: return failed("semantic lexical token count overflow")
                        if (tokenization.truncated) {
                            truncatedDocuments = addExact(
                                truncatedDocuments,
                                1L,
                                "semantic lexical truncated-document count overflow"
                            ) ?: return failed(
                                "semantic lexical truncated-document count overflow"
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
            return SemanticClaimLexicalRebuildResult.SourceDrift(started, ended)
        }

        val complete = SemanticClaimLexicalManifest(
            buildEpoch = epoch,
            source = started,
            state = SemanticClaimLexicalIndexState.COMPLETE,
            policyVersion = policy.version,
            tokenizerVersion = policy.tokenizerVersion,
            postingPageEntries = policy.postingPageEntries,
            bm25K1 = policy.bm25K1,
            bm25B = policy.bm25B,
            indexedDocumentCount = indexedDocuments,
            totalIndexedTokenCount = totalTokens,
            truncatedDocumentCount = truncatedDocuments
        )
        writeManifest(complete)?.let { return it }

        return SemanticClaimLexicalRebuildResult.Complete(
            source = started,
            indexedDocuments = indexedDocuments,
            totalIndexedTokens = totalTokens,
            truncatedDocuments = truncatedDocuments,
            buildEpoch = epoch
        )
    }

    private fun appendPosting(
        epoch: String,
        token: String,
        posting: SemanticClaimLexicalPosting
    ): SemanticClaimLexicalRebuildResult.Failed? {
        val root = when (val loaded = indexStore.readRoot(token)) {
            SemanticClaimLexicalRootLoadResult.Missing -> null
            is SemanticClaimLexicalRootLoadResult.Loaded ->
                loaded.root.takeIf { it.buildEpoch == epoch }
            SemanticClaimLexicalRootLoadResult.Corrupt ->
                return failed("semantic lexical token root is corrupt")
            is SemanticClaimLexicalRootLoadResult.Incompatible ->
                return failed(loaded.reason)
            is SemanticClaimLexicalRootLoadResult.EncryptionUnavailable ->
                return failed(
                    "semantic lexical token root encryption unavailable: " +
                        loaded.category
                )
            is SemanticClaimLexicalRootLoadResult.Failed ->
                return SemanticClaimLexicalRebuildResult.Failed(
                    loaded.reason,
                    loaded.throwable
                )
        }

        val postingCount = root?.postingCount ?: 0L
        val pageOrdinal = postingCount / policy.postingPageEntries.toLong()
        val pageOffset =
            (postingCount % policy.postingPageEntries.toLong()).toInt()

        val existingEntries = if (pageOffset == 0) {
            emptyList()
        } else {
            when (val loaded = indexStore.readPage(token, pageOrdinal)) {
                is SemanticClaimLexicalPageLoadResult.Loaded -> {
                    val page = loaded.page
                    if (
                        page.buildEpoch != epoch ||
                        page.entries.size != pageOffset
                    ) {
                        return failed(
                            "semantic lexical posting page does not match rebuild epoch"
                        )
                    }
                    page.entries
                }
                SemanticClaimLexicalPageLoadResult.Missing ->
                    return failed("semantic lexical posting page is missing during append")
                SemanticClaimLexicalPageLoadResult.Corrupt ->
                    return failed("semantic lexical posting page is corrupt")
                is SemanticClaimLexicalPageLoadResult.Incompatible ->
                    return failed(loaded.reason)
                is SemanticClaimLexicalPageLoadResult.EncryptionUnavailable ->
                    return failed(
                        "semantic lexical posting page encryption unavailable: " +
                            loaded.category
                    )
                is SemanticClaimLexicalPageLoadResult.Failed ->
                    return SemanticClaimLexicalRebuildResult.Failed(
                        loaded.reason,
                        loaded.throwable
                    )
            }
        }

        if (existingEntries.any { it.reference == posting.reference }) {
            return failed("semantic lexical posting duplicate claim reference")
        }

        val nextEntries = ArrayList<SemanticClaimLexicalPosting>(
            existingEntries.size + 1
        )
        nextEntries.addAll(existingEntries)
        nextEntries += posting

        when (
            val written = indexStore.writePage(
                SemanticClaimLexicalPostingPage(
                    buildEpoch = epoch,
                    token = token,
                    ordinal = pageOrdinal,
                    entries = nextEntries
                )
            )
        ) {
            SemanticClaimLexicalWriteResult.Written -> Unit
            is SemanticClaimLexicalWriteResult.Rejected ->
                return failed(written.reason)
            is SemanticClaimLexicalWriteResult.Failed ->
                return SemanticClaimLexicalRebuildResult.Failed(
                    written.reason,
                    written.throwable
                )
        }

        val nextCount = try {
            Math.addExact(postingCount, 1L)
        } catch (_: ArithmeticException) {
            return failed("semantic lexical posting count overflow")
        }
        val nextPageCount =
            ((nextCount - 1L) / policy.postingPageEntries.toLong()) + 1L

        return when (
            val written = indexStore.writeRoot(
                SemanticClaimLexicalTokenRoot(
                    buildEpoch = epoch,
                    token = token,
                    pageCount = nextPageCount,
                    postingCount = nextCount
                )
            )
        ) {
            SemanticClaimLexicalWriteResult.Written -> null
            is SemanticClaimLexicalWriteResult.Rejected ->
                failed(written.reason)
            is SemanticClaimLexicalWriteResult.Failed ->
                SemanticClaimLexicalRebuildResult.Failed(
                    written.reason,
                    written.throwable
                )
        }
    }

    private fun writeManifest(
        manifest: SemanticClaimLexicalManifest
    ): SemanticClaimLexicalRebuildResult.Failed? =
        when (val written = indexStore.writeManifest(manifest)) {
            SemanticClaimLexicalWriteResult.Written -> null
            is SemanticClaimLexicalWriteResult.Rejected ->
                failed(written.reason)
            is SemanticClaimLexicalWriteResult.Failed ->
                SemanticClaimLexicalRebuildResult.Failed(
                    written.reason,
                    written.throwable
                )
        }

    private fun addExact(
        left: Long,
        right: Long,
        reason: String
    ): Long? =
        try {
            Math.addExact(left, right)
        } catch (_: ArithmeticException) {
            null
        }

    private fun failed(reason: String) =
        SemanticClaimLexicalRebuildResult.Failed(reason)

    private companion object {
        const val CLAIM_PAGE_SIZE = 512
    }
}
