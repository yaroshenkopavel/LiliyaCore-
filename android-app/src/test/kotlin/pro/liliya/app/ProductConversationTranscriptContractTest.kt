package pro.liliya.app

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class ProductConversationTranscriptContractTest {
    @Test
    fun completed_turns_preserve_order_and_both_speakers() {
        val transcript = ProductConversationTranscript()

        transcript.appendUser("Привет")
        transcript.appendLiliya("Здравствуйте")
        transcript.appendUser("Как дела?")
        transcript.appendLiliya("Готова помочь")

        assertEquals(
            "Вы: Привет\n\n" +
                "Лилия: Здравствуйте\n\n" +
                "Вы: Как дела?\n\n" +
                "Лилия: Готова помочь",
            transcript.render()
        )
    }

    @Test
    fun blank_entries_are_ignored_and_visible_text_is_trimmed() {
        val transcript = ProductConversationTranscript()

        transcript.appendUser("   ")
        transcript.appendLiliya("\n\t")
        assertTrue(transcript.isEmpty())

        transcript.appendUser("  вопрос  ")
        transcript.appendLiliya("  ответ  ")

        assertEquals("Вы: вопрос\n\nЛилия: ответ", transcript.render())
    }

    @Test
    fun user_message_remains_visible_before_reply_arrives() {
        val transcript = ProductConversationTranscript()

        transcript.appendUser("Первый запрос")

        assertEquals("Вы: Первый запрос", transcript.render())
    }

    @Test
    fun matching_newest_user_turn_can_be_rolled_back_for_retry() {
        val transcript = ProductConversationTranscript()
        transcript.appendUser("Первый")
        transcript.appendLiliya("Ответ")
        transcript.appendUser("  Повторить  ")

        assertTrue(transcript.rollbackLastUser("Повторить"))
        assertEquals("Вы: Первый\n\nЛилия: Ответ", transcript.render())
    }

    @Test
    fun rollback_fails_closed_when_newest_turn_or_message_does_not_match() {
        val transcript = ProductConversationTranscript()
        transcript.appendUser("Первый")
        transcript.appendLiliya("Ответ")

        assertFalse(transcript.rollbackLastUser("Первый"))
        assertFalse(transcript.rollbackLastUser(""))
        assertEquals("Вы: Первый\n\nЛилия: Ответ", transcript.render())

        transcript.appendUser("Второй")
        assertFalse(transcript.rollbackLastUser("другой текст"))
        assertEquals("Вы: Первый\n\nЛилия: Ответ\n\nВы: Второй", transcript.render())
    }

    @Test
    fun snapshot_round_trip_preserves_structured_turns_and_internal_newlines() {
        val transcript = ProductConversationTranscript()
        transcript.appendUser("Первая строка\nВторая строка")
        transcript.appendLiliya("Ответ\nс переносом")
        transcript.appendUser("Повтор")
        transcript.appendUser("Повтор")

        val restored = ProductConversationTranscript.restore(transcript.snapshot())

        assertEquals(transcript.render(), restored.render())
        assertEquals(transcript.snapshot(), restored.snapshot())
    }

    @Test
    fun malformed_snapshot_fails_closed_to_empty_transcript_without_partial_restore() {
        val mismatched = ProductConversationTranscript.restore(
            ProductConversationTranscriptSnapshot(
                speakers = listOf("USER", "LILIYA"),
                messages = listOf("только одно сообщение")
            )
        )
        val unknownSpeaker = ProductConversationTranscript.restore(
            ProductConversationTranscriptSnapshot(
                speakers = listOf("USER", "UNKNOWN", "LILIYA"),
                messages = listOf("первое", "невалидное", "третье")
            )
        )

        assertTrue(mismatched.isEmpty())
        assertTrue(unknownSpeaker.isEmpty())
    }

    @Test
    fun bounded_snapshot_keeps_newest_contiguous_tail_by_entry_count() {
        val transcript = ProductConversationTranscript()
        transcript.appendUser("один")
        transcript.appendLiliya("два")
        transcript.appendUser("три")
        transcript.appendLiliya("четыре")

        val snapshot = transcript.snapshotWithinBudget(
            maxEntries = 2,
            maxUtf8Bytes = 1024
        )

        assertEquals(listOf("USER", "LILIYA"), snapshot.speakers)
        assertEquals(listOf("три", "четыре"), snapshot.messages)
    }

    @Test
    fun bounded_snapshot_uses_utf8_budget_and_never_splits_a_message() {
        val transcript = ProductConversationTranscript()
        transcript.appendUser("старое")
        transcript.appendLiliya("новое")

        val newestEntryBytes =
            "LILIYA".toByteArray(Charsets.UTF_8).size +
                "новое".toByteArray(Charsets.UTF_8).size
        val snapshot = transcript.snapshotWithinBudget(
            maxEntries = 8,
            maxUtf8Bytes = newestEntryBytes
        )

        assertEquals(listOf("LILIYA"), snapshot.speakers)
        assertEquals(listOf("новое"), snapshot.messages)
    }

    @Test
    fun oversized_newest_message_yields_empty_snapshot_instead_of_partial_text() {
        val transcript = ProductConversationTranscript()
        transcript.appendUser("очень длинное сообщение")

        val snapshot = transcript.snapshotWithinBudget(
            maxEntries = 8,
            maxUtf8Bytes = 4
        )

        assertTrue(snapshot.speakers.isEmpty())
        assertTrue(snapshot.messages.isEmpty())
        assertEquals("Вы: очень длинное сообщение", transcript.render())
    }

    @Test
    fun bounded_snapshot_rejects_non_positive_budgets() {
        val transcript = ProductConversationTranscript()

        assertFailsWith<IllegalArgumentException> {
            transcript.snapshotWithinBudget(maxEntries = 0, maxUtf8Bytes = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            transcript.snapshotWithinBudget(maxEntries = 1, maxUtf8Bytes = 0)
        }
    }
}
