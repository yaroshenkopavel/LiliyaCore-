package pro.liliya.app

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class ProductConversationDraftStateContractTest {
    @Test
    fun draft_round_trip_preserves_exact_text_with_whitespace_and_newlines() {
        val draft = "  первая строка\nвторая строка  "

        val saved = ProductConversationDraftState.snapshotWithinBudget(
            draft = draft,
            maxUtf8Bytes = 1024
        )
        val restored = ProductConversationDraftState.restoreWithinBudget(
            savedDraft = saved,
            maxUtf8Bytes = 1024
        )

        assertEquals(draft, restored)
    }

    @Test
    fun utf8_budget_is_measured_in_bytes_and_oversized_draft_fails_closed() {
        val draft = "Привет"
        val exactBytes = draft.toByteArray(Charsets.UTF_8).size

        assertEquals(
            draft,
            ProductConversationDraftState.snapshotWithinBudget(draft, exactBytes)
        )
        assertEquals(
            "",
            ProductConversationDraftState.snapshotWithinBudget(draft, exactBytes - 1)
        )
    }

    @Test
    fun oversized_restored_state_fails_closed_instead_of_reappearing() {
        assertEquals(
            "",
            ProductConversationDraftState.restoreWithinBudget(
                savedDraft = "слишком длинный черновик",
                maxUtf8Bytes = 4
            )
        )
        assertEquals(
            "",
            ProductConversationDraftState.restoreWithinBudget(
                savedDraft = null,
                maxUtf8Bytes = 4
            )
        )
    }

    @Test
    fun non_positive_budget_is_rejected() {
        assertFailsWith<IllegalArgumentException> {
            ProductConversationDraftState.snapshotWithinBudget("draft", 0)
        }
        assertFailsWith<IllegalArgumentException> {
            ProductConversationDraftState.restoreWithinBudget("draft", -1)
        }
    }
}
