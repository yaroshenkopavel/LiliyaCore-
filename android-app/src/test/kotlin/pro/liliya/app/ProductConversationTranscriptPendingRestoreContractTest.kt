package pro.liliya.app

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class ProductConversationTranscriptPendingRestoreContractTest {
    @Test
    fun ensure_last_user_adds_missing_pending_turn_once() {
        val transcript = ProductConversationTranscript()
        transcript.appendUser("older")
        transcript.appendLiliya("reply")

        assertTrue(transcript.ensureLastUser("  pending  "))
        assertFalse(transcript.ensureLastUser("pending"))
        assertEquals(
            "Вы: older\n\nЛилия: reply\n\nВы: pending",
            transcript.render()
        )
    }

    @Test
    fun ensure_last_user_ignores_blank_candidate() {
        val transcript = ProductConversationTranscript()
        transcript.appendUser("existing")

        assertFalse(transcript.ensureLastUser("   "))
        assertEquals("Вы: existing", transcript.render())
    }

    @Test
    fun ensure_last_user_does_not_hide_a_distinct_pending_request() {
        val transcript = ProductConversationTranscript()
        transcript.appendUser("first")

        assertTrue(transcript.ensureLastUser("second"))
        assertEquals("Вы: first\n\nВы: second", transcript.render())
    }
}
