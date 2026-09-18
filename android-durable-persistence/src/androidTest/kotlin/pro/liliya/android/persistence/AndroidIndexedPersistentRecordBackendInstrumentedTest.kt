package pro.liliya.android.persistence

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.core.persistence.PersistentBackendCommitResult
import pro.liliya.core.persistence.PersistentBackendEntry
import pro.liliya.core.persistence.PersistentBackendLoadResult
import pro.liliya.core.persistence.PersistentBackendState
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentGeneration
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion
import pro.liliya.core.persistence.PersistentStoreId

@RunWith(AndroidJUnit4::class)
class AndroidIndexedPersistentRecordBackendInstrumentedTest {

    @Test
    fun legacy_ldp1_is_imported_once_and_newer_indexed_state_wins_after_reopen() =
        withCleanRoot { context, root ->
            val storeId = PersistentStoreId("legacy-migration")
            val legacy = AndroidDurablePersistentRecordBackend.create(context, TEST_DIRECTORY)
            val first = state(storeId, 1, mapOf("a" to "legacy"))
            assertEquals(
                PersistentBackendCommitResult.Committed(1),
                legacy.commit(storeId, 0, first)
            )

            val indexed = AndroidIndexedPersistentRecordBackend.create(context, TEST_DIRECTORY)
            val imported = assertIs<PersistentBackendLoadResult.Loaded>(indexed.load(storeId))
            assertEquals(1, imported.revision)
            assertEquals("legacy", payload(imported, "a"))

            val second = state(
                storeId,
                2,
                mapOf("a" to "legacy", "b" to "indexed")
            )
            assertEquals(
                PersistentBackendCommitResult.Committed(2),
                indexed.commit(storeId, 1, second)
            )

            val reopened = AndroidIndexedPersistentRecordBackend.create(context, TEST_DIRECTORY)
            val loaded = assertIs<PersistentBackendLoadResult.Loaded>(reopened.load(storeId))
            assertEquals(2, loaded.revision)
            assertEquals(2, loaded.state.highWatermark)
            assertEquals(setOf("a", "b"), loaded.state.entries.keys.map { it.value }.toSet())
            assertEquals("indexed", payload(loaded, "b"))

            assertTrue(root.listFiles().orEmpty().any { it.name.endsWith(".lpr") })
        }

    @Test
    fun stale_revision_conflicts_and_unchanged_record_row_is_not_rewritten() =
        withCleanRoot { context, root ->
            val storeId = PersistentStoreId("record-granular")
            val backend = AndroidIndexedPersistentRecordBackend.create(context, TEST_DIRECTORY)
            val first = state(storeId, 2, mapOf("a" to "one", "b" to "two"))
            assertEquals(
                PersistentBackendCommitResult.Committed(1),
                backend.commit(storeId, 0, first)
            )
            val rowBefore = rowId(root, storeId.value, "a")

            val second = state(
                storeId,
                3,
                mapOf("a" to "one", "b" to "two", "c" to "three")
            )
            assertEquals(
                PersistentBackendCommitResult.Committed(2),
                backend.commit(storeId, 1, second)
            )
            assertEquals(rowBefore, rowId(root, storeId.value, "a"))

            assertEquals(
                PersistentBackendCommitResult.Conflict,
                backend.commit(storeId, 1, second)
            )
            val loaded = assertIs<PersistentBackendLoadResult.Loaded>(backend.load(storeId))
            assertEquals(2, loaded.revision)
            assertEquals(3, loaded.state.entries.size)
        }

    @Test
    fun unknown_indexed_database_version_is_incompatible_fail_closed() =
        withCleanRoot { context, root ->
            val backend = AndroidIndexedPersistentRecordBackend.create(context, TEST_DIRECTORY)
            val storeId = PersistentStoreId("future-version")
            assertEquals(
                PersistentBackendCommitResult.Committed(1),
                backend.commit(storeId, 0, state(storeId, 1, mapOf("a" to "one")))
            )

            val db = SQLiteDatabase.openDatabase(
                File(root, "liliya-indexed-v2.sqlite3").absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE
            )
            db.use { it.version = 99 }

            assertIs<PersistentBackendLoadResult.Incompatible>(
                AndroidIndexedPersistentRecordBackend.create(context, TEST_DIRECTORY).load(storeId)
            )
        }

    @Test
    fun missing_indexed_row_is_detected_as_corrupt_instead_of_silent_data_loss() =
        withCleanRoot { context, root ->
            val storeId = PersistentStoreId("row-loss")
            val backend = AndroidIndexedPersistentRecordBackend.create(context, TEST_DIRECTORY)
            assertEquals(
                PersistentBackendCommitResult.Committed(1),
                backend.commit(storeId, 0, state(storeId, 2, mapOf("a" to "one", "b" to "two")))
            )

            val db = SQLiteDatabase.openDatabase(
                File(root, "liliya-indexed-v2.sqlite3").absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE
            )
            db.use {
                it.delete(
                    "records",
                    "store_id=? AND entity_id=?",
                    arrayOf(storeId.value, "b")
                )
            }

            assertEquals(
                PersistentBackendLoadResult.Corrupt,
                AndroidIndexedPersistentRecordBackend.create(context, TEST_DIRECTORY).load(storeId)
            )
        }

    @Test
    fun indexed_backend_accepts_more_than_legacy_25000_entry_ceiling() =
        withCleanRoot { context, _ ->
            val storeId = PersistentStoreId("large-indexed-store")
            val backend = AndroidIndexedPersistentRecordBackend.create(context, TEST_DIRECTORY)
            val entries = LinkedHashMap<PersistentEntityId, PersistentBackendEntry>(25_001)
            repeat(25_001) { index ->
                val generation = index.toLong() + 1L
                val id = PersistentEntityId("e-" + index.toString().padStart(5, '0'))
                entries[id] = PersistentBackendEntry(
                    generation = PersistentGeneration(generation),
                    record = record(id, generation, byteArrayOf((index and 0xff).toByte()))
                )
            }
            val state = PersistentBackendState(
                storeId = storeId,
                highWatermark = 25_001,
                entries = entries
            )

            assertEquals(
                PersistentBackendCommitResult.Committed(1),
                backend.commit(storeId, 0, state)
            )
            val loaded = assertIs<PersistentBackendLoadResult.Loaded>(
                AndroidIndexedPersistentRecordBackend.create(context, TEST_DIRECTORY).load(storeId)
            )
            assertEquals(25_001, loaded.state.entries.size)
            assertEquals(25_001, loaded.state.highWatermark)
        }

    private fun state(
        storeId: PersistentStoreId,
        highWatermark: Long,
        values: Map<String, String>
    ): PersistentBackendState {
        var generation = 0L
        val entries = LinkedHashMap<PersistentEntityId, PersistentBackendEntry>()
        for ((name, value) in values) {
            generation += 1L
            val id = PersistentEntityId(name)
            entries[id] = PersistentBackendEntry(
                generation = PersistentGeneration(generation),
                record = record(id, generation, value.encodeToByteArray())
            )
        }
        return PersistentBackendState(storeId, highWatermark, entries)
    }

    private fun record(
        id: PersistentEntityId,
        generation: Long,
        bytes: ByteArray
    ): PersistentRecord =
        PersistentRecord(
            id = id,
            schemaId = PersistentSchemaId("indexed-test-record"),
            schemaVersion = PersistentSchemaVersion(1),
            payload = PersistentPayload(bytes),
            createdAt = Instant.ofEpochSecond(1_800_000_000L + generation)
        )

    private fun payload(
        loaded: PersistentBackendLoadResult.Loaded,
        id: String
    ): String =
        loaded.state.entries.getValue(PersistentEntityId(id))
            .record.payload.copyBytes().decodeToString()

    private fun rowId(root: File, storeId: String, entityId: String): Long {
        val db = SQLiteDatabase.openDatabase(
            File(root, "liliya-indexed-v2.sqlite3").absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
        return db.use {
            it.rawQuery(
                "SELECT rowid FROM records WHERE store_id=? AND entity_id=?",
                arrayOf(storeId, entityId)
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getLong(0)
            }
        }
    }

    private inline fun withCleanRoot(block: (android.content.Context, File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val root = File(context.filesDir, TEST_DIRECTORY)
        root.deleteRecursively()
        try {
            block(context, root)
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val TEST_DIRECTORY = "indexed-durable-persistence-instrumented-test"
    }
}
