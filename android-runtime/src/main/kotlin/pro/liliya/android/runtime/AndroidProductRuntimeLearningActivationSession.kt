package pro.liliya.android.runtime

/**
 * Product-owned governed-learning activation session with explicit crash/restart checkpoints.
 *
 * Initial activation claims CLEAN -> ACTIVATING before owner construction and publishes success only
 * after ACTIVATING -> ACTIVATED. Restart restoration separately claims ACTIVATED -> RESTORING before
 * rebuilding process-local owners. A restart observing ACTIVATING, RESTORING or FAILED never retries
 * implicitly and reports RecoveryRequired.
 *
 * This object does not mint Authority, grant capabilities or execute a learning mutation.
 */
sealed interface AndroidProductRuntimeLearningActivationSessionResult<out T> {
    data class Activated<T>(
        val value: T
    ) : AndroidProductRuntimeLearningActivationSessionResult<T>

    data class Restored<T>(
        val value: T
    ) : AndroidProductRuntimeLearningActivationSessionResult<T>

    data class EvidenceRejected(
        val reason: AndroidProductRuntimeLearningEnablementRejection
    ) : AndroidProductRuntimeLearningActivationSessionResult<Nothing>

    data object AlreadyActivated : AndroidProductRuntimeLearningActivationSessionResult<Nothing>

    data object NotActivated : AndroidProductRuntimeLearningActivationSessionResult<Nothing>

    data object RecoveryRequired : AndroidProductRuntimeLearningActivationSessionResult<Nothing>

    data object ActivationFailed : AndroidProductRuntimeLearningActivationSessionResult<Nothing>
}

class AndroidProductRuntimeLearningActivationSession<T : Any>(
    private val activation: () -> T,
    private val journal: AndroidProductRuntimeLearningActivationJournal =
        InMemoryAndroidProductRuntimeLearningActivationJournal(),
    private val restoration: () -> T = activation
) {
    private sealed interface State<out T> {
        data object Initial : State<Nothing>
        data class Activated<T>(val value: T) : State<T>
        data object Failed : State<Nothing>
    }

    private var state: State<T> = State.Initial

    @Synchronized
    fun activate(
        evidence: AndroidProductRuntimeLearningEnablementEvidence
    ): AndroidProductRuntimeLearningActivationSessionResult<T> {
        when (state) {
            is State.Activated ->
                return AndroidProductRuntimeLearningActivationSessionResult.AlreadyActivated
            State.Failed ->
                return AndroidProductRuntimeLearningActivationSessionResult.ActivationFailed
            State.Initial -> Unit
        }

        when (
            val accepted = AndroidProductRuntimeLearningEnablementAcceptanceGate.evaluate(evidence)
        ) {
            is AndroidProductRuntimeLearningEnablementAcceptanceResult.Rejected ->
                return AndroidProductRuntimeLearningActivationSessionResult.EvidenceRejected(
                    accepted.reason
                )
            AndroidProductRuntimeLearningEnablementAcceptanceResult.Ready -> Unit
        }

        when (val loaded = journal.load()) {
            AndroidProductRuntimeLearningActivationJournalLoadResult.Failed ->
                return failLocal()
            is AndroidProductRuntimeLearningActivationJournalLoadResult.Loaded ->
                when (loaded.state) {
                    AndroidProductRuntimeLearningActivationJournalState.ACTIVATED ->
                        return AndroidProductRuntimeLearningActivationSessionResult.AlreadyActivated
                    AndroidProductRuntimeLearningActivationJournalState.ACTIVATING,
                    AndroidProductRuntimeLearningActivationJournalState.RESTORING,
                    AndroidProductRuntimeLearningActivationJournalState.FAILED ->
                        return AndroidProductRuntimeLearningActivationSessionResult.RecoveryRequired
                    AndroidProductRuntimeLearningActivationJournalState.CLEAN -> Unit
                }
        }

        when (
            val claimed = journal.compareAndSet(
                expected = AndroidProductRuntimeLearningActivationJournalState.CLEAN,
                next = AndroidProductRuntimeLearningActivationJournalState.ACTIVATING
            )
        ) {
            AndroidProductRuntimeLearningActivationJournalTransitionResult.Updated -> Unit
            AndroidProductRuntimeLearningActivationJournalTransitionResult.Failed ->
                return failLocal()
            is AndroidProductRuntimeLearningActivationJournalTransitionResult.Conflict ->
                return resultForConflict(claimed.actual)
        }

        val value = try {
            activation()
        } catch (_: Exception) {
            markFailed(AndroidProductRuntimeLearningActivationJournalState.ACTIVATING)
            return failLocal()
        }

        return when (
            journal.compareAndSet(
                expected = AndroidProductRuntimeLearningActivationJournalState.ACTIVATING,
                next = AndroidProductRuntimeLearningActivationJournalState.ACTIVATED
            )
        ) {
            AndroidProductRuntimeLearningActivationJournalTransitionResult.Updated -> {
                state = State.Activated(value)
                AndroidProductRuntimeLearningActivationSessionResult.Activated(value)
            }
            AndroidProductRuntimeLearningActivationJournalTransitionResult.Failed,
            is AndroidProductRuntimeLearningActivationJournalTransitionResult.Conflict -> failLocal()
        }
    }

    @Synchronized
    fun restore(): AndroidProductRuntimeLearningActivationSessionResult<T> {
        when (state) {
            is State.Activated ->
                return AndroidProductRuntimeLearningActivationSessionResult.AlreadyActivated
            State.Failed ->
                return AndroidProductRuntimeLearningActivationSessionResult.ActivationFailed
            State.Initial -> Unit
        }

        val loaded = when (val result = journal.load()) {
            AndroidProductRuntimeLearningActivationJournalLoadResult.Failed -> return failLocal()
            is AndroidProductRuntimeLearningActivationJournalLoadResult.Loaded -> result.state
        }
        when (loaded) {
            AndroidProductRuntimeLearningActivationJournalState.CLEAN ->
                return AndroidProductRuntimeLearningActivationSessionResult.NotActivated
            AndroidProductRuntimeLearningActivationJournalState.ACTIVATING,
            AndroidProductRuntimeLearningActivationJournalState.RESTORING,
            AndroidProductRuntimeLearningActivationJournalState.FAILED ->
                return AndroidProductRuntimeLearningActivationSessionResult.RecoveryRequired
            AndroidProductRuntimeLearningActivationJournalState.ACTIVATED -> Unit
        }

        when (
            val claimed = journal.compareAndSet(
                expected = AndroidProductRuntimeLearningActivationJournalState.ACTIVATED,
                next = AndroidProductRuntimeLearningActivationJournalState.RESTORING
            )
        ) {
            AndroidProductRuntimeLearningActivationJournalTransitionResult.Updated -> Unit
            AndroidProductRuntimeLearningActivationJournalTransitionResult.Failed ->
                return failLocal()
            is AndroidProductRuntimeLearningActivationJournalTransitionResult.Conflict ->
                return resultForConflict(claimed.actual)
        }

        val value = try {
            restoration()
        } catch (_: Exception) {
            markFailed(AndroidProductRuntimeLearningActivationJournalState.RESTORING)
            return failLocal()
        }

        return when (
            journal.compareAndSet(
                expected = AndroidProductRuntimeLearningActivationJournalState.RESTORING,
                next = AndroidProductRuntimeLearningActivationJournalState.ACTIVATED
            )
        ) {
            AndroidProductRuntimeLearningActivationJournalTransitionResult.Updated -> {
                state = State.Activated(value)
                AndroidProductRuntimeLearningActivationSessionResult.Restored(value)
            }
            AndroidProductRuntimeLearningActivationJournalTransitionResult.Failed,
            is AndroidProductRuntimeLearningActivationJournalTransitionResult.Conflict -> failLocal()
        }
    }

    private fun resultForConflict(
        actual: AndroidProductRuntimeLearningActivationJournalState
    ): AndroidProductRuntimeLearningActivationSessionResult<T> =
        when (actual) {
            AndroidProductRuntimeLearningActivationJournalState.ACTIVATED ->
                AndroidProductRuntimeLearningActivationSessionResult.AlreadyActivated
            AndroidProductRuntimeLearningActivationJournalState.ACTIVATING,
            AndroidProductRuntimeLearningActivationJournalState.RESTORING,
            AndroidProductRuntimeLearningActivationJournalState.FAILED ->
                AndroidProductRuntimeLearningActivationSessionResult.RecoveryRequired
            AndroidProductRuntimeLearningActivationJournalState.CLEAN -> failLocal()
        }

    private fun markFailed(expected: AndroidProductRuntimeLearningActivationJournalState) {
        journal.compareAndSet(
            expected = expected,
            next = AndroidProductRuntimeLearningActivationJournalState.FAILED
        )
    }

    private fun failLocal(): AndroidProductRuntimeLearningActivationSessionResult.ActivationFailed {
        state = State.Failed
        return AndroidProductRuntimeLearningActivationSessionResult.ActivationFailed
    }

    override fun toString(): String =
        "AndroidProductRuntimeLearningActivationSession(state=" +
            when (state) {
                State.Initial -> "INITIAL"
                is State.Activated -> "ACTIVATED"
                State.Failed -> "FAILED"
            } +
            ",activation=<redacted>,restoration=<redacted>,journal=<redacted>)"
}
