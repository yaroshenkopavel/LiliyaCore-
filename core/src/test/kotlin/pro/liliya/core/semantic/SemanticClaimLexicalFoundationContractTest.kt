package pro.liliya.core.semantic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.persistence.PersistentPayload

class SemanticClaimLexicalFoundationContractTest {
    @Test
    fun tokenizer_is_nfkc_root_lowercase_and_explicitly_bounded() {
        val normalized =
            SemanticClaimLexicalTokenizer.tokenizeQuery("ＦＯＯ  Bar-42")
        assertEquals(listOf("foo", "bar", "42"), normalized.tokens)
        assertFalse(normalized.truncated)

        val oversizedQuery = (1..17).joinToString(" ") { "token$it" }
        val query = SemanticClaimLexicalTokenizer.tokenizeQuery(oversizedQuery)
        assertEquals(SemanticClaimLexicalTokenizer.MAX_QUERY_TOKENS, query.tokens.size)
        assertTrue(query.truncated)

        val longToken = "a".repeat(
            SemanticClaimLexicalTokenizer.MAX_TOKEN_CODE_POINTS + 10
        )
        val document = SemanticClaimLexicalTokenizer.tokenizeDocument(longToken)
        assertEquals(
            SemanticClaimLexicalTokenizer.MAX_TOKEN_CODE_POINTS,
            document.tokens.single().codePointCount(
                0,
                document.tokens.single().length
            )
        )
        assertTrue(document.truncated)
    }

    @Test
    fun lexical_codec_round_trips_and_corrupt_magic_fails_closed() {
        val manifest = SemanticClaimLexicalManifest(
            buildEpoch = "epoch-codec",
            source = SemanticClaimSourceCheckpoint(7, 8, 9),
            state = SemanticClaimLexicalIndexState.COMPLETE,
            policyVersion = SemanticClaimLexicalPolicy.CURRENT_VERSION,
            tokenizerVersion = SemanticClaimLexicalTokenizer.VERSION,
            postingPageEntries =
                SemanticClaimLexicalPolicy.DEFAULT_POSTING_PAGE_ENTRIES,
            bm25K1 = SemanticClaimLexicalPolicy.DEFAULT_BM25_K1,
            bm25B = SemanticClaimLexicalPolicy.DEFAULT_BM25_B,
            indexedDocumentCount = 3,
            totalIndexedTokenCount = 12,
            truncatedDocumentCount = 1
        )
        val encodedManifest = SemanticClaimLexicalIndexCodec.encodeManifest(manifest)
        val decodedManifest = assertIs<
            SemanticClaimLexicalManifestDecodeResult.Decoded
        >(SemanticClaimLexicalIndexCodec.decodeManifest(encodedManifest))
        assertEquals(manifest, decodedManifest.manifest)

        val root = SemanticClaimLexicalTokenRoot(
            buildEpoch = "epoch-codec",
            token = "alpha",
            pageCount = 1,
            postingCount = 1
        )
        val encodedRoot = SemanticClaimLexicalIndexCodec.encodeRoot(root)
        assertEquals(
            root,
            assertIs<SemanticClaimLexicalRootDecodeResult.Decoded>(
                SemanticClaimLexicalIndexCodec.decodeRoot(encodedRoot)
            ).root
        )

        val reference = SemanticClaimVersionReference(
            SemanticClaimId("claim-codec"),
            SemanticClaimVersion(1)
        )
        val page = SemanticClaimLexicalPostingPage(
            buildEpoch = "epoch-codec",
            token = "alpha",
            ordinal = 0,
            entries = listOf(
                SemanticClaimLexicalPosting(
                    reference = reference,
                    termFrequency = 2,
                    documentLength = 4
                )
            )
        )
        val encodedPage = SemanticClaimLexicalIndexCodec.encodePage(page)
        assertEquals(
            page,
            assertIs<SemanticClaimLexicalPageDecodeResult.Decoded>(
                SemanticClaimLexicalIndexCodec.decodePage(encodedPage)
            ).page
        )

        val corruptBytes = encodedPage.payload.copyBytes()
        corruptBytes[0] = (corruptBytes[0].toInt() xor 0x7F).toByte()
        val corrupt = encodedPage.copy(payload = PersistentPayload(corruptBytes))
        assertIs<SemanticClaimLexicalPageDecodeResult.Corrupt>(
            SemanticClaimLexicalIndexCodec.decodePage(corrupt)
        )
    }

    @Test
    fun candidate_adapter_preserves_stable_identity_and_drops_raw_score() {
        val candidate = SemanticClaimLexicalCandidate(
            reference = SemanticClaimVersionReference(
                SemanticClaimId("claim-adapter"),
                SemanticClaimVersion(2)
            ),
            candidateId =
                pro.liliya.core.retrieval.RetrievalCandidateId(
                    "semantic-claim:claim-adapter:v2"
                ),
            score = 4.25,
            matchedTokens = listOf("alpha")
        )
        val ranked = SemanticClaimLexicalQueryResult.Ranked(
            candidates = listOf(candidate),
            audit = SemanticClaimLexicalAudit(
                indexVersion = SemanticClaimLexicalManifest.CURRENT_VERSION,
                buildEpoch = "epoch-adapter",
                policyVersion = SemanticClaimLexicalPolicy.CURRENT_VERSION,
                tokenizerVersion = SemanticClaimLexicalTokenizer.VERSION,
                source = SemanticClaimSourceCheckpoint(1, 1, 1),
                queryTokenCount = 1,
                queryTokenTruncated = false,
                postingEntriesScanned = 1,
                candidateWorkingSetSize = 1,
                postingBudgetTruncated = false,
                candidateBudgetTruncated = false,
                returnedCandidates = 1
            )
        )

        val channel = assertIs<
            pro.liliya.core.retrieval.RetrievalChannelResult.Ranked
        >(
            ranked.toRetrievalChannelResult(
                pro.liliya.core.retrieval.RetrievalChannelRequirement.OPTIONAL
            )
        )
        assertEquals(candidate.candidateId, channel.candidates.single().id)
    }
}
