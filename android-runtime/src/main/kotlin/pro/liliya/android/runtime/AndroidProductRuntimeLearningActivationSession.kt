package pro.liliya.android.runtime

/**
 * One-shot product-owned learning activation session.
 *
 * Evidence rejection is non-terminal because no activation side effect has been attempted. Once an
 * accepted activation is attempted, the session becomes terminal: success is retained and a thrown
 * activation failure cannot be retried because the caller cannot prove that the failed activation
 * left no partial mutation-capable owner installation behind.
 *
 * This object still does not mint Authority, grant capabilities or execute a learning mutation.
 */
sealed interface AndroidProductRuntimeLearningActivationSessionResult<out T> {
    data class Activated<T>(
        val value: T
    ) : AndroidProductRuntimeLearningActivationSessionResult<T>

    data class EvidenceRejected(
        val reason: AndroidProductRuntimeLearningEnablementRejection
    ) : AndroidProductRuntimeLearningActivationSessionResult<Nothing>

    data object AlreadyActivated : AndroidProductRuntimeLearningActivationSessionResult<Nothing>

    data object ActivationFailed : AndroidProductRuntimeLearningActivationSessionResult<Nothing>
}

class AndroidProductRuntimeLearningActivationSession<T : Any>(
    private val activation: () -> T
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

        return when (
            val accepted = AndroidProductRuntimeLearningEnablementAcceptanceGate.evaluate(evidence)
        ) {
            is AndroidProductRuntimeLearningEnablementAcceptanceResult.Rejected ->
                AndroidProductRuntimeLearningActivationSessionResult.EvidenceRejected(
                    accepted.reason
                )

            AndroidProductRuntimeLearningEnablementAcceptanceResult.Ready ->
                try {
                    val value = activation()
                    state = State.Activated(value)
                    AndroidProductRuntimeLearningActivationSessionResult.Activated(value)
                } catch (_: Exception) {
                    state = State.Failed
                    AndroidProductRuntimeLearningActivationSessionResult.ActivationFailed
                }
        }
    }

    override fun toString(): String =
        "AndroidProductRuntimeLearningActivationSession(state=" +
            when (state) {
                State.Initial -> "INITIAL"
                is State.Activated -> "ACTIVATED"
                State.Failed -> "FAILED"
            } +
            ",activation=<redacted>)"
}
