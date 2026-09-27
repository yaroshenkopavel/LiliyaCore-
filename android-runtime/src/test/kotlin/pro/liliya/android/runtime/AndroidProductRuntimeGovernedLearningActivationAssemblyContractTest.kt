package pro.liliya.android.runtime

import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test
import pro.liliya.core.cognitive.CognitiveGovernedLearningFailure
import pro.liliya.core.cognitive.CognitiveGovernedLearningResult
import pro.liliya.core.learning.LearningApplicationMutationApplicationPort
import pro.liliya.core.learning.LearningApplicationMutationAuthorizationGate

class AndroidProductRuntimeGovernedLearningActivationAssemblyContractTest {

    @Test
    fun rejected_evidence_cannot_create_mutation_capable_composition() {
        var createCalls = 0
        val session = AndroidProductRuntimeGovernedLearningActivationAssembly.createSession {
            createCalls += 1
            composition()
        }

        val result = session.activate(
            completeEvidence().copy(poisoningResistanceAccepted = false)
        )

        val rejected = assertIs<
            AndroidProductRuntimeLearningActivationSessionResult.EvidenceRejected
        >(result)
        assertEquals(
            AndroidProductRuntimeLearningEnablementRejection.POISONING_RESISTANCE_NOT_ACCEPTED,
            rejected.reason
        )
        assertEquals(0, createCalls)
    }

    @Test
    fun accepted_evidence_creates_governed_composition_once_only() {
        var createCalls = 0
        val session = AndroidProductRuntimeGovernedLearningActivationAssembly.createSession {
            createCalls += 1
            composition()
        }

        assertIs<AndroidProductRuntimeLearningActivationSessionResult.Activated<*>>(
            session.activate(completeEvidence())
        )
        assertEquals(1, createCalls)

        assertEquals(
            AndroidProductRuntimeLearningActivationSessionResult.AlreadyActivated,
            session.activate(completeEvidence())
        )
        assertEquals(1, createCalls)
    }

    @Test
    fun creation_failure_is_terminal_and_never_retries_unknown_partial_installation() {
        var createCalls = 0
        val session = AndroidProductRuntimeGovernedLearningActivationAssembly.createSession {
            createCalls += 1
            error("synthetic governed learning creation failure")
        }

        assertEquals(
            AndroidProductRuntimeLearningActivationSessionResult.ActivationFailed,
            session.activate(completeEvidence())
        )
        assertEquals(
            AndroidProductRuntimeLearningActivationSessionResult.ActivationFailed,
            session.activate(completeEvidence())
        )
        assertEquals(1, createCalls)
    }

    @Test
    fun merely_creating_activation_session_does_not_change_first_working_disabled_policy() {
        var createCalls = 0
        AndroidProductRuntimeGovernedLearningActivationAssembly.createSession {
            createCalls += 1
            composition()
        }

        assertIs<pro.liliya.core.cognitive.CognitiveLearningGovernanceResult.Rejected>(
            AndroidProductRuntimeFirstWorkingLearningDisabled.governanceDecision()
        )
        assertEquals(0, createCalls)
    }

    private fun completeEvidence() = AndroidProductRuntimeLearningEnablementEvidence(
        productPolicyApproved = true,
        poisoningResistanceAccepted = true,
        freshAuthorityPerMutationAccepted = true,
        rollbackCompensationAccepted = true,
        durableCrashSemanticsAccepted = true
    )

    private fun composition(): AndroidHeartProductionGovernedLearningComposition {
        val bridge = object : AndroidHeartProductionGovernedLearningBridge {
            override fun state(): HeartRuntimeState = HeartRuntimeState.READY

            override fun mutationApplicationPort(
                authorizationGate: LearningApplicationMutationAuthorizationGate
            ): LearningApplicationMutationApplicationPort? = null

            override fun governedLearning(
                composition: pro.liliya.core.cognitive.CognitiveGovernedLearningComposition
            ): AndroidHeartGovernedLearningComposition? = null
        }
        val governed = AndroidHeartGovernedLearningComposition(
            governed = AndroidHeartGovernedLearningPort {
                CognitiveGovernedLearningResult.Rejected(
                    CognitiveGovernedLearningFailure.CANDIDATE_MISSING_OR_MISMATCH
                )
            },
            semantic = AndroidHeartAppliedSemanticSyncPort {
                error("semantic synchronization is not part of activation boundary test")
            },
            onSemanticUnavailable = {}
        )
        return AndroidHeartProductionGovernedLearningComposition(
            bridge = bridge,
            governed = governed
        )
    }
}
