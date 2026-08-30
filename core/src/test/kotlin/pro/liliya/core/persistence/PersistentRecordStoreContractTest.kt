package pro.liliya.core.persistence

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
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
