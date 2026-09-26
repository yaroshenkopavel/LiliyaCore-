package pro.liliya.app

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ProductionAndroidConversationSessionStateContractTest {
    @Test
    fun current_session_survives_owner_recreation_and_fresh_candidate_is_not_committed_implicitly() {
        val storage = FakeStorage()
        val firstIds = ArrayDeque(listOf("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222"))
        val first = ProductionAndroidConversationSessionOwner(
            storage = storage,
            ids = ProductionAndroidConversationSessionIdSource {
                firstIds.removeFirst()
            }
        )

        assertEquals("11111111-1111-4111-8111-111111111111", first.currentSessionId())
        assertEquals("11111111-1111-4111-8111-111111111111", storage.value)

        val candidate = first.freshSessionId()
        assertEquals("22222222-2222-4222-8222-222222222222", candidate)
        assertEquals("11111111-1111-4111-8111-111111111111", storage.value)

        val recreated = ProductionAndroidConversationSessionOwner(
            storage = storage,
            ids = ProductionAndroidConversationSessionIdSource { "33333333-3333-4333-8333-333333333333" }
        )
        assertEquals("11111111-1111-4111-8111-111111111111", recreated.currentSessionId())

        assertTrue(recreated.commitSessionId(candidate))
        assertEquals("22222222-2222-4222-8222-222222222222", storage.value)
        assertEquals("22222222-2222-4222-8222-222222222222", recreated.currentSessionId())
    }

    @Test
    fun failed_initial_persistence_fails_closed_without_process_local_session_identity() {
        val storage = FakeStorage(allowWrites = false)
        val owner = ProductionAndroidConversationSessionOwner(
            storage = storage,
            ids = ProductionAndroidConversationSessionIdSource { "11111111-1111-4111-8111-111111111111" }
        )

        assertNull(owner.currentSessionId())
        assertNull(storage.value)
    }

    @Test
    fun corrupted_persisted_session_id_fails_closed_without_replacement() {
        val storage = FakeStorage(value = "not-a-uuid")
        val owner = ProductionAndroidConversationSessionOwner(
            storage = storage,
            ids = ProductionAndroidConversationSessionIdSource {
                "33333333-3333-4333-8333-333333333333"
            }
        )

        assertNull(owner.currentSessionId())
        assertEquals("not-a-uuid", storage.value)
    }

    @Test
    fun storage_read_or_write_exception_fails_closed_without_session_manufacturing() {
        val readFailure = ProductionAndroidConversationSessionOwner(
            storage = FakeStorage(throwOnRead = true),
            ids = ProductionAndroidConversationSessionIdSource {
                "33333333-3333-4333-8333-333333333333"
            }
        )
        assertNull(readFailure.currentSessionId())

        val writeFailureStorage = FakeStorage(throwOnWrite = true)
        val writeFailure = ProductionAndroidConversationSessionOwner(
            storage = writeFailureStorage,
            ids = ProductionAndroidConversationSessionIdSource {
                "33333333-3333-4333-8333-333333333333"
            }
        )
        assertNull(writeFailure.currentSessionId())
        assertFalse(
            writeFailure.commitSessionId(
                "44444444-4444-4444-8444-444444444444"
            )
        )
        assertNull(writeFailureStorage.value)
    }

    @Test
    fun blank_session_rotation_is_rejected_without_mutating_current_identity() {
        val storage = FakeStorage(value = "11111111-1111-4111-8111-111111111111")
        val owner = ProductionAndroidConversationSessionOwner(
            storage = storage,
            ids = ProductionAndroidConversationSessionIdSource { "33333333-3333-4333-8333-333333333333" }
        )

        assertFalse(owner.commitSessionId("   "))
        assertEquals("11111111-1111-4111-8111-111111111111", storage.value)
    }

    private class FakeStorage(
        var value: String? = null,
        private val allowWrites: Boolean = true,
        private val throwOnRead: Boolean = false,
        private val throwOnWrite: Boolean = false
    ) : ProductionAndroidConversationSessionStorage {
        override fun readSessionId(): String? {
            if (throwOnRead) error("forced read failure")
            return value
        }

        override fun writeSessionId(sessionId: String): Boolean {
            if (throwOnWrite) error("forced write failure")
            if (!allowWrites) return false
            value = sessionId
            return true
        }
    }
}
