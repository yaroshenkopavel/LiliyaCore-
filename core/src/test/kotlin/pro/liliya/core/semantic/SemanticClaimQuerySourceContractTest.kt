package pro.liliya.core.semantic

import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.encryption.CognitiveAeadProvider
import pro.liliya.core.encryption.CognitiveAeadSealedData
import pro.liliya.core.encryption.CognitiveAssociatedData
import pro.liliya.core.encryption.CognitiveDekGeneration
import pro.liliya.core.encryption.CognitiveDekId
import pro.liliya.core.encryption.CognitiveDekMaterial
import pro.liliya.core.encryption.CognitiveDekMaterialResolver
import pro.liliya.core.encryption.CognitiveDekReference
import pro.liliya.core.encryption.CognitiveEncryptionFailureCategory
import pro.liliya.core.encryption.CognitiveEncryptionProfile
import pro.liliya.core.encryption.CognitiveEncryptionResult
import pro.liliya.core.encryption.CognitiveEnvelopeVersion
import pro.liliya.core.encryption.CognitiveNonce
import pro.liliya.core.encryption.CognitiveNonceSource
import pro.liliya.core.encryption.CognitivePlaintext
import pro.liliya.core.encryption.EncryptedPersistentRecordStore
import pro.liliya.core.episodic.EpisodeId
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.persistence.IndexedPersistentRecordMutationBackend
import pro.liliya.core.persistence.PersistentBackendCommitResult
import pro.liliya.core.persistence.PersistentBackendEntry
import pro.liliya.core.persistence.PersistentBackendEntryLoadResult
import pro.liliya.core.persistence.PersistentBackendLoadResult
import pro.liliya.core.persistence.PersistentBackendMetadata
import pro.liliya.core.persistence.PersistentBackendMetadataLoadResult
import pro.liliya.core.persistence.PersistentBackendMutationResult
import pro.liliya.core.persistence.PersistentBackendPage
import pro.liliya.core.persistence.PersistentBackendPageCursor
import pro.liliya.core.persistence.PersistentBackendPageLoadResult
import pro.liliya.core.persistence.PersistentBackendPageOrder
import pro.liliya.core.persistence.PersistentBackendPageRequest
import pro.liliya.core.persistence.PersistentBackendState
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentGeneration
import pro.liliya.core.persistence.PersistentRecordSnapshot
import pro.liliya.core.persistence.PersistentRecordStore
import pro.liliya.core.persistence.PersistentStoreId
import pro.liliya.core.persistence.PersistentStoreOpenResult

class SemanticClaimQuerySourceContractTest {
    private val profile = CognitiveEncryptionProfile.AES_256_GCM
    private val dekRef = CognitiveDekReference(
        CognitiveDekId("semantic-query-dek"),
        CognitiveDekGeneration(1)
    )
    private val material =
        CognitiveDekMaterial(ByteArray(32) { (it * 7 + 11).toByte() })

    @Test
    fun rebuild_and_query_are_conflict_group_bounded_and_exact_verified() {
        val fixture = fixture("bounded")
        val languageIdentity = identity("preferred_language")
        val timezoneIdentity = identity("preferred_timezone")

        val language = listOf(
            claim(languageIdentity, "Russian", 1),
            claim(languageIdentity, "Ukrainian", 2),
            claim(languageIdentity, "English", 3)
        )
        language.forEach {
            assertIs<SemanticClaimStoreResult.Stored>(
                fixture.repository.storeClaim(it)
            )
        }
        repeat(50) { ordinal ->
            assertIs<SemanticClaimStoreResult.Stored>(
                fixture.repository.storeClaim(
                    claim(timezoneIdentity, "zone-" + ordinal, 100 + ordinal)
                )
            )
        }

        val rebuilt = assertIs<SemanticClaimQueryIndexRebuildResult.Complete>(
            fixture.rebuilder.rebuild()
        )
        assertEquals(53L, rebuilt.indexedClaims)

        val canonicalPagesAfterRebuild = fixture.canonicalBackend.pageLoadCalls
        fixture.canonicalBackend.entryLoadCalls = 0
        fixture.indexBackend.pageLoadCalls = 0

        val result = assertIs<SemanticClaimQueryResult.Candidates>(
            fixture.source.query(languageIdentity, limit = 10)
        )
        assertEquals(
            language.map { it.id to it.version },
            result.records.map { it.id to it.version }
        )
        assertEquals(3, result.audit.scannedIndexEntries)
        assertEquals(3, result.audit.returnedCandidates)
        assertEquals(null, result.nextCursor)

        assertEquals(
            canonicalPagesAfterRebuild,
            fixture.canonicalBackend.pageLoadCalls
        )
        assertEquals(0, fixture.indexBackend.pageLoadCalls)
        assertTrue(fixture.canonicalBackend.entryLoadCalls > 0)
        assertTrue(fixture.canonicalBackend.entryLoadCalls < 50)
    }

    @Test
    fun group_history_is_segmented_and_cursor_stays_bounded() {
        val fixture = fixture("segmented")
        val identity = identity("long_history")
        repeat(130) { ordinal ->
            assertIs<SemanticClaimStoreResult.Stored>(
                fixture.repository.storeClaim(
                    claim(identity, "value-" + ordinal, ordinal)
                )
            )
        }

        assertIs<SemanticClaimQueryIndexRebuildResult.Complete>(
            fixture.rebuilder.rebuild()
        )

        val first = assertIs<SemanticClaimQueryResult.Candidates>(
            fixture.source.query(identity, limit = 100)
        )
        assertEquals(100, first.records.size)
        assertEquals(100, first.audit.scannedIndexEntries)
        assertNotNull(first.nextCursor)

        val second = assertIs<SemanticClaimQueryResult.Candidates>(
            fixture.source.query(
                identity,
                limit = 100,
                cursorExclusive = first.nextCursor
            )
        )
        assertEquals(30, second.records.size)
        assertEquals(30, second.audit.scannedIndexEntries)
        assertEquals(null, second.nextCursor)

        val all = first.records + second.records
        assertEquals(130, all.size)
        assertEquals(130, all.map { it.id to it.version }.distinct().size)
    }

    @Test
    fun canonical_source_change_makes_complete_index_stale_until_rebuild() {
        val fixture = fixture("stale")
        val identity = identity("preferred_language")
        assertIs<SemanticClaimStoreResult.Stored>(
            fixture.repository.storeClaim(claim(identity, "Russian", 1))
        )
        assertIs<SemanticClaimQueryIndexRebuildResult.Complete>(
            fixture.rebuilder.rebuild()
        )
        assertIs<SemanticClaimQueryResult.Candidates>(
            fixture.source.query(identity, 10)
        )

        assertIs<SemanticClaimStoreResult.Stored>(
            fixture.repository.storeClaim(claim(identity, "Ukrainian", 2))
        )
        val stale = assertIs<SemanticClaimQueryResult.FallbackRequired>(
            fixture.source.query(identity, 10)
        )
        assertTrue(stale.reason.contains("source checkpoint"))

        assertIs<SemanticClaimQueryIndexRebuildResult.Complete>(
            fixture.rebuilder.rebuild()
        )
        val refreshed = assertIs<SemanticClaimQueryResult.Candidates>(
            fixture.source.query(identity, 10)
        )
        assertEquals(2, refreshed.records.size)
    }

    @Test
    fun source_drift_leaves_manifest_incomplete_and_query_requires_fallback() {
        val fixture = fixture("drift")
        val identity = identity("preferred_language")
        assertIs<SemanticClaimStoreResult.Stored>(
            fixture.repository.storeClaim(claim(identity, "Russian", 1))
        )

        var injected = false
        val driftingRebuilder = SemanticClaimQueryIndexRebuilder(
            fixture.repository,
            fixture.indexStore
        ) {
            if (!injected) {
                injected = true
                assertIs<SemanticClaimStoreResult.Stored>(
                    fixture.repository.storeClaim(
                        claim(identity, "Ukrainian", 2)
                    )
                )
            }
            "drift-epoch"
        }

        assertIs<SemanticClaimQueryIndexRebuildResult.SourceDrift>(
            driftingRebuilder.rebuild()
        )
        val manifest = assertIs<SemanticClaimQueryManifestLoadResult.Loaded>(
            fixture.indexStore.readManifest()
        ).manifest
        assertEquals(
            SemanticClaimQueryIndexState.INCOMPLETE,
            manifest.state
        )

        assertIs<SemanticClaimQueryResult.FallbackRequired>(
            fixture.source.query(identity, 10)
        )

        assertIs<SemanticClaimQueryIndexRebuildResult.Complete>(
            fixture.rebuilder.rebuild()
        )
        assertEquals(
            2,
            assertIs<SemanticClaimQueryResult.Candidates>(
                fixture.source.query(identity, 10)
            ).records.size
        )
    }

    @Test
    fun query_candidates_feed_deterministic_resolver_without_semantic_rewrite() {
        val fixture = fixture("resolver")
        val identity = identity("preferred_language")
        val russian = claim(identity, "Russian", 1)
        val ukrainian = claim(identity, "Ukrainian", 2)
        assertIs<SemanticClaimStoreResult.Stored>(
            fixture.repository.storeClaim(russian)
        )
        assertIs<SemanticClaimStoreResult.Stored>(
            fixture.repository.storeClaim(ukrainian)
        )
        assertIs<SemanticClaimQueryIndexRebuildResult.Complete>(
            fixture.rebuilder.rebuild()
        )

        val candidates = assertIs<SemanticClaimQueryResult.Candidates>(
            fixture.source.query(identity, 10)
        ).records
        assertEquals(listOf(russian, ukrainian), candidates)

        val result = DeterministicSemanticResolutionPolicyEngine.resolve(
            records = candidates,
            conflictGroupId = SemanticClaimIds.forConflictGroup(identity),
            policy = SemanticResolutionPolicy(
                id = "single-current",
                operator = SemanticResolutionOperator.CURRENT_VALUE,
                cardinality = SemanticClaimCardinality.SINGLE
            ),
            worldTime = Instant.parse("2026-09-27T00:00:00Z"),
            knowledgeTime = Instant.parse("2026-09-27T00:00:00Z")
        )
        assertIs<SemanticResolutionResult.Conflicted>(result)
    }

    @Test
    fun lexical_rebuild_and_query_are_exact_bounded_and_bm25_deterministic() {
        val fixture = lexicalFixture("lexical-bounded")
        val first = claim(identity("lexical_a"), "alpha beta", 1)
        val second = claim(identity("lexical_b"), "alpha beta", 2)
        listOf(first, second).forEach {
            assertIs<SemanticClaimStoreResult.Stored>(
                fixture.repository.storeClaim(it)
            )
        }
        repeat(40) { ordinal ->
            assertIs<SemanticClaimStoreResult.Stored>(
                fixture.repository.storeClaim(
                    claim(
                        identity("noise_" + ordinal),
                        "unrelated value " + ordinal,
                        100 + ordinal
                    )
                )
            )
        }

        assertIs<SemanticClaimLexicalRebuildResult.Complete>(
            fixture.rebuilder.rebuild()
        )
        val canonicalPages = fixture.canonicalBackend.pageLoadCalls
        fixture.canonicalBackend.entryLoadCalls = 0
        fixture.indexBackend.pageLoadCalls = 0
        fixture.indexBackend.entryLoadCalls = 0

        val result = assertIs<SemanticClaimLexicalQueryResult.Ranked>(
            fixture.source.query("ＡＬＰＨＡ")
        )
        assertEquals(2, result.candidates.size)
        assertEquals(
            result.candidates.first().score,
            result.candidates.last().score
        )
        val ids = result.candidates.map { it.candidateId.value }
        assertEquals(
            ids.sortedWith { left, right ->
                pro.liliya.core.retrieval.compareUtf8(left, right)
            },
            ids
        )
        assertEquals(2, result.audit.postingEntriesScanned)
        assertEquals(2, result.audit.candidateWorkingSetSize)
        assertEquals(2, result.audit.returnedCandidates)
        assertEquals(canonicalPages, fixture.canonicalBackend.pageLoadCalls)
        assertEquals(0, fixture.indexBackend.pageLoadCalls)
        assertTrue(fixture.indexBackend.entryLoadCalls < 10)
        assertTrue(fixture.canonicalBackend.entryLoadCalls >= 2)
    }

    @Test
    fun lexical_posting_and_candidate_budgets_are_explicitly_audited() {
        val policy = SemanticClaimLexicalPolicy(
            postingPageEntries = 2,
            maxPostingEntriesPerToken = 2,
            maxCandidateWorkingSet = 2,
            maxReturnedCandidates = 2
        )
        val fixture = lexicalFixture("lexical-budget", policy)
        repeat(5) { ordinal ->
            assertIs<SemanticClaimStoreResult.Stored>(
                fixture.repository.storeClaim(
                    claim(
                        identity("budget_" + ordinal),
                        "alpha value " + ordinal,
                        10 + ordinal
                    )
                )
            )
        }
        assertIs<SemanticClaimLexicalRebuildResult.Complete>(
            fixture.rebuilder.rebuild()
        )

        val result = assertIs<SemanticClaimLexicalQueryResult.Ranked>(
            fixture.source.query("alpha")
        )
        assertEquals(2, result.candidates.size)
        assertEquals(2, result.audit.postingEntriesScanned)
        assertEquals(2, result.audit.candidateWorkingSetSize)
        assertTrue(result.audit.postingBudgetTruncated)
        assertEquals(false, result.audit.candidateBudgetTruncated)

        val candidatePolicy = SemanticClaimLexicalPolicy(
            postingPageEntries = 2,
            maxPostingEntriesPerToken = 4,
            maxCandidateWorkingSet = 2,
            maxReturnedCandidates = 2
        )
        val candidateFixture =
            lexicalFixture("lexical-candidate-budget", candidatePolicy)
        listOf(
            claim(identity("candidate_a"), "alpha", 21),
            claim(identity("candidate_b"), "alpha", 22),
            claim(identity("candidate_c"), "beta", 23),
            claim(identity("candidate_d"), "beta", 24)
        ).forEach {
            assertIs<SemanticClaimStoreResult.Stored>(
                candidateFixture.repository.storeClaim(it)
            )
        }
        assertIs<SemanticClaimLexicalRebuildResult.Complete>(
            candidateFixture.rebuilder.rebuild()
        )
        val candidateResult = assertIs<SemanticClaimLexicalQueryResult.Ranked>(
            candidateFixture.source.query("alpha beta")
        )
        assertEquals(2, candidateResult.candidates.size)
        assertEquals(4, candidateResult.audit.postingEntriesScanned)
        assertTrue(candidateResult.audit.candidateBudgetTruncated)
    }

    @Test
    fun lexical_source_drift_and_missing_canonical_hit_fail_safe() {
        val fixture = lexicalFixture("lexical-revalidation")
        val first = claim(identity("revalidation"), "alpha", 31)
        assertIs<SemanticClaimStoreResult.Stored>(
            fixture.repository.storeClaim(first)
        )

        var injected = false
        val drifting = SemanticClaimLexicalIndexRebuilder(
            fixture.repository,
            fixture.indexStore,
            SemanticClaimLexicalPolicy()
        ) {
            if (!injected) {
                injected = true
                assertIs<SemanticClaimStoreResult.Stored>(
                    fixture.repository.storeClaim(
                        claim(identity("drift-extra"), "beta", 32)
                    )
                )
            }
            "lexical-drift-epoch"
        }
        assertIs<SemanticClaimLexicalRebuildResult.SourceDrift>(
            drifting.rebuild()
        )
        val incomplete = assertIs<SemanticClaimLexicalManifestLoadResult.Loaded>(
            fixture.indexStore.readManifest()
        ).manifest
        assertEquals(SemanticClaimLexicalIndexState.INCOMPLETE, incomplete.state)
        assertIs<SemanticClaimLexicalQueryResult.FallbackRequired>(
            fixture.source.query("alpha")
        )

        assertIs<SemanticClaimLexicalRebuildResult.Complete>(
            fixture.rebuilder.rebuild()
        )
        fixture.canonicalBackend.hideEntryWithoutMetadata(
            SemanticClaimPersistentCodec.persistentId(first.id, first.version)
        )
        val missing = assertIs<SemanticClaimLexicalQueryResult.FallbackRequired>(
            fixture.source.query("alpha")
        )
        assertTrue(missing.reason.contains("missing"))
    }

    @Test
    fun lexical_output_limit_query_truncation_and_stale_source_are_explicit() {
        val policy = SemanticClaimLexicalPolicy(
            postingPageEntries = 2,
            maxPostingEntriesPerToken = 4,
            maxCandidateWorkingSet = 4,
            maxReturnedCandidates = 2
        )
        val fixture = lexicalFixture("lexical-output-limit", policy)
        repeat(4) { ordinal ->
            assertIs<SemanticClaimStoreResult.Stored>(
                fixture.repository.storeClaim(
                    claim(
                        identity("output_" + ordinal),
                        "alpha token " + ordinal,
                        40 + ordinal
                    )
                )
            )
        }
        assertIs<SemanticClaimLexicalRebuildResult.Complete>(
            fixture.rebuilder.rebuild()
        )

        val bounded = assertIs<SemanticClaimLexicalQueryResult.Ranked>(
            fixture.source.query("alpha")
        )
        assertEquals(4, bounded.audit.candidateWorkingSetSize)
        assertEquals(2, bounded.audit.returnedCandidates)
        assertEquals(2, bounded.candidates.size)

        val oversized =
            "alpha " + (1..16).joinToString(" ") { "querytoken" + it }
        val truncated = assertIs<SemanticClaimLexicalQueryResult.Ranked>(
            fixture.source.query(oversized)
        )
        assertEquals(
            SemanticClaimLexicalTokenizer.MAX_QUERY_TOKENS,
            truncated.audit.queryTokenCount
        )
        assertTrue(truncated.audit.queryTokenTruncated)

        assertIs<SemanticClaimStoreResult.Stored>(
            fixture.repository.storeClaim(
                claim(identity("stale_after_build"), "alpha new", 50)
            )
        )
        val stale = assertIs<SemanticClaimLexicalQueryResult.FallbackRequired>(
            fixture.source.query("alpha")
        )
        assertTrue(stale.reason.contains("source checkpoint"))
    }

    @Test
    fun lexical_query_rejects_empty_and_audits_token_truncation() {
        val fixture = lexicalFixture("lexical-query-limits")
        assertIs<SemanticClaimLexicalQueryResult.Rejected>(
            fixture.source.query("   ---   ")
        )
        assertIs<SemanticClaimStoreResult.Stored>(
            fixture.repository.storeClaim(
                claim(identity("limits"), "token1 token2", 41)
            )
        )
        assertIs<SemanticClaimLexicalRebuildResult.Complete>(
            fixture.rebuilder.rebuild()
        )
        val oversized = (1..20).joinToString(" ") { "token" + it }
        val ranked = assertIs<SemanticClaimLexicalQueryResult.Ranked>(
            fixture.source.query(oversized)
        )
        assertEquals(
            SemanticClaimLexicalTokenizer.MAX_QUERY_TOKENS,
            ranked.audit.queryTokenCount
        )
        assertTrue(ranked.audit.queryTokenTruncated)
    }

    @Test
    fun lexical_bm25_formula_is_exact_for_known_corpus() {
        val fixture = lexicalFixture("lexical-bm25-formula")
        val dense = claim(identity("bm25_dense"), "alpha alpha beta", 61)
        val sparse = claim(identity("bm25_sparse"), "alpha beta", 62)
        listOf(dense, sparse).forEach {
            assertIs<SemanticClaimStoreResult.Stored>(
                fixture.repository.storeClaim(it)
            )
        }
        assertIs<SemanticClaimLexicalRebuildResult.Complete>(
            fixture.rebuilder.rebuild()
        )

        val result = assertIs<SemanticClaimLexicalQueryResult.Ranked>(
            fixture.source.query("alpha")
        )
        assertEquals(2, result.candidates.size)

        val n = 2.0
        val df = 2.0
        val idf = kotlin.math.ln(1.0 + (n - df + 0.5) / (df + 0.5))
        val avg = 2.5
        fun expected(tf: Double, length: Double): Double {
            val k1 = SemanticClaimLexicalPolicy.DEFAULT_BM25_K1
            val b = SemanticClaimLexicalPolicy.DEFAULT_BM25_B
            val normalization = 1.0 - b + b * length / avg
            return idf * ((tf * (k1 + 1.0)) / (tf + k1 * normalization))
        }

        val byReference = result.candidates.associateBy { it.reference }
        val denseScore = byReference[
            SemanticClaimVersionReference(dense.id, dense.version)
        ]!!.score
        val sparseScore = byReference[
            SemanticClaimVersionReference(sparse.id, sparse.version)
        ]!!.score
        assertEquals(expected(2.0, 3.0), denseScore, 1e-12)
        assertEquals(expected(1.0, 2.0), sparseScore, 1e-12)
        assertTrue(denseScore > sparseScore)
    }

    @Test
    fun lexical_active_build_mismatch_fails_safe_without_partial_result() {
        val fixture = lexicalFixture("lexical-active-build")
        val record = claim(identity("active_build"), "alpha beta", 71)
        assertIs<SemanticClaimStoreResult.Stored>(
            fixture.repository.storeClaim(record)
        )
        assertIs<SemanticClaimLexicalRebuildResult.Complete>(
            fixture.rebuilder.rebuild()
        )
        val currentRoot = assertIs<SemanticClaimLexicalRootLoadResult.Loaded>(
            fixture.indexStore.readRoot("alpha")
        ).root
        assertIs<SemanticClaimLexicalWriteResult.Written>(
            fixture.indexStore.writeRoot(
                currentRoot.copy(buildEpoch = "stale-build-epoch")
            )
        )

        val result = assertIs<SemanticClaimLexicalQueryResult.FallbackRequired>(
            fixture.source.query("alpha")
        )
        assertTrue(result.reason.contains("active build"))
    }

    private fun lexicalFixture(
        suffix: String,
        policy: SemanticClaimLexicalPolicy = SemanticClaimLexicalPolicy()
    ): LexicalFixture {
        val canonicalBackend = IndexedBackend()
        val indexBackend = IndexedBackend()
        val repository = EncryptedPersistentSemanticClaimRepository(
            encrypted(canonicalBackend, "semantic-lexical-source-" + suffix),
            dekRef
        )
        val indexStore = assertIs<SemanticClaimLexicalIndexOpenResult.Opened>(
            EncryptedPersistentSemanticClaimLexicalIndexStore.open(
                encrypted(indexBackend, "semantic-lexical-index-" + suffix),
                dekRef
            )
        ).store
        val sequence = AtomicInteger()
        val rebuilder = SemanticClaimLexicalIndexRebuilder(
            repository,
            indexStore,
            policy
        ) {
            "lexical-epoch-" + suffix + "-" + sequence.incrementAndGet()
        }
        return LexicalFixture(
            repository = repository,
            indexStore = indexStore,
            rebuilder = rebuilder,
            source = SemanticClaimLexicalQuerySource(
                repository,
                indexStore,
                policy
            ),
            canonicalBackend = canonicalBackend,
            indexBackend = indexBackend
        )
    }

    private data class LexicalFixture(
        val repository: EncryptedPersistentSemanticClaimRepository,
        val indexStore: EncryptedPersistentSemanticClaimLexicalIndexStore,
        val rebuilder: SemanticClaimLexicalIndexRebuilder,
        val source: SemanticClaimLexicalQuerySource,
        val canonicalBackend: IndexedBackend,
        val indexBackend: IndexedBackend
    )

    private fun fixture(suffix: String): Fixture {
        val canonicalBackend = IndexedBackend()
        val indexBackend = IndexedBackend()
        val repository = EncryptedPersistentSemanticClaimRepository(
            encrypted(canonicalBackend, "semantic-query-source-" + suffix),
            dekRef
        )
        val indexStore = assertIs<SemanticClaimQueryIndexOpenResult.Opened>(
            EncryptedPersistentSemanticClaimQueryIndexStore.open(
                encrypted(indexBackend, "semantic-query-index-" + suffix),
                dekRef
            )
        ).store
        val epochSequence = AtomicInteger()
        val rebuilder = SemanticClaimQueryIndexRebuilder(
            repository,
            indexStore
        ) {
            "epoch-" + suffix + "-" + epochSequence.incrementAndGet()
        }
        return Fixture(
            repository,
            indexStore,
            rebuilder,
            SemanticClaimQuerySource(repository, indexStore),
            canonicalBackend,
            indexBackend
        )
    }

    private data class Fixture(
        val repository: EncryptedPersistentSemanticClaimRepository,
        val indexStore: EncryptedPersistentSemanticClaimQueryIndexStore,
        val rebuilder: SemanticClaimQueryIndexRebuilder,
        val source: SemanticClaimQuerySource,
        val canonicalBackend: IndexedBackend,
        val indexBackend: IndexedBackend
    )

    private fun identity(predicate: String) =
        SemanticClaimIdentity(
            subject = SemanticEntityReference("person", "user"),
            predicate = predicate
        )

    private fun claim(
        identity: SemanticClaimIdentity,
        value: String,
        ordinal: Int
    ): SemanticClaimRecord {
        val objectValue = SemanticClaimObject.Text(value)
        val observed = Instant.parse("2026-09-26T10:00:00Z")
            .plusSeconds(ordinal.toLong())
        return SemanticClaimRecord(
            id = SemanticClaimIds.forClaim(identity, objectValue),
            version = SemanticClaimVersion(1),
            identity = identity,
            objectValue = objectValue,
            temporal = SemanticClaimTemporalState(
                observedAt = observed,
                validFrom = Instant.parse("2026-09-01T00:00:00Z")
            ),
            provenance = SemanticClaimProvenance(
                episodes = listOf(EpisodeId("episode-" + ordinal)),
                extraction = SemanticClaimExtractionProvenance(
                    extractorId = "semantic-query-test",
                    extractorVersion = "1.0",
                    extractedAt = observed.plusSeconds(1)
                )
            )
        )
    }

    private fun encrypted(
        backend: IndexedBackend,
        storeId: String
    ): EncryptedPersistentRecordStore {
        val persistent = assertIs<PersistentStoreOpenResult.Opened>(
            PersistentRecordStore.open(
                foundation(),
                PersistentStoreId(storeId),
                backend
            )
        ).store
        return EncryptedPersistentRecordStore(
            store = persistent,
            profile = profile,
            envelopeVersion = CognitiveEnvelopeVersion(1),
            nonceSource = DeterministicNonceSource(),
            aead = DeterministicAeadProvider(),
            dekResolver = resolver()
        )
    }

    private fun resolver() = object : CognitiveDekMaterialResolver {
        override fun resolve(
            reference: CognitiveDekReference
        ): CognitiveEncryptionResult<CognitiveDekMaterial> =
            if (reference == dekRef) {
                CognitiveEncryptionResult.Success(material)
            } else {
                CognitiveEncryptionResult.Rejected(
                    CognitiveEncryptionFailureCategory.DEK_MISSING
                )
            }
    }

    private fun foundation(): FoundationComposition {
        val sequence = AtomicInteger()
        val writer = InMemoryLogWriter()
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider {
                context -> StructuredLogger(context, writer)
            },
            correlationIds = CorrelationIdGenerator {
                "semantic-query-" + sequence.incrementAndGet()
            }
        )
    }

    private class IndexedBackend : IndexedPersistentRecordMutationBackend {
        private val entries =
            LinkedHashMap<PersistentEntityId, PersistentBackendEntry>()
        private val hiddenEntryIds = HashSet<PersistentEntityId>()
        private var revision = 0L
        private var highWatermark = 0L
        var pageLoadCalls: Int = 0
        var entryLoadCalls: Int = 0

        fun hideEntryWithoutMetadata(id: PersistentEntityId) {
            hiddenEntryIds += id
        }

        override fun load(
            storeId: PersistentStoreId
        ): PersistentBackendLoadResult =
            PersistentBackendLoadResult.Failed("legacy load must not be used")

        override fun commit(
            storeId: PersistentStoreId,
            expectedRevision: Long,
            state: PersistentBackendState
        ): PersistentBackendCommitResult =
            PersistentBackendCommitResult.Failed(
                "legacy commit must not be used"
            )

        override fun loadMetadata(
            storeId: PersistentStoreId
        ): PersistentBackendMetadataLoadResult =
            if (revision == 0L && entries.isEmpty()) {
                PersistentBackendMetadataLoadResult.Missing
            } else {
                PersistentBackendMetadataLoadResult.Loaded(metadata())
            }

        override fun loadEntry(
            storeId: PersistentStoreId,
            entityId: PersistentEntityId
        ): PersistentBackendEntryLoadResult {
            entryLoadCalls += 1
            if (entityId in hiddenEntryIds) {
                return PersistentBackendEntryLoadResult.Missing
            }
            return entries[entityId]?.let {
                PersistentBackendEntryLoadResult.Loaded(
                    PersistentRecordSnapshot(it.record, it.generation)
                )
            } ?: PersistentBackendEntryLoadResult.Missing
        }

        override fun loadPage(
            storeId: PersistentStoreId,
            request: PersistentBackendPageRequest
        ): PersistentBackendPageLoadResult {
            pageLoadCalls += 1
            val ordered = entries.values
                .filter {
                    request.schemaId == null ||
                        it.record.schemaId == request.schemaId
                }
                .map {
                    PersistentRecordSnapshot(it.record, it.generation)
                }
                .sortedWith(
                    compareBy(
                        { it.record.createdAt },
                        { it.record.id.value }
                    )
                )
                .let {
                    if (
                        request.order ==
                        PersistentBackendPageOrder.OLDEST_FIRST
                    ) it else it.asReversed()
                }

            val filtered = ordered.filter { snapshot ->
                val cursor =
                    request.cursorExclusive ?: return@filter true
                when (request.order) {
                    PersistentBackendPageOrder.OLDEST_FIRST ->
                        snapshot.record.createdAt > cursor.createdAt ||
                            (
                                snapshot.record.createdAt ==
                                    cursor.createdAt &&
                                    snapshot.record.id.value >
                                    cursor.entityId.value
                                )
                    PersistentBackendPageOrder.NEWEST_FIRST ->
                        snapshot.record.createdAt < cursor.createdAt ||
                            (
                                snapshot.record.createdAt ==
                                    cursor.createdAt &&
                                    snapshot.record.id.value <
                                    cursor.entityId.value
                                )
                }
            }
            val page = filtered.take(request.limit)
            val next =
                if (filtered.size > request.limit && page.isNotEmpty()) {
                    val last = page.last().record
                    PersistentBackendPageCursor(last.createdAt, last.id)
                } else {
                    null
                }
            return if (entries.isEmpty()) {
                PersistentBackendPageLoadResult.Missing
            } else {
                PersistentBackendPageLoadResult.Loaded(
                    PersistentBackendPage(page, next)
                )
            }
        }

        override fun installEntry(
            storeId: PersistentStoreId,
            expectedRevision: Long,
            expectedHighWatermark: Long,
            entry: PersistentBackendEntry
        ): PersistentBackendMutationResult {
            if (
                revision != expectedRevision ||
                highWatermark != expectedHighWatermark
            ) {
                return PersistentBackendMutationResult.Conflict
            }
            if (
                entries.containsKey(entry.record.id) ||
                entry.generation.value != highWatermark + 1L
            ) {
                return PersistentBackendMutationResult.Rejected(
                    "invalid install"
                )
            }
            entries[entry.record.id] = entry
            revision += 1L
            highWatermark = entry.generation.value
            return PersistentBackendMutationResult.Committed(metadata())
        }

        override fun transitionEntry(
            storeId: PersistentStoreId,
            expectedRevision: Long,
            expectedHighWatermark: Long,
            sourceId: PersistentEntityId,
            sourceGeneration: PersistentGeneration,
            replacement: PersistentBackendEntry
        ): PersistentBackendMutationResult {
            if (
                revision != expectedRevision ||
                highWatermark != expectedHighWatermark
            ) {
                return PersistentBackendMutationResult.Conflict
            }
            val current = entries[sourceId]
                ?: return PersistentBackendMutationResult.Rejected(
                    "missing source"
                )
            if (
                current.generation != sourceGeneration ||
                replacement.generation != sourceGeneration
            ) {
                return PersistentBackendMutationResult.Rejected(
                    "stale generation"
                )
            }
            entries.remove(sourceId)
            entries[replacement.record.id] = replacement
            revision += 1L
            return PersistentBackendMutationResult.Committed(metadata())
        }

        override fun removeEntry(
            storeId: PersistentStoreId,
            expectedRevision: Long,
            expectedHighWatermark: Long,
            id: PersistentEntityId,
            generation: PersistentGeneration
        ): PersistentBackendMutationResult =
            PersistentBackendMutationResult.Rejected("not used")

        private fun metadata() =
            PersistentBackendMetadata(
                revision = revision,
                highWatermark = highWatermark,
                entryCount = entries.size.toLong()
            )
    }

    private class DeterministicNonceSource : CognitiveNonceSource {
        private var next = 1

        override fun next(
            profile: CognitiveEncryptionProfile
        ): CognitiveEncryptionResult<CognitiveNonce> =
            CognitiveEncryptionResult.Success(
                CognitiveNonce(
                    profile,
                    ByteArray(profile.nonceSizeBytes) {
                        (next + it).toByte()
                    }
                ).also { next += 1 }
            )
    }

    private class DeterministicAeadProvider : CognitiveAeadProvider {
        override fun seal(
            profile: CognitiveEncryptionProfile,
            dek: CognitiveDekMaterial,
            nonce: CognitiveNonce,
            associatedData: CognitiveAssociatedData,
            plaintext: CognitivePlaintext
        ): CognitiveEncryptionResult<CognitiveAeadSealedData> {
            val key = dek.copyBytes()
            val n = nonce.copyBytes()
            val plain = plaintext.copyBytes()
            val aad = associatedData.copyBytes()
            val cipher = ByteArray(plain.size) { i ->
                (
                    plain[i].toInt() xor
                        key[i % key.size].toInt() xor
                        n[i % n.size].toInt()
                    ).toByte()
            }
            return CognitiveEncryptionResult.Success(
                CognitiveAeadSealedData(
                    cipher,
                    tag(key, n, aad, cipher)
                )
            )
        }

        override fun open(
            profile: CognitiveEncryptionProfile,
            dek: CognitiveDekMaterial,
            nonce: CognitiveNonce,
            associatedData: CognitiveAssociatedData,
            sealed: CognitiveAeadSealedData
        ): CognitiveEncryptionResult<CognitivePlaintext> {
            val key = dek.copyBytes()
            val n = nonce.copyBytes()
            val aad = associatedData.copyBytes()
            val cipher = sealed.copyCiphertext()
            val expected = tag(key, n, aad, cipher)
            if (
                !MessageDigest.isEqual(
                    expected,
                    sealed.copyAuthenticationTag()
                )
            ) {
                return CognitiveEncryptionResult.Rejected(
                    CognitiveEncryptionFailureCategory
                        .CIPHERTEXT_AUTHENTICATION_FAILED
                )
            }
            return CognitiveEncryptionResult.Success(
                CognitivePlaintext(
                    ByteArray(cipher.size) { i ->
                        (
                            cipher[i].toInt() xor
                                key[i % key.size].toInt() xor
                                n[i % n.size].toInt()
                            ).toByte()
                    }
                )
            )
        }

        private fun tag(
            key: ByteArray,
            nonce: ByteArray,
            aad: ByteArray,
            cipher: ByteArray
        ): ByteArray =
            MessageDigest.getInstance("SHA-256")
                .digest(key + nonce + aad + cipher)
                .copyOf(16)
    }
}
