package pro.liliya.android.runtime

/**
 * Product-owned transition boundary between accepted learning-enable evidence and installation of
 * mutation-capable governed-learning owners.
 *
 * This gate deliberately does not mint Authority, grant a capability, select a mutation target or
 * execute a mutation. It only guarantees that the supplied activation action is not invoked until
 * every prerequisite in [AndroidProductRuntimeLearningEnablementEvidence] has been accepted.
 *
 * The activation action remains responsible for constructing the already-governed runtime owners;
 * per-mutation Authority and durable mutation semantics stay owned by the existing governed-learning
 * composition.
 */
sealed interface AndroidProductRuntimeLearningActivationResult<out T> {
    data class Activated<T>(
        val value: T
    ) : AndroidProductRuntimeLearningActivationResult<T>

    data class EvidenceRejected(
        val reason: AndroidProductRuntimeLearningEnablementRejection
    ) : AndroidProductRuntimeLearningActivationResult<Nothing>

    data object ActivationFailed : AndroidProductRuntimeLearningActivationResult<Nothing>
}

object AndroidProductRuntimeLearningActivationGate {
    fun <T : Any> activate(
        evidence: AndroidProductRuntimeLearningEnablementEvidence,
        activation: () -> T
    ): AndroidProductRuntimeLearningActivationResult<T> =
        when (val accepted = AndroidProductRuntimeLearningEnablementAcceptanceGate.evaluate(evidence)) {
            is AndroidProductRuntimeLearningEnablementAcceptanceResult.Rejected ->
                AndroidProductRuntimeLearningActivationResult.EvidenceRejected(accepted.reason)

            AndroidProductRuntimeLearningEnablementAcceptanceResult.Ready ->
                try {
                    AndroidProductRuntimeLearningActivationResult.Activated(activation())
                } catch (_: Exception) {
                    AndroidProductRuntimeLearningActivationResult.ActivationFailed
                }
        }
}
