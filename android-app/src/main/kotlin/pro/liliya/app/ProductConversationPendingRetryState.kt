package pro.liliya.app

internal object ProductConversationPendingRetryState {
    fun snapshotWithinBudget(
        message: String,
        maxUtf8Bytes: Int
    ): String = ProductConversationDraftState.snapshotWithinBudget(
        draft = message,
        maxUtf8Bytes = maxUtf8Bytes
    )

    fun restoreForApplicationState(
        savedMessage: String?,
        applicationChat: ProductionAndroidAppChatTaskSnapshot,
        maxUtf8Bytes: Int
    ): String {
        if (applicationChat !is ProductionAndroidAppChatTaskSnapshot.Idle) return ""
        return ProductConversationDraftState.restoreWithinBudget(
            savedDraft = savedMessage,
            maxUtf8Bytes = maxUtf8Bytes
        )
    }
}
