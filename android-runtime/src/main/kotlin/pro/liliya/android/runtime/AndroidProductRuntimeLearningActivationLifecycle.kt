package pro.liliya.android.runtime

/**
 * Product-owned startup/restart owner for governed-learning activation.
 *
 * Startup never enables learning implicitly. An already durable ACTIVATED state is restored,
 * CLEAN remains disabled unless fresh accepted enablement evidence is supplied, and ambiguous
 * activation/restoration states remain recovery-gated. Recovery is an explicit separate action.
 *
 * Enablement evidence is not Authority. This lifecycle never mints capabilities and does not
 * replace the existing per-mutation authorization path.
 */
class AndroidProductRuntimeLearningActivationLifecycle<T : Any> internal constructor(
    private val session: AndroidProductRuntimeLearningActivationSession<T>,
    private val journal: AndroidProductRuntimeLearningActivationJournal,
    private val recoverySafety: AndroidProductRuntimeLearningActivationRecoverySafetyPort
) {
    @Synchronized
    fun start(
        evidence: AndroidProductRuntimeLearningEnablementEvidence? = null
    ): AndroidProductRuntimeLearningActivationSessionResult<T> {
        return when (val restored = session.restore()) {
            is AndroidProductRuntimeLearningActivationSessionResult.Restored,
            AndroidProductRuntimeLearningActivationSessionResult.AlreadyActivated,
            AndroidProductRuntimeLearningActivationSessionResult.RecoveryRequired,
            AndroidProductRuntimeLearningActivationSessionResult.ActivationFailed -> restored

            AndroidProductRuntimeLearningActivationSessionResult.NotActivated ->
                if (evidence == null) {
                    AndroidProductRuntimeLearningActivationSessionResult.NotActivated
                } else {
                    session.activate(evidence)
                }

            is AndroidProductRuntimeLearningActivationSessionResult.EvidenceRejected,
            is AndroidProductRuntimeLearningActivationSessionResult.Activated ->
                AndroidProductRuntimeLearningActivationSessionResult.ActivationFailed
        }
    }

    @Synchronized
    fun recover(): AndroidProductRuntimeLearningActivationRecoveryResult =
        AndroidProductRuntimeLearningActivationRecovery.recover(
            journal = journal,
            safety = recoverySafety
        )

    override fun toString(): String =
        "AndroidProductRuntimeLearningActivationLifecycle(" +
            "session=<redacted>,journal=<redacted>,recoverySafety=<redacted>)"
}
