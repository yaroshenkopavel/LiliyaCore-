package pro.liliya.app

internal object ProductConversationDraftState {
    fun snapshotWithinBudget(
        draft: String,
        maxUtf8Bytes: Int
    ): String {
        require(maxUtf8Bytes > 0) { "maxUtf8Bytes must be positive" }
        return if (draft.toByteArray(Charsets.UTF_8).size <= maxUtf8Bytes) draft else ""
    }

    fun restoreWithinBudget(
        savedDraft: String?,
        maxUtf8Bytes: Int
    ): String = snapshotWithinBudget(savedDraft.orEmpty(), maxUtf8Bytes)
}
