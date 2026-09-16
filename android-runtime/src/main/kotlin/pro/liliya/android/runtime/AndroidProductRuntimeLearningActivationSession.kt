package pro.liliya.android.runtime

/**
 * One-shot product-owned learning activation session.
 *
 * Evidence rejection is non-terminal because no activation side effect has been attempted. Before
 * invoking activation, the session atomically claims CLEAN -> ACTIVATING in its journal. A process
 * restart that observes ACTIVATING or FAILED stays fail-closed and never retries an ambiguous
 * activation. Successful activation is exposed only after ACTIVATING -> ACTIVATED is durably
 * acknowledged by the supplied journal.
 *
 * This object does not mint Authority, grant capabilities or execute a learning mutation.
 */
sealed interface AndroidProductRuntimeLearningActivationSessionResult<out T> {
    data class Activated<T>(
        val value: T
    ) : AndroidProductRuntimeLearningActivationSessionResult<T>

    data class EvidenceRejected(
        val reason: AndroidProductRuntimeLearningEnablementRejection
    ) : AndroidProductRuntimeLearningActivationSessionResult<Nothing>

    data object AlreadyActivated : AndroidProductRuntimeLearningActivationSessionResult<Nothing>

    data object RecoveryRequired : AndroidProductRuntimeLearningActivationSessionResult<Nothing>

    data object ActivationFailed : AndroidProductRuntimeLearningActivationSessionResult<Nothing>
}

class AndroidProductRuntimeLearningActivationSession<T : Any>(
    private val activation: () -> T,
    private val journal: AndroidProductRuntimeLearningActivationJournal =
        InMemoryAndroidProductRuntimeLearningActivationJournal()
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
                return when (claimed.actual) {
                    AndroidProductRuntimeLearningActivationJournalState.ACTIVATED ->
                        AndroidProductRuntimeLearningActivationSessionResult.AlreadyActivated
                    AndroidProductRuntimeLearningActivationJournalState.ACTIVATING,
                    AndroidProductRuntimeLearningActivationJournalState.FAILED ->
                        AndroidProductRuntimeLearningActivationSessionResult.RecoveryRequired
                    AndroidProductRuntimeLearningActivationJournalState.CLEAN -> failLocal()
                }
        }

        val value = try {
            activation()
        } catch (_: Exception) {
            journal.compareAndSet(
                expected = AndroidProductRuntimeLearningActivationJournalState.ACTIVATING,
                next = AndroidProductRuntimeLearningActivationJournalState.FAILED
            )
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
            ",activation=<redacted>,journal=<redacted>)"
}
