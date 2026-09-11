package pro.liliya.app

import kotlin.test.assertEquals
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
}
