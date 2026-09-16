package pro.liliya.android.runtime

enum class AndroidProductRuntimeLearningActivationJournalState {
    CLEAN,
    ACTIVATING,
    ACTIVATED,
    FAILED
}

sealed interface AndroidProductRuntimeLearningActivationJournalLoadResult {
    data class Loaded(
        val state: AndroidProductRuntimeLearningActivationJournalState
    ) : AndroidProductRuntimeLearningActivationJournalLoadResult

    data object Failed : AndroidProductRuntimeLearningActivationJournalLoadResult
}

sealed interface AndroidProductRuntimeLearningActivationJournalTransitionResult {
    data object Updated : AndroidProductRuntimeLearningActivationJournalTransitionResult

    data class Conflict(
        val actual: AndroidProductRuntimeLearningActivationJournalState
    ) : AndroidProductRuntimeLearningActivationJournalTransitionResult

    data object Failed : AndroidProductRuntimeLearningActivationJournalTransitionResult
}

interface AndroidProductRuntimeLearningActivationJournal {
    fun load(): AndroidProductRuntimeLearningActivationJournalLoadResult

    fun compareAndSet(
        expected: AndroidProductRuntimeLearningActivationJournalState,
        next: AndroidProductRuntimeLearningActivationJournalState
    ): AndroidProductRuntimeLearningActivationJournalTransitionResult
}

internal class InMemoryAndroidProductRuntimeLearningActivationJournal(
    initial: AndroidProductRuntimeLearningActivationJournalState =
        AndroidProductRuntimeLearningActivationJournalState.CLEAN
) : AndroidProductRuntimeLearningActivationJournal {
    private var state = initial

    @Synchronized
    override fun load(): AndroidProductRuntimeLearningActivationJournalLoadResult =
        AndroidProductRuntimeLearningActivationJournalLoadResult.Loaded(state)

    @Synchronized
    override fun compareAndSet(
        expected: AndroidProductRuntimeLearningActivationJournalState,
        next: AndroidProductRuntimeLearningActivationJournalState
    ): AndroidProductRuntimeLearningActivationJournalTransitionResult {
        if (state != expected) {
            return AndroidProductRuntimeLearningActivationJournalTransitionResult.Conflict(state)
        }
        state = next
        return AndroidProductRuntimeLearningActivationJournalTransitionResult.Updated
    }
}
