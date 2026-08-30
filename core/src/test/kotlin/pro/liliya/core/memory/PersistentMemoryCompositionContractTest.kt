package pro.liliya.core.memory

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.persistence.InMemoryPersistentRecordBackend
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentInstallResult
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentRecordStore
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion
import pro.liliya.core.persistence.PersistentStoreId
import pro.liliya.core.persistence.PersistentStoreOpenResult

class PersistentMemoryCompositionContractTest {
    private fun foundation(): FoundationComposition {
        val sequence = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context ->
                StructuredLogger(context, InMemoryLogWriter())
            },
            correlationIds = CorrelationIdGenerator {
                "persistent-memory-${sequence.incrementAndGet()}"
            }
        )
    }

    private fun record(
        id: String = "memory-1",
        content: String = "private durable memory",
        createdAt: Instant = Instant.parse("2026-08-30T14:25:00Z")
    ) = MemoryRecord(
        id = MemoryRecordId(id),
        provenance = MemoryProvenance(
            sourceId = MemorySourceId("conversation"),
            sourceReference = MemorySourceReference("turn-1")
        ),
        content = content,
        createdAt = createdAt
    )

    private fun open(
        backend: InMemoryPersistentRecordBackend,
        storeId: String = "memory-store"
    ): PersistentMemoryComposition = assertIs<PersistentMemoryOpenResult.Opened>(
        PersistentMemoryComposition.open(
            foundation = foundation(),
            storeId = PersistentStoreId(storeId),
            backend = backend
        )
    ).composition

    @Test
    fun remember_is_visible_only_after_durable_commit_and_reopen_restores_exact_generation() {
        val backend = InMemoryPersistentRecordBackend()
        val memory = record()
        val first = open(backend)

        val remembered = assertIs<PersistentMemoryRememberResult.Remembered>(
            first.remember(memory)
        )
        assertEquals(memory, first.find(memory.id))
        assertEquals(remembered.ownership.generation, first.inspect(memory.id)?.generation)

        val reopened = open(backend)
        assertEquals(memory, reopened.find(memory.id))
        assertEquals(
            remembered.ownership.generation,
            reopened.inspect(memory.id)?.generation
        )
    }

    @Test
    fun failed_durable_remember_commit_keeps_memory_locally_absent_and_reopen_empty() {
        val backend = InMemoryPersistentRecordBackend()
        val composition = open(backend)
        val memory = record()
        backend.failNextCommit()

        assertIs<PersistentMemoryRememberResult.Failed>(composition.remember(memory))
        assertNull(composition.find(memory.id))
        assertTrue(composition.snapshot().isEmpty())

        val reopened = open(backend)
        assertFalse(reopened.contains(memory.id))
        assertTrue(reopened.snapshot().isEmpty())
    }

    @Test
    fun durable_remove_commits_before_local_exact_removal() {
        val backend = InMemoryPersistentRecordBackend()
        val composition = open(backend)
        val memory = record()
        val ownership = assertIs<PersistentMemoryRememberResult.Remembered>(
            composition.remember(memory)
        ).ownership

        assertIs<PersistentMemoryMutationResult.Committed>(ownership.remove())
        assertFalse(composition.contains(memory.id))

        val reopened = open(backend)
        assertFalse(reopened.contains(memory.id))
    }

    @Test
    fun failed_durable_remove_keeps_local_and_reopened_memory_live() {
        val backend = InMemoryPersistentRecordBackend()
        val composition = open(backend)
        val memory = record()
        val ownership = assertIs<PersistentMemoryRememberResult.Remembered>(
            composition.remember(memory)
        ).ownership
        backend.failNextCommit()

        assertIs<PersistentMemoryMutationResult.Failed>(ownership.remove())
        assertEquals(memory, composition.find(memory.id))

        val reopened = open(backend)
        assertEquals(memory, reopened.find(memory.id))
        assertEquals(ownership.generation, reopened.inspect(memory.id)?.generation)
    }

    @Test
    fun stale_owner_cannot_remove_newer_persisted_replacement_generation() {
        val backend = InMemoryPersistentRecordBackend()
        val composition = open(backend)
        val firstRecord = record(content = "first private memory")
        val first = assertIs<PersistentMemoryRememberResult.Remembered>(
            composition.remember(firstRecord)
        ).ownership
        assertIs<PersistentMemoryMutationResult.Committed>(first.remove())

        val replacementRecord = record(content = "replacement private memory")
        val replacement = assertIs<PersistentMemoryRememberResult.Remembered>(
            composition.remember(replacementRecord)
        ).ownership
        assertTrue(replacement.generation.value > first.generation.value)

        assertIs<PersistentMemoryMutationResult.Rejected>(first.remove())
        assertEquals(replacementRecord, composition.find(replacementRecord.id))
        assertEquals(replacement.generation, composition.inspect(replacementRecord.id)?.generation)

        val reopened = open(backend)
        assertEquals(replacementRecord, reopened.find(replacementRecord.id))
        assertEquals(replacement.generation, reopened.inspect(replacementRecord.id)?.generation)
    }

    @Test
    fun concurrent_distinct_remembers_on_one_composition_commit_without_local_generation_races() {
        val backend = InMemoryPersistentRecordBackend()
        val composition = open(backend)
        val attempts = 16
        val executor = Executors.newFixedThreadPool(attempts)
        val ready = CountDownLatch(attempts)
        val start = CountDownLatch(1)
        val done = CountDownLatch(attempts)
        val remembered = AtomicInteger(0)

        try {
            repeat(attempts) { index ->
                executor.submit {
                    try {
                        ready.countDown()
                        start.await()
                        if (
                            composition.remember(
                                record(
                                    id = "memory-$index",
                                    content = "private-$index",
                                    createdAt = Instant.parse("2026-08-30T14:25:00Z").plusSeconds(index.toLong())
                                )
                            ) is PersistentMemoryRememberResult.Remembered
                        ) {
                            remembered.incrementAndGet()
                        }
                    } finally {
                        done.countDown()
                    }
                }
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertEquals(attempts, remembered.get())
            assertEquals(attempts, composition.snapshotEntries().size)
            assertEquals(
                (1L..attempts.toLong()).toSet(),
                composition.snapshotEntries().map { it.generation.value }.toSet()
            )

            val reopened = open(backend)
            assertEquals(attempts, reopened.snapshotEntries().size)
            assertEquals(
                composition.snapshotEntries(),
                reopened.snapshotEntries()
            )
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun shared_backend_stale_composition_conflict_does_not_publish_memory_locally() {
        val backend = InMemoryPersistentRecordBackend()
        val first = open(backend)
        val stale = open(backend)
        val committed = record(id = "committed")
        val rejected = record(id = "rejected")

        assertIs<PersistentMemoryRememberResult.Remembered>(first.remember(committed))
        assertIs<PersistentMemoryRememberResult.Rejected>(stale.remember(rejected))
        assertFalse(stale.contains(rejected.id))

        val reopened = open(backend)
        assertEquals(committed, reopened.find(committed.id))
        assertFalse(reopened.contains(rejected.id))
    }

    @Test
    fun corrupt_payload_after_valid_record_fails_reopen_without_partial_composition() {
        val backend = InMemoryPersistentRecordBackend()
        val valid = open(backend)
        val validRecord = record(id = "valid")
        assertIs<PersistentMemoryRememberResult.Remembered>(valid.remember(validRecord))

        val rawStore = assertIs<PersistentStoreOpenResult.Opened>(
            PersistentRecordStore.open(
                foundation = foundation(),
                storeId = PersistentStoreId("memory-store"),
                backend = backend
            )
        ).store
        assertIs<PersistentInstallResult.Installed>(
            rawStore.install(
                PersistentRecord(
                    id = PersistentEntityId("corrupt"),
                    schemaId = PersistentSchemaId("memory-record"),
                    schemaVersion = PersistentSchemaVersion(1),
                    payload = PersistentPayload(byteArrayOf(1, 2, 3)),
                    createdAt = Instant.parse("2026-08-30T14:26:00Z")
                )
            )
        )

        assertIs<PersistentMemoryOpenResult.Corrupt>(
            PersistentMemoryComposition.open(
                foundation = foundation(),
                storeId = PersistentStoreId("memory-store"),
                backend = backend
            )
        )
    }

    @Test
    fun failure_rendering_redacts_backend_throwable_message_and_memory_content() {
        val backend = InMemoryPersistentRecordBackend()
        val composition = open(backend)
        val privateContent = "ultra-private-memory-content"
        backend.failNextCommit(IllegalStateException("backend leaked $privateContent"))

        val failed = assertIs<PersistentMemoryRememberResult.Failed>(
            composition.remember(record(content = privateContent))
        )
        val rendered = failed.toString()

        assertFalse(rendered.contains(privateContent))
        assertFalse(rendered.contains("backend leaked"))
        assertTrue(rendered.contains("java.lang.IllegalStateException"))
        assertEquals("persistent memory durable install failed", failed.reason)
    }
}
