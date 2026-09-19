package pro.liliya.core.persistence

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider

class PersistentRecordStoreContractTest {
    private class IndexedLookupFixtureBackend : IndexedPersistentRecordReadBackend {
        private val delegate = InMemoryPersistentRecordBackend()
        var exactResult: PersistentBackendEntryLoadResult = PersistentBackendEntryLoadResult.Missing
        var pageHandler: (PersistentBackendPageRequest) -> PersistentBackendPageLoadResult = {
            PersistentBackendPageLoadResult.Missing
        }

        override fun load(storeId: PersistentStoreId): PersistentBackendLoadResult =
            delegate.load(storeId)

        override fun commit(
            storeId: PersistentStoreId,
            expectedRevision: Long,
            state: PersistentBackendState
        ): PersistentBackendCommitResult =
            delegate.commit(storeId, expectedRevision, state)

        override fun loadMetadata(storeId: PersistentStoreId): PersistentBackendMetadataLoadResult =
            PersistentBackendMetadataLoadResult.Missing

        override fun loadEntry(
            storeId: PersistentStoreId,
            entityId: PersistentEntityId
        ): PersistentBackendEntryLoadResult = exactResult

        override fun loadPage(
            storeId: PersistentStoreId,
            request: PersistentBackendPageRequest
        ): PersistentBackendPageLoadResult = pageHandler(request)
    }
    private class LazyIndexedMutationFixtureBackend : IndexedPersistentRecordMutationBackend {
        private val entries = LinkedHashMap<PersistentEntityId, PersistentBackendEntry>()
        private var revision = 0L
        private var highWatermark = 0L
        var legacyLoadCalls = 0
        var legacyCommitCalls = 0
        var installCalls = 0
        var transitionCalls = 0
        var removeCalls = 0
        var advanceRevisionOnNextPage = false

        override fun load(storeId: PersistentStoreId): PersistentBackendLoadResult {
            legacyLoadCalls += 1
            return PersistentBackendLoadResult.Failed("legacy load must not be used")
        }

        override fun commit(
            storeId: PersistentStoreId,
            expectedRevision: Long,
            state: PersistentBackendState
        ): PersistentBackendCommitResult {
            legacyCommitCalls += 1
            return PersistentBackendCommitResult.Failed("legacy commit must not be used")
        }

        override fun loadMetadata(storeId: PersistentStoreId): PersistentBackendMetadataLoadResult =
            if (revision == 0L && entries.isEmpty()) {
                PersistentBackendMetadataLoadResult.Missing
            } else {
                PersistentBackendMetadataLoadResult.Loaded(
                    PersistentBackendMetadata(
                        revision = revision,
                        highWatermark = highWatermark,
                        entryCount = entries.size.toLong()
                    )
                )
            }

        override fun loadEntry(
            storeId: PersistentStoreId,
            entityId: PersistentEntityId
        ): PersistentBackendEntryLoadResult {
            val entry = entries[entityId] ?: return PersistentBackendEntryLoadResult.Missing
            return PersistentBackendEntryLoadResult.Loaded(
                PersistentRecordSnapshot(entry.record, entry.generation)
            )
        }

        override fun loadPage(
            storeId: PersistentStoreId,
            request: PersistentBackendPageRequest
        ): PersistentBackendPageLoadResult {
            if (advanceRevisionOnNextPage) {
                advanceRevisionOnNextPage = false
                revision += 1L
            }
            val ordered = entries.values
                .map { PersistentRecordSnapshot(it.record, it.generation) }
                .sortedWith(compareBy({ it.record.createdAt }, { it.record.id.value }))
            val filtered = ordered.filter { snapshot ->
                val cursor = request.cursorExclusive ?: return@filter true
                snapshot.record.createdAt > cursor.createdAt ||
                    (snapshot.record.createdAt == cursor.createdAt &&
                        snapshot.record.id.value > cursor.entityId.value)
            }
            val page = filtered.take(request.limit)
            val next = if (filtered.size > request.limit && page.isNotEmpty()) {
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
            installCalls += 1
            if (revision != expectedRevision || highWatermark != expectedHighWatermark) {
                return PersistentBackendMutationResult.Conflict
            }
            if (entries.containsKey(entry.record.id) ||
                entry.generation.value != highWatermark + 1L
            ) {
                return PersistentBackendMutationResult.Rejected("invalid install")
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
            transitionCalls += 1
            if (revision != expectedRevision || highWatermark != expectedHighWatermark) {
                return PersistentBackendMutationResult.Conflict
            }
            val source = entries[sourceId]
                ?: return PersistentBackendMutationResult.Rejected("source missing")
            if (source.generation != sourceGeneration ||
                replacement.generation != sourceGeneration
            ) {
                return PersistentBackendMutationResult.Rejected("source generation stale")
            }
            if (replacement.record.id != sourceId &&
                entries.containsKey(replacement.record.id)
            ) {
                return PersistentBackendMutationResult.Rejected("replacement exists")
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
        ): PersistentBackendMutationResult {
            removeCalls += 1
            if (revision != expectedRevision || highWatermark != expectedHighWatermark) {
                return PersistentBackendMutationResult.Conflict
            }
            val source = entries[id]
                ?: return PersistentBackendMutationResult.Rejected("source missing")
            if (source.generation != generation) {
                return PersistentBackendMutationResult.Rejected("source generation stale")
            }
            entries.remove(id)
            revision += 1L
            return PersistentBackendMutationResult.Committed(metadata())
        }

        private fun metadata(): PersistentBackendMetadata =
            PersistentBackendMetadata(
                revision = revision,
                highWatermark = highWatermark,
                entryCount = entries.size.toLong()
            )
    }

    private data class Fixture(
        val foundation: FoundationComposition,
        val logs: InMemoryLogWriter
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "persistence-${sequence.incrementAndGet()}" }
        )
        return Fixture(foundation, logs)
    }

    private fun record(
        id: String,
        payload: String = "private-$id",
        createdAt: Instant = Instant.parse("2026-08-30T13:20:00Z")
    ) = PersistentRecord(
        id = PersistentEntityId(id),
        schemaId = PersistentSchemaId("test-record"),
        schemaVersion = PersistentSchemaVersion(1),
        payload = PersistentPayload(payload.encodeToByteArray()),
        createdAt = createdAt
    )

    private fun open(
        fixture: Fixture,
        backend: PersistentRecordBackend,
        storeId: PersistentStoreId = PersistentStoreId("cognitive-test")
    ): PersistentRecordStore = assertIs<PersistentStoreOpenResult.Opened>(
        PersistentRecordStore.open(fixture.foundation, storeId, backend)
    ).store

    @Test
    fun install_returns_exact_generation_ownership_and_duplicate_live_id_rejects() {
        val f = fixture()
        val store = open(f, InMemoryPersistentRecordBackend())
        val first = assertIs<PersistentInstallResult.Installed>(store.install(record("one")))

        assertEquals(PersistentGeneration(1), first.ownership.generation)
        assertEquals(PersistentEntityId("one"), first.ownership.record.id)
        assertIs<PersistentInstallResult.Rejected>(store.install(record("one", "different")))
        assertEquals(PersistentGeneration(1), store.inspect(PersistentEntityId("one"))?.generation)
    }

    @Test
    fun stale_exact_ownership_cannot_remove_replacement_generation() {
        val f = fixture()
        val store = open(f, InMemoryPersistentRecordBackend())
        val old = assertIs<PersistentInstallResult.Installed>(store.install(record("same"))).ownership
        assertIs<PersistentMutationResult.Committed>(old.remove())
        val replacement = assertIs<PersistentInstallResult.Installed>(store.install(record("same", "replacement"))).ownership

        assertEquals(PersistentGeneration(2), replacement.generation)
        assertIs<PersistentMutationResult.Rejected>(old.remove())
        assertEquals(PersistentGeneration(2), store.inspect(PersistentEntityId("same"))?.generation)
    }

    @Test
    fun generation_high_watermark_restores_monotonically_after_reopen() {
        val f = fixture()
        val backend = InMemoryPersistentRecordBackend()
        val firstStore = open(f, backend)
        val first = assertIs<PersistentInstallResult.Installed>(firstStore.install(record("one"))).ownership
        assertIs<PersistentMutationResult.Committed>(first.remove())

        val reopened = open(f, backend)
        val second = assertIs<PersistentInstallResult.Installed>(reopened.install(record("two"))).ownership
        assertEquals(PersistentGeneration(2), second.generation)
    }

    @Test
    fun deterministic_detached_snapshots_survive_reopen() {
        val f = fixture()
        val backend = InMemoryPersistentRecordBackend()
        val store = open(f, backend)
        assertIs<PersistentInstallResult.Installed>(
            store.install(record("b", createdAt = Instant.parse("2026-08-30T13:20:02Z")))
        )
        assertIs<PersistentInstallResult.Installed>(
            store.install(record("a", createdAt = Instant.parse("2026-08-30T13:20:01Z")))
        )

        val before = store.snapshotEntries()
        before.first().record.payload.copyBytes()[0] = 0
        val reopened = open(f, backend)
        val after = reopened.snapshotEntries()

        assertEquals(listOf("a", "b"), after.map { it.record.id.value })
        assertEquals(listOf(2L, 1L), after.map { it.generation.value })
        assertNotEquals(0, after.first().record.payload.copyBytes()[0].toInt())
    }

    @Test
    fun failed_backend_commit_is_failed_and_never_visible_as_success() {
        val f = fixture()
        val backend = InMemoryPersistentRecordBackend()
        val store = open(f, backend)
        backend.failNextCommit()

        assertIs<PersistentInstallResult.Failed>(store.install(record("failed")))
        assertFalse(store.contains(PersistentEntityId("failed")))
        assertNull(open(f, backend).find(PersistentEntityId("failed")))
    }

    @Test
    fun corrupt_and_incompatible_load_are_explicit_not_empty_store() {
        val f = fixture()
        val storeId = PersistentStoreId("recovery")
        val corruptBackend = InMemoryPersistentRecordBackend()
        corruptBackend.forceLoad(
            storeId,
            PersistentBackendLoadResult.Loaded(
                revision = 1,
                state = PersistentBackendState(
                    storeId = storeId,
                    highWatermark = 0,
                    entries = mapOf(
                        PersistentEntityId("bad") to PersistentBackendEntry(
                            PersistentGeneration(1), record("bad")
                        )
                    )
                )
            )
        )
        assertIs<PersistentStoreOpenResult.Corrupt>(
            PersistentRecordStore.open(f.foundation, storeId, corruptBackend)
        )

        val incompatibleBackend = InMemoryPersistentRecordBackend()
        incompatibleBackend.forceLoad(storeId, PersistentBackendLoadResult.Incompatible("schema epoch unsupported"))
        assertIs<PersistentStoreOpenResult.Incompatible>(
            PersistentRecordStore.open(f.foundation, storeId, incompatibleBackend)
        )
    }

    @Test
    fun indexed_snapshot_page_is_bounded_cursor_driven_and_revision_barriered() {
        val f = fixture()
        val backend = LazyIndexedMutationFixtureBackend()
        val store = open(
            f,
            backend,
            PersistentStoreId("bounded-page-seam")
        )

        assertIs<PersistentInstallResult.Installed>(
            store.install(
                record(
                    "one",
                    createdAt = Instant.parse("2026-08-30T13:20:01Z")
                )
            )
        )
        assertIs<PersistentInstallResult.Installed>(
            store.install(
                record(
                    "two",
                    createdAt = Instant.parse("2026-08-30T13:20:02Z")
                )
            )
        )

        val first = assertIs<PersistentRecordPageResult.Loaded>(
            store.snapshotPageResult(
                PersistentBackendPageRequest(
                    limit = 1,
                    order = PersistentBackendPageOrder.OLDEST_FIRST,
                    cursorExclusive = null
                )
            )
        )
        assertEquals(
            listOf("one"),
            first.entries.map { it.record.id.value }
        )
        val cursor = assertNotNull(first.nextCursor)

        val second = assertIs<PersistentRecordPageResult.Loaded>(
            store.snapshotPageResult(
                PersistentBackendPageRequest(
                    limit = 1,
                    order = PersistentBackendPageOrder.OLDEST_FIRST,
                    cursorExclusive = cursor
                )
            )
        )
        assertEquals(
            listOf("two"),
            second.entries.map { it.record.id.value }
        )
        assertNull(second.nextCursor)

        backend.advanceRevisionOnNextPage = true
        val failed = assertIs<PersistentRecordPageResult.Failed>(
            store.snapshotPageResult(
                PersistentBackendPageRequest(
                    limit = 1,
                    order = PersistentBackendPageOrder.OLDEST_FIRST,
                    cursorExclusive = null
                )
            )
        )
        assertTrue(failed.reason.contains("changed during enumeration"))
    }

    @Test
    fun indexed_snapshot_enumeration_rejects_revision_change_with_same_entry_count() {
        val f = fixture()
        val backend = LazyIndexedMutationFixtureBackend()
        val store = open(f, backend, PersistentStoreId("metadata-barrier"))

        assertIs<PersistentInstallResult.Installed>(
            store.install(record("one"))
        )
        backend.advanceRevisionOnNextPage = true

        val result = assertIs<PersistentRecordSnapshotEntriesResult.Failed>(
            store.snapshotEntriesResult()
        )
        assertTrue(result.reason.contains("changed during enumeration"))
    }

    @Test
    fun payload_is_redacted_from_rendering_and_operational_logs() {
        val f = fixture()
        val secret = "TOP-SECRET-COGNITIVE-PAYLOAD"
        val value = record("private", secret)
        val store = open(f, InMemoryPersistentRecordBackend())
        assertIs<PersistentInstallResult.Installed>(store.install(value))

        assertFalse(value.toString().contains(secret))
        assertFalse(value.payload.toString().contains(secret))
        assertFalse(f.logs.snapshot().joinToString("\n").contains(secret))
    }

    @Test
    fun separate_backends_isolate_same_logical_store_id() {
        val f = fixture()
        val id = PersistentStoreId("same-store")
        val left = open(f, InMemoryPersistentRecordBackend(), id)
        val right = open(f, InMemoryPersistentRecordBackend(), id)

        val leftOwnership = assertIs<PersistentInstallResult.Installed>(left.install(record("x", "left"))).ownership
        val rightOwnership = assertIs<PersistentInstallResult.Installed>(right.install(record("x", "right"))).ownership

        assertEquals(PersistentGeneration(1), leftOwnership.generation)
        assertEquals(PersistentGeneration(1), rightOwnership.generation)
        assertNotEquals(left.find(PersistentEntityId("x"))?.payload, right.find(PersistentEntityId("x"))?.payload)
    }

    @Test
    fun shared_backend_is_explicit_shared_durable_state() {
        val f = fixture()
        val backend = InMemoryPersistentRecordBackend()
        val id = PersistentStoreId("shared-store")
        val first = open(f, backend, id)
        assertIs<PersistentInstallResult.Installed>(first.install(record("shared")))

        val reopened = open(f, backend, id)
        assertTrue(reopened.contains(PersistentEntityId("shared")))
        assertEquals(PersistentGeneration(1), reopened.inspect(PersistentEntityId("shared"))?.generation)
    }

    @Test
    fun indexed_exact_lookup_preserves_missing_corrupt_incompatible_failed_and_found_states() {
        val f = fixture()
        val backend = IndexedLookupFixtureBackend()
        val store = open(f, backend)
        val id = PersistentEntityId("indexed")

        assertEquals(PersistentRecordLookupResult.Missing, store.inspectResult(id))

        backend.exactResult = PersistentBackendEntryLoadResult.Corrupt
        assertEquals(PersistentRecordLookupResult.Corrupt, store.inspectResult(id))

        backend.exactResult = PersistentBackendEntryLoadResult.Incompatible("future indexed format")
        val incompatible = assertIs<PersistentRecordLookupResult.Incompatible>(
            store.inspectResult(id)
        )
        assertEquals("future indexed format", incompatible.reason)

        val failure = IllegalStateException("indexed read failed")
        backend.exactResult = PersistentBackendEntryLoadResult.Failed(
            "indexed read failed",
            failure
        )
        val failed = assertIs<PersistentRecordLookupResult.Failed>(store.inspectResult(id))
        assertEquals("indexed read failed", failed.reason)
        assertEquals(failure, failed.throwable)

        val snapshot = PersistentRecordSnapshot(
            record("indexed"),
            PersistentGeneration(7)
        )
        backend.exactResult = PersistentBackendEntryLoadResult.Loaded(snapshot)
        val found = assertIs<PersistentRecordLookupResult.Found>(store.inspectResult(id))
        assertEquals(PersistentGeneration(7), found.snapshot.generation)
        assertEquals(id, found.snapshot.record.id)
    }

    @Test
    fun indexed_snapshot_enumeration_is_bounded_paged_and_fail_closed() {
        val f = fixture()
        val backend = IndexedLookupFixtureBackend()
        val store = open(f, backend)

        val one = PersistentRecordSnapshot(
            record("one", createdAt = Instant.parse("2026-08-30T13:20:01Z")),
            PersistentGeneration(1)
        )
        val two = PersistentRecordSnapshot(
            record("two", createdAt = Instant.parse("2026-08-30T13:20:02Z")),
            PersistentGeneration(2)
        )
        val firstCursor = PersistentBackendPageCursor(
            one.record.createdAt,
            one.record.id
        )
        backend.pageHandler = { request ->
            assertTrue(request.limit <= PersistentBackendPageRequest.MAX_PAGE_SIZE)
            if (request.cursorExclusive == null) {
                PersistentBackendPageLoadResult.Loaded(
                    PersistentBackendPage(listOf(one), firstCursor)
                )
            } else {
                assertEquals(firstCursor, request.cursorExclusive)
                PersistentBackendPageLoadResult.Loaded(
                    PersistentBackendPage(listOf(two), null)
                )
            }
        }

        val loaded = assertIs<PersistentRecordSnapshotEntriesResult.Loaded>(
            store.snapshotEntriesResult()
        )
        assertEquals(listOf("one", "two"), loaded.entries.map { it.record.id.value })

        backend.pageHandler = { PersistentBackendPageLoadResult.Corrupt }
        assertEquals(
            PersistentRecordSnapshotEntriesResult.Corrupt,
            store.snapshotEntriesResult()
        )

        backend.pageHandler = {
            PersistentBackendPageLoadResult.Incompatible("future page format")
        }
        val incompatible = assertIs<PersistentRecordSnapshotEntriesResult.Incompatible>(
            store.snapshotEntriesResult()
        )
        assertEquals("future page format", incompatible.reason)

        val failure = IllegalStateException("page read failed")
        backend.pageHandler = {
            PersistentBackendPageLoadResult.Failed("page read failed", failure)
        }
        val failed = assertIs<PersistentRecordSnapshotEntriesResult.Failed>(
            store.snapshotEntriesResult()
        )
        assertEquals(failure, failed.throwable)
    }

    @Test
    fun indexed_store_opens_from_metadata_and_mutates_without_legacy_snapshot_path() {
        val f = fixture()
        val backend = LazyIndexedMutationFixtureBackend()
        val storeId = PersistentStoreId("lazy-indexed-core")
        val store = open(f, backend, storeId)

        assertEquals(0, backend.legacyLoadCalls)
        assertEquals(0, backend.legacyCommitCalls)

        val first = assertIs<PersistentInstallResult.Installed>(
            store.install(record("one"))
        ).ownership
        assertEquals(PersistentGeneration(1), first.generation)
        assertEquals(1, backend.installCalls)
        assertEquals(0, backend.legacyCommitCalls)

        val reopened = open(f, backend, storeId)
        assertEquals(0, backend.legacyLoadCalls)
        val found = assertIs<PersistentRecordLookupResult.Found>(
            reopened.inspectResult(PersistentEntityId("one"))
        )
        assertEquals(PersistentGeneration(1), found.snapshot.generation)

        val transitioned = assertIs<PersistentRecordTransitionResult.Committed>(
            reopened.transitionExact(
                sourceId = PersistentEntityId("one"),
                sourceGeneration = PersistentGeneration(1),
                replacement = record("renamed", "replacement")
            )
        )
        assertEquals(PersistentEntityId("renamed"), transitioned.ownership.record.id)
        assertEquals(1, backend.transitionCalls)
        assertEquals(0, backend.legacyCommitCalls)

        assertIs<PersistentMutationResult.Committed>(
            transitioned.ownership.remove()
        )
        assertEquals(1, backend.removeCalls)
        val afterRemove = open(f, backend, storeId)
        assertEquals(PersistentRecordLookupResult.Missing, afterRemove.inspectResult(PersistentEntityId("renamed")))

        val second = assertIs<PersistentInstallResult.Installed>(
            afterRemove.install(record("two"))
        ).ownership
        assertEquals(PersistentGeneration(2), second.generation)
        assertEquals(0, backend.legacyLoadCalls)
        assertEquals(0, backend.legacyCommitCalls)
    }

    @Test
    fun indexed_metadata_refresh_recovers_install_after_external_writer_conflict() {
        val f = fixture()
        val backend = LazyIndexedMutationFixtureBackend()
        val storeId = PersistentStoreId("indexed-refresh-install")
        val first = open(f, backend, storeId)
        val stale = open(f, backend, storeId)

        assertIs<PersistentInstallResult.Installed>(first.install(record("one")))
        assertIs<PersistentInstallResult.Rejected>(stale.install(record("two")))

        val refreshed = assertIs<PersistentRecordMetadataRefreshResult.Refreshed>(
            stale.refreshIndexedMetadata()
        )
        assertEquals(1L, refreshed.metadata.revision)
        assertEquals(1L, refreshed.metadata.highWatermark)
        assertEquals(1L, refreshed.metadata.entryCount)

        val installed = assertIs<PersistentInstallResult.Installed>(
            stale.install(record("two"))
        )
        assertEquals(PersistentGeneration(2), installed.ownership.generation)
    }

    @Test
    fun indexed_metadata_refresh_invalidates_pre_refresh_ownership_handles() {
        val f = fixture()
        val backend = LazyIndexedMutationFixtureBackend()
        val storeId = PersistentStoreId("indexed-refresh-ownership")
        val stale = open(f, backend, storeId)
        val oldOwnership = assertIs<PersistentInstallResult.Installed>(
            stale.install(record("one"))
        ).ownership
        val external = open(f, backend, storeId)

        assertIs<PersistentInstallResult.Installed>(external.install(record("two")))
        assertIs<PersistentRecordMetadataRefreshResult.Refreshed>(
            stale.refreshIndexedMetadata()
        )

        val rejected = assertIs<PersistentMutationResult.Rejected>(
            oldOwnership.remove()
        )
        assertTrue(rejected.reason.contains("stale after metadata refresh"))
        assertIs<PersistentRecordLookupResult.Found>(
            stale.inspectResult(PersistentEntityId("one"))
        )
    }

    @Test
    fun indexed_metadata_refresh_requires_fresh_exact_read_before_transition_retry() {
        val f = fixture()
        val backend = LazyIndexedMutationFixtureBackend()
        val storeId = PersistentStoreId("indexed-refresh-transition")
        val seed = open(f, backend, storeId)
        assertIs<PersistentInstallResult.Installed>(seed.install(record("head")))

        val stale = open(f, backend, storeId)
        val external = open(f, backend, storeId)
        assertIs<PersistentRecordTransitionResult.Committed>(
            external.transitionExact(
                sourceId = PersistentEntityId("head"),
                sourceGeneration = PersistentGeneration(1),
                replacement = record("head", "external")
            )
        )
        assertIs<PersistentRecordTransitionResult.Rejected>(
            stale.transitionExact(
                sourceId = PersistentEntityId("head"),
                sourceGeneration = PersistentGeneration(1),
                replacement = record("head", "stale-attempt")
            )
        )

        assertIs<PersistentRecordMetadataRefreshResult.Refreshed>(
            stale.refreshIndexedMetadata()
        )
        val fresh = assertIs<PersistentRecordLookupResult.Found>(
            stale.inspectResult(PersistentEntityId("head"))
        ).snapshot
        assertEquals("external", fresh.record.payload.copyBytes().decodeToString())

        assertIs<PersistentRecordTransitionResult.Committed>(
            stale.transitionExact(
                sourceId = fresh.record.id,
                sourceGeneration = fresh.generation,
                replacement = record("head", "fresh-retry")
            )
        )
        assertEquals(
            "fresh-retry",
            assertIs<PersistentRecordLookupResult.Found>(
                stale.inspectResult(PersistentEntityId("head"))
            ).snapshot.record.payload.copyBytes().decodeToString()
        )
    }

    @Test
    fun persistence_api_contains_no_authority_license_android_scheduler_or_cognitive_policy_semantics() {
        val forbidden = setOf(
            "authority", "permission", "license", "entitlement", "android", "keystore",
            "scheduler", "schedule", "retry", "execute", "executor", "capability", "memory", "knowledge"
        )
        val types = listOf(
            PersistentRecord::class.java,
            PersistentRecordSnapshot::class.java,
            PersistentRecordOwnership::class.java,
            PersistentRecordStore::class.java,
            PersistentRecordBackend::class.java
        )
        types.forEach { type ->
            val names = type.methods.map { it.name.lowercase() }
            assertFalse(names.any { name -> forbidden.any { token -> name.contains(token) } }, type.name)
        }
    }
}
