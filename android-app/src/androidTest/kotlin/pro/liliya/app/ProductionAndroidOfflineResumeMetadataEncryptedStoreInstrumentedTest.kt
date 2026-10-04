package pro.liliya.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.core.encryption.CognitiveDekGeneration
import pro.liliya.core.encryption.CognitiveDekId
import pro.liliya.core.encryption.CognitiveDekReference
import pro.liliya.core.persistence.PersistentStoreId

@RunWith(AndroidJUnit4::class)
class ProductionAndroidOfflineResumeMetadataEncryptedStoreInstrumentedTest {
    @Test
    fun exact_round_trip_tamper_reject_and_missing_state() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ProductionAndroidOfflineResumeMetadataEncryptedStore.create(
            context = context,
            alias = "pro.liliya.offline-resume-metadata.acceptance"
        )
        store.deleteForTests()

        assertIs<ProductionAndroidOfflineResumeMetadataLoadResult.Missing>(store.load())

        val expected = ProductionAndroidOfflineResumeMetadata(
            activeDek = CognitiveDekReference(
                id = CognitiveDekId("offline-resume-cognitive-dek"),
                generation = CognitiveDekGeneration(7)
            ),
            memoryStoreId = PersistentStoreId("offline-resume-memory"),
            knowledgeStoreId = PersistentStoreId("offline-resume-knowledge"),
            learningMutationStoreId = PersistentStoreId("offline-resume-learning"),
            cognitiveStorageDirectoryName = "offline-resume-cognitive",
            semanticDirectoryName = "offline-resume-semantic"
        )

        try {
            assertIs<ProductionAndroidOfflineResumeMetadataStoreResult.Stored>(
                store.store(expected)
            )
            val loaded = assertIs<ProductionAndroidOfflineResumeMetadataLoadResult.Loaded>(
                store.load()
            )
            assertEquals(expected, loaded.metadata)

            val published = store.publishedFileForTest()
            val bytes = published.readBytes()
            try {
                assertTrue(bytes.size > 32)
                bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
                published.writeBytes(bytes)
            } finally {
                bytes.fill(0)
            }

            assertIs<ProductionAndroidOfflineResumeMetadataLoadResult.Rejected>(
                store.load()
            )
        } finally {
            store.deleteForTests()
        }

        assertIs<ProductionAndroidOfflineResumeMetadataLoadResult.Missing>(store.load())
    }
}
