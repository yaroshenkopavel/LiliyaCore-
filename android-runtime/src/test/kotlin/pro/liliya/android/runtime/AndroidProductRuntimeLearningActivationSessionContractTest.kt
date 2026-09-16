package pro.liliya.android.runtime

import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test

class AndroidProductRuntimeLearningActivationSessionContractTest {
    @Test
    fun rejected_evidence_does_not_consume_session_and_corrected_evidence_can_activate_once() {
        var calls = 0
        val expected = Any()
        val session = AndroidProductRuntimeLearningActivationSession {
            calls += 1
            expected
        }

        val rejected = session.activate(
            evidence().copy(productPolicyApproved = false)
        )
        assertIs<AndroidProductRuntimeLearningActivationSessionResult.EvidenceRejected>(rejected)
        assertEquals(0, calls)

        val activated = assertIs<
            AndroidProductRuntimeLearningActivationSessionResult.Activated<Any>
        >(session.activate(evidence()))
        assertEquals(expected, activated.value)
        assertEquals(1, calls)
    }

    @Test
    fun accepted_evidence_cannot_invoke_activation_twice() {
        var calls = 0
        val session = AndroidProductRuntimeLearningActivationSession {
            calls += 1
            Any()
        }

        assertIs<AndroidProductRuntimeLearningActivationSessionResult.Activated<Any>>(
            session.activate(evidence())
        )
        assertIs<AndroidProductRuntimeLearningActivationSessionResult.AlreadyActivated>(
            session.activate(evidence())
        )
        assertEquals(1, calls)
    }

    @Test
    fun activation_failure_is_terminal_and_is_never_retried() {
        var calls = 0
        val session = AndroidProductRuntimeLearningActivationSession<Any> {
            calls += 1
            error("synthetic partial activation failure")
        }

        assertIs<AndroidProductRuntimeLearningActivationSessionResult.ActivationFailed>(
            session.activate(evidence())
        )
        assertIs<AndroidProductRuntimeLearningActivationSessionResult.ActivationFailed>(
            session.activate(evidence())
        )
        assertEquals(1, calls)
    }

    @Test
    fun concurrent_activation_has_exactly_one_activation_winner() {
        var calls = 0
        val lock = Any()
        val session = AndroidProductRuntimeLearningActivationSession {
            synchronized(lock) {
                calls += 1
            }
            Any()
        }
        val results = java.util.Collections.synchronizedList(
            mutableListOf<AndroidProductRuntimeLearningActivationSessionResult<Any>>()
        )
        val threads = List(8) {
            Thread {
                results += session.activate(evidence())
            }
        }

        threads.forEach(Thread::start)
        threads.forEach(Thread::join)

        assertEquals(1, results.count {
            it is AndroidProductRuntimeLearningActivationSessionResult.Activated
        })
        assertEquals(7, results.count {
            it is AndroidProductRuntimeLearningActivationSessionResult.AlreadyActivated
        })
        assertEquals(1, calls)
    }

    private fun evidence() = AndroidProductRuntimeLearningEnablementEvidence(
        productPolicyApproved = true,
        poisoningResistanceAccepted = true,
        freshAuthorityPerMutationAccepted = true,
        rollbackCompensationAccepted = true,
        durableCrashSemanticsAccepted = true
    )
}
