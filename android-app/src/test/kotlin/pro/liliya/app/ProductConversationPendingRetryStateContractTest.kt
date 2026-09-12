package pro.liliya.app

import kotlin.test.assertEquals
import org.junit.Test
import pro.liliya.android.runtime.ProductChatResult

class ProductConversationPendingRetryStateContractTest {
    @Test
    fun idle_application_state_restores_saved_message_for_manual_retry() {
        assertEquals(
            "  Повтори меня  ",
            ProductConversationPendingRetryState.restoreForApplicationState(
                savedMessage = "  Повтори меня  ",
                applicationChat = ProductionAndroidAppChatTaskSnapshot.Idle,
                maxUtf8Bytes = 8 * 1024
            )
        )
    }

    @Test
    fun live_inflight_request_suppresses_retry_copy() {
        assertEquals(
            "",
            ProductConversationPendingRetryState.restoreForApplicationState(
                savedMessage = "Сообщение",
                applicationChat = ProductionAndroidAppChatTaskSnapshot.InFlight(
                    requestId = 1L,
                    message = "Сообщение"
                ),
                maxUtf8Bytes = 8 * 1024
            )
        )
    }

    @Test
    fun retained_terminal_result_suppresses_retry_copy() {
        assertEquals(
            "",
            ProductConversationPendingRetryState.restoreForApplicationState(
                savedMessage = "Сообщение",
                applicationChat = ProductionAndroidAppChatTaskSnapshot.Completed(
                    requestId = 1L,
                    message = "Сообщение",
                    outcome = ProductionAndroidAppChatTaskOutcome.Result(
                        ProductChatResult.Completed(
                            reply = "Ответ",
                            streamedChunkCount = 0,
                            streamedCharacterCount = 0
                        )
                    )
                ),
                maxUtf8Bytes = 8 * 1024
            )
        )
    }

    @Test
    fun oversized_saved_message_fails_closed_to_empty() {
        assertEquals(
            "",
            ProductConversationPendingRetryState.restoreForApplicationState(
                savedMessage = "я".repeat(5000),
                applicationChat = ProductionAndroidAppChatTaskSnapshot.Idle,
                maxUtf8Bytes = 8 * 1024
            )
        )
    }
}
