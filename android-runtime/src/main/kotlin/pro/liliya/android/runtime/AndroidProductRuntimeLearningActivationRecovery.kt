package pro.liliya.android.runtime

sealed interface AndroidProductRuntimeLearningActivationRecoverySafetyResult {
    data object SafeToRearm : AndroidProductRuntimeLearningActivationRecoverySafetyResult
    data object Blocked : AndroidProductRuntimeLearningActivationRecoverySafetyResult
}

fun interface AndroidProductRuntimeLearningActivationRecoverySafetyPort {
    fun evaluate(
        interruptedState: AndroidProductRuntimeLearningActivationJournalState
    ): AndroidProductRuntimeLearningActivationRecoverySafetyResult
}

sealed interface AndroidProductRuntimeLearningActivationRecoveryResult {
    data object NoRecoveryRequired : AndroidProductRuntimeLearningActivationRecoveryResult
    data object AlreadyActivated : AndroidProductRuntimeLearningActivationRecoveryResult
    data object Rearmed : AndroidProductRuntimeLearningActivationRecoveryResult
    data object Blocked : AndroidProductRuntimeLearningActivationRecoveryResult
    data object Failed : AndroidProductRuntimeLearningActivationRecoveryResult
}

/**
 * Explicit recovery boundary for an interrupted/failed activation or restoration.
 *
 * Recovery is never automatic. A trusted product-owned safety port must independently establish
 * that the interrupted owner-construction attempt is safe to re-arm. This boundary does not mint
 * Authority and cannot clear an already ACTIVATED state.
 */
object AndroidProductRuntimeLearningActivationRecovery {
    fun recover(
        journal: AndroidProductRuntimeLearningActivationJournal,
        safety: AndroidProductRuntimeLearningActivationRecoverySafetyPort
    ): AndroidProductRuntimeLearningActivationRecoveryResult {
        val state = when (val loaded = journal.load()) {
            AndroidProductRuntimeLearningActivationJournalLoadResult.Failed ->
                return AndroidProductRuntimeLearningActivationRecoveryResult.Failed
            is AndroidProductRuntimeLearningActivationJournalLoadResult.Loaded -> loaded.state
        }

        when (state) {
            AndroidProductRuntimeLearningActivationJournalState.CLEAN ->
                return AndroidProductRuntimeLearningActivationRecoveryResult.NoRecoveryRequired
            AndroidProductRuntimeLearningActivationJournalState.ACTIVATED ->
                return AndroidProductRuntimeLearningActivationRecoveryResult.AlreadyActivated
            AndroidProductRuntimeLearningActivationJournalState.ACTIVATING,
            AndroidProductRuntimeLearningActivationJournalState.RESTORING,
            AndroidProductRuntimeLearningActivationJournalState.FAILED -> Unit
        }

        val safetyResult = try {
            safety.evaluate(state)
        } catch (_: Exception) {
            return AndroidProductRuntimeLearningActivationRecoveryResult.Failed
        }
        if (safetyResult != AndroidProductRuntimeLearningActivationRecoverySafetyResult.SafeToRearm) {
            return AndroidProductRuntimeLearningActivationRecoveryResult.Blocked
        }

        return when (journal.compareAndSet(state, AndroidProductRuntimeLearningActivationJournalState.CLEAN)) {
            AndroidProductRuntimeLearningActivationJournalTransitionResult.Updated ->
                AndroidProductRuntimeLearningActivationRecoveryResult.Rearmed
            AndroidProductRuntimeLearningActivationJournalTransitionResult.Failed,
            is AndroidProductRuntimeLearningActivationJournalTransitionResult.Conflict ->
                AndroidProductRuntimeLearningActivationRecoveryResult.Failed
        }
    }
}
