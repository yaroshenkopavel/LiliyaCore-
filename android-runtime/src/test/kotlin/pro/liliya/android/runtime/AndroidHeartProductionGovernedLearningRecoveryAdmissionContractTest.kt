package pro.liliya.android.runtime

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test
import pro.liliya.core.learning.LearningApplicationMutationApplicationPort
import pro.liliya.core.learning.LearningApplicationMutationApplicationResult
import pro.liliya.core.learning.LearningApplicationMutationExactCompletionRecoveryPort
import pro.liliya.core.learning.LearningApplicationMutationExactCompletionRecoveryResult
import pro.liliya.core.learning.LearningApplicationMutationRecoveryClassificationPort
import pro.liliya.core.learning.LearningApplicationMutationRecoveryClassificationResult
import pro.liliya.core.learning.LearningApplicationMutationRecoveryCompletionEligibilityDecision
import pro.liliya.core.learning.LearningApplicationMutationRecoverySummary

class AndroidHeartProductionGovernedLearningRecoveryAdmissionContractTest {

    @Test
    fun clean_recovery_evidence_allows_composition_wiring_without_recovery_execution() {
        var recoveryCalls = 0
        assertNull(
            AndroidHeartProductionGovernedLearningAssembly.recoveryAdmissionFailure(
                application(
                    before = clean(),
                    recover = {
                        recoveryCalls += 1
                        LearningApplicationMutationExactCompletionRecoveryResult.Completed(emptyList())
                    },
                    after = clean()
                )
            )
        )
        assertEquals(0, recoveryCalls)
    }

    @Test
    fun exact_recovery_completion_is_reclassified_and_allows_wiring_only_after_clean_state() {
        var recoveryCalls = 0
        val application = application(
            before = requiresRecovery(exact = 1),
            recover = {
                recoveryCalls += 1
                LearningApplicationMutationExactCompletionRecoveryResult.Completed(emptyList())
            },
            after = clean()
        )

        assertNull(
            AndroidHeartProductionGovernedLearningAssembly.recoveryAdmissionFailure(application)
        )
        assertEquals(1, recoveryCalls)
    }

    @Test
    fun completed_recovery_that_still_has_prepared_state_remains_blocked() {
        assertEquals(
            AndroidHeartProductionGovernedLearningCreateFailure.MUTATION_RECOVERY_REQUIRED,
            AndroidHeartProductionGovernedLearningAssembly.recoveryAdmissionFailure(
                application(
                    before = requiresRecovery(exact = 1),
                    recover = {
                        LearningApplicationMutationExactCompletionRecoveryResult.Completed(emptyList())
                    },
                    after = requiresRecovery(exact = 1)
                )
            )
        )
    }

    @Test
    fun no_downstream_blocked_recovery_never_allows_wiring() {
        assertEquals(
            AndroidHeartProductionGovernedLearningCreateFailure.MUTATION_RECOVERY_REQUIRED,
            AndroidHeartProductionGovernedLearningAssembly.recoveryAdmissionFailure(
                application(
                    before = requiresRecovery(noDownstream = 1),
                    recover = {
                        LearningApplicationMutationExactCompletionRecoveryResult.Blocked(
                            LearningApplicationMutationRecoveryCompletionEligibilityDecision
                                .REPLAY_REQUIRES_FRESH_AUTHORITY
                        )
                    },
                    after = clean()
                )
            )
        )
    }

    @Test
    fun stale_or_failed_exact_completion_remains_fail_closed() {
        assertEquals(
            AndroidHeartProductionGovernedLearningCreateFailure.MUTATION_RECOVERY_REQUIRED,
            AndroidHeartProductionGovernedLearningAssembly.recoveryAdmissionFailure(
                application(
                    before = requiresRecovery(exact = 1),
                    recover = {
                        LearningApplicationMutationExactCompletionRecoveryResult.StaleEvidence(0)
                    },
                    after = clean()
                )
            )
        )
        assertEquals(
            AndroidHeartProductionGovernedLearningCreateFailure.MUTATION_RECOVERY_REQUIRED,
            AndroidHeartProductionGovernedLearningAssembly.recoveryAdmissionFailure(
                application(
                    before = requiresRecovery(exact = 1),
                    recover = {
                        LearningApplicationMutationExactCompletionRecoveryResult.CompletionFailed(0)
                    },
                    after = clean()
                )
            )
        )
    }

    @Test
    fun recovery_exception_is_converted_to_fail_closed_required_state() {
        assertEquals(
            AndroidHeartProductionGovernedLearningCreateFailure.MUTATION_RECOVERY_REQUIRED,
            AndroidHeartProductionGovernedLearningAssembly.recoveryAdmissionFailure(
                application(
                    before = requiresRecovery(exact = 1),
                    recover = { error("recovery failure") },
                    after = clean()
                )
            )
        )
    }

    @Test
    fun prepared_mutation_without_exact_recovery_capability_remains_blocked() {
        assertEquals(
            AndroidHeartProductionGovernedLearningCreateFailure.MUTATION_RECOVERY_REQUIRED,
            AndroidHeartProductionGovernedLearningAssembly.recoveryAdmissionFailure(
                classificationOnly(requiresRecovery(exact = 1))
            )
        )
    }

    @Test
    fun classification_failure_fails_closed() {
        assertEquals(
            AndroidHeartProductionGovernedLearningCreateFailure
                .MUTATION_RECOVERY_CLASSIFICATION_FAILED,
            AndroidHeartProductionGovernedLearningAssembly.recoveryAdmissionFailure(
                classificationOnly(LearningApplicationMutationRecoveryClassificationResult.Failed)
            )
        )
    }

    @Test
    fun missing_classification_capability_fails_closed() {
        val application = LearningApplicationMutationApplicationPort {
            error("mutation application must not run during admission")
        }
        assertEquals(
            AndroidHeartProductionGovernedLearningCreateFailure
                .MUTATION_RECOVERY_CLASSIFICATION_FAILED,
            AndroidHeartProductionGovernedLearningAssembly.recoveryAdmissionFailure(application)
        )
    }

    @Test
    fun classifier_exception_fails_closed_before_recovery_execution() {
        var recoveryCalls = 0
        val application = object : LearningApplicationMutationApplicationPort,
            LearningApplicationMutationRecoveryClassificationPort,
            LearningApplicationMutationExactCompletionRecoveryPort {
            override fun apply(
                reference: pro.liliya.core.learning.LearningApplicationMutationReference
            ): LearningApplicationMutationApplicationResult =
                error("mutation application must not run during admission")

            override fun classifyPreparedMutations():
                LearningApplicationMutationRecoveryClassificationResult =
                error("classification failure")

            override fun recoverExactCompletion():
                LearningApplicationMutationExactCompletionRecoveryResult {
                recoveryCalls += 1
                return LearningApplicationMutationExactCompletionRecoveryResult.Completed(emptyList())
            }
        }
        assertEquals(
            AndroidHeartProductionGovernedLearningCreateFailure
                .MUTATION_RECOVERY_CLASSIFICATION_FAILED,
            AndroidHeartProductionGovernedLearningAssembly.recoveryAdmissionFailure(application)
        )
        assertEquals(0, recoveryCalls)
    }

    @Test
    fun post_recovery_classification_failure_fails_closed() {
        assertEquals(
            AndroidHeartProductionGovernedLearningCreateFailure
                .MUTATION_RECOVERY_CLASSIFICATION_FAILED,
            AndroidHeartProductionGovernedLearningAssembly.recoveryAdmissionFailure(
                application(
                    before = requiresRecovery(exact = 1),
                    recover = {
                        LearningApplicationMutationExactCompletionRecoveryResult.Completed(emptyList())
                    },
                    after = LearningApplicationMutationRecoveryClassificationResult.Failed
                )
            )
        )
    }

    private fun clean(): LearningApplicationMutationRecoveryClassificationResult =
        LearningApplicationMutationRecoveryClassificationResult.Classified(
            LearningApplicationMutationRecoverySummary.Clean
        )

    private fun requiresRecovery(
        noDownstream: Int = 0,
        exact: Int = 0,
        mismatched: Int = 0
    ): LearningApplicationMutationRecoveryClassificationResult =
        LearningApplicationMutationRecoveryClassificationResult.Classified(
            LearningApplicationMutationRecoverySummary(
                noDownstream = noDownstream,
                exactDownstreamPresent = exact,
                mismatchedDownstreamPresent = mismatched
            )
        )

    private fun classificationOnly(
        recovery: LearningApplicationMutationRecoveryClassificationResult
    ): LearningApplicationMutationApplicationPort =
        object : LearningApplicationMutationApplicationPort,
            LearningApplicationMutationRecoveryClassificationPort {
            override fun apply(
                reference: pro.liliya.core.learning.LearningApplicationMutationReference
            ): LearningApplicationMutationApplicationResult =
                error("mutation application must not run during admission")

            override fun classifyPreparedMutations():
                LearningApplicationMutationRecoveryClassificationResult = recovery
        }

    private fun application(
        before: LearningApplicationMutationRecoveryClassificationResult,
        recover: () -> LearningApplicationMutationExactCompletionRecoveryResult,
        after: LearningApplicationMutationRecoveryClassificationResult
    ): LearningApplicationMutationApplicationPort {
        var classificationCalls = 0
        return object : LearningApplicationMutationApplicationPort,
            LearningApplicationMutationRecoveryClassificationPort,
            LearningApplicationMutationExactCompletionRecoveryPort {
            override fun apply(
                reference: pro.liliya.core.learning.LearningApplicationMutationReference
            ): LearningApplicationMutationApplicationResult =
                error("mutation application must not run during admission")

            override fun classifyPreparedMutations():
                LearningApplicationMutationRecoveryClassificationResult =
                if (classificationCalls++ == 0) before else after

            override fun recoverExactCompletion():
                LearningApplicationMutationExactCompletionRecoveryResult = recover()
        }
    }
}
