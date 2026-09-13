package pro.liliya.android.runtime

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test
import pro.liliya.core.learning.LearningApplicationMutationApplicationPort
import pro.liliya.core.learning.LearningApplicationMutationApplicationResult
import pro.liliya.core.learning.LearningApplicationMutationRecoveryClassificationPort
import pro.liliya.core.learning.LearningApplicationMutationRecoveryClassificationResult
import pro.liliya.core.learning.LearningApplicationMutationRecoverySummary

class AndroidHeartProductionGovernedLearningRecoveryAdmissionContractTest {

    @Test
    fun clean_recovery_evidence_allows_composition_wiring() {
        assertNull(
            AndroidHeartProductionGovernedLearningAssembly.recoveryAdmissionFailure(
                application(
                    LearningApplicationMutationRecoveryClassificationResult.Classified(
                        LearningApplicationMutationRecoverySummary.Clean
                    )
                )
            )
        )
    }

    @Test
    fun any_prepared_mutation_requires_explicit_recovery_before_wiring() {
        assertEquals(
            AndroidHeartProductionGovernedLearningCreateFailure.MUTATION_RECOVERY_REQUIRED,
            AndroidHeartProductionGovernedLearningAssembly.recoveryAdmissionFailure(
                application(
                    LearningApplicationMutationRecoveryClassificationResult.Classified(
                        LearningApplicationMutationRecoverySummary(
                            noDownstream = 0,
                            exactDownstreamPresent = 1,
                            mismatchedDownstreamPresent = 0
                        )
                    )
                )
            )
        )
    }

    @Test
    fun classification_failure_fails_closed() {
        assertEquals(
            AndroidHeartProductionGovernedLearningCreateFailure
                .MUTATION_RECOVERY_CLASSIFICATION_FAILED,
            AndroidHeartProductionGovernedLearningAssembly.recoveryAdmissionFailure(
                application(LearningApplicationMutationRecoveryClassificationResult.Failed)
            )
        )
    }

    @Test
    fun missing_recovery_capability_fails_closed() {
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
    fun classifier_exception_fails_closed() {
        val application = object : LearningApplicationMutationApplicationPort,
            LearningApplicationMutationRecoveryClassificationPort {
            override fun apply(
                reference: pro.liliya.core.learning.LearningApplicationMutationReference
            ): LearningApplicationMutationApplicationResult =
                error("mutation application must not run during admission")

            override fun classifyPreparedMutations():
                LearningApplicationMutationRecoveryClassificationResult =
                error("classification failure")
        }
        assertEquals(
            AndroidHeartProductionGovernedLearningCreateFailure
                .MUTATION_RECOVERY_CLASSIFICATION_FAILED,
            AndroidHeartProductionGovernedLearningAssembly.recoveryAdmissionFailure(application)
        )
    }

    private fun application(
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
}
