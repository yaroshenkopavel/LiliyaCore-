package pro.liliya.android.persistence

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
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
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class AndroidDurablePersistentRecordBackendInstrumentedTest {

    @Test
    fun commit_reopen_conflict_and_temp_isolation_use_real_app_private_filesystem() = withCleanRoot {
        context, root ->
        val storeId = PersistentStoreId("memory-main")
        val firstState = state(storeId, highWatermark = 1, content = "ciphertext-one")
        val backend = AndroidDurablePersistentRecordBackend.create(context, TEST_DIRECTORY)

        assertEquals(PersistentBackendLoadResult.Missing, backend.load(storeId))
        assertEquals(
            PersistentBackendCommitResult.Committed(1),
            backend.commit(storeId, expectedRevision = 0, state = firstState)
        )

        val reopened = AndroidDurablePersistentRecordBackend.create(context, TEST_DIRECTORY)
        val loaded = assertIs<PersistentBackendLoadResult.Loaded>(reopened.load(storeId))
        assertEquals(1, loaded.revision)
        assertEquals(firstState.storeId, loaded.state.storeId)
        assertEquals(firstState.highWatermark, loaded.state.highWatermark)

        val stale = state(storeId, highWatermark = 2, content = "ciphertext-stale")
        assertEquals(
            PersistentBackendCommitResult.Conflict,
            reopened.commit(storeId, expectedRevision = 0, state = stale)
        )
        val afterConflict = assertIs<PersistentBackendLoadResult.Loaded>(reopened.load(storeId))
        assertEquals(1, afterConflict.revision)
        assertEquals(firstState.highWatermark, afterConflict.state.highWatermark)

        File(root, publishedName(storeId) + ".tmp").writeBytes(byteArrayOf(1, 2, 3, 4))
        val afterTemp = assertIs<PersistentBackendLoadResult.Loaded>(
            AndroidDurablePersistentRecordBackend.create(context, TEST_DIRECTORY).load(storeId)
        )
        assertEquals(1, afterTemp.revision)
        assertEquals(firstState.highWatermark, afterTemp.state.highWatermark)
    }

    @Test
    fun truncated_published_state_is_corrupt_not_missing() = withCleanRoot { context, root ->
        val storeId = PersistentStoreId("knowledge-main")
        val backend = AndroidDurablePersistentRecordBackend.create(context, TEST_DIRECTORY)
        assertEquals(
            PersistentBackendCommitResult.Committed(1),
            backend.commit(storeId, 0, state(storeId, 1, "opaque"))
        )

        val published = File(root, publishedName(storeId))
        assertTrue(published.isFile)
        val bytes = published.readBytes()
        published.writeBytes(bytes.copyOf(bytes.size - 5))

        assertEquals(
            PersistentBackendLoadResult.Corrupt,
            AndroidDurablePersistentRecordBackend.create(context, TEST_DIRECTORY).load(storeId)
        )
    }

    @Test
    fun store_namespaces_are_isolated() = withCleanRoot { context, _ ->
        val memory = PersistentStoreId("memory")
        val knowledge = PersistentStoreId("knowledge")
        val backend = AndroidDurablePersistentRecordBackend.create(context, TEST_DIRECTORY)

        assertEquals(
            PersistentBackendCommitResult.Committed(1),
            backend.commit(memory, 0, state(memory, 1, "memory-ciphertext"))
        )
        assertEquals(PersistentBackendLoadResult.Missing, backend.load(knowledge))
        assertEquals(
            PersistentBackendCommitResult.Committed(1),
            backend.commit(knowledge, 0, state(knowledge, 1, "knowledge-ciphertext"))
        )

        assertEquals(1, assertIs<PersistentBackendLoadResult.Loaded>(backend.load(memory)).revision)
        assertEquals(1, assertIs<PersistentBackendLoadResult.Loaded>(backend.load(knowledge)).revision)
    }

    private fun state(
        storeId: PersistentStoreId,
        highWatermark: Long,
        content: String
    ): PersistentBackendState {
        val id = PersistentEntityId("entity-$highWatermark")
        return PersistentBackendState(
            storeId = storeId,
            highWatermark = highWatermark,
            entries = mapOf(
                id to PersistentBackendEntry(
                    generation = PersistentGeneration(highWatermark),
                    record = PersistentRecord(
                        id = id,
                        schemaId = PersistentSchemaId("test-schema"),
                        schemaVersion = PersistentSchemaVersion(1),
                        payload = PersistentPayload(content.toByteArray()),
                        createdAt = Instant.parse("2026-09-05T15:00:00Z")
                    )
                )
            )
        )
    }

    private fun publishedName(storeId: PersistentStoreId): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(storeId.value.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "$digest.lpr"
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
        const val TEST_DIRECTORY = "durable-persistence-instrumented-test"
    }
}
