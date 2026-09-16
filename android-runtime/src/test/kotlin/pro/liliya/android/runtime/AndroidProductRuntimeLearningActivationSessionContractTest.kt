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
    fun concurrent_process_sessions_have_exactly_one_activation_winner() {
        var calls = 0
        val lock = Any()
        val journal = InMemoryAndroidProductRuntimeLearningActivationJournal()
        val results = java.util.Collections.synchronizedList(
            mutableListOf<AndroidProductRuntimeLearningActivationSessionResult<Any>>()
        )
        val sessions = List(8) {
            AndroidProductRuntimeLearningActivationSession(
                activation = {
                    synchronized(lock) {
                        calls += 1
                    }
                    Any()
                },
                journal = journal
            )
        }
        val threads = sessions.map { session ->
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

    @Test
    fun process_restart_after_completed_activation_never_reinvokes_activation() {
        val journal = InMemoryAndroidProductRuntimeLearningActivationJournal()
        var calls = 0
        val first = AndroidProductRuntimeLearningActivationSession(
            activation = {
                calls += 1
                Any()
            },
            journal = journal
        )

        assertIs<AndroidProductRuntimeLearningActivationSessionResult.Activated<Any>>(
            first.activate(evidence())
        )

        val restarted = AndroidProductRuntimeLearningActivationSession(
            activation = {
                calls += 1
                Any()
            },
            journal = journal
        )
        assertIs<AndroidProductRuntimeLearningActivationSessionResult.AlreadyActivated>(
            restarted.activate(evidence())
        )
        assertEquals(1, calls)
    }

    @Test
    fun process_restart_during_activation_requires_explicit_recovery_and_never_retries() {
        val journal = InMemoryAndroidProductRuntimeLearningActivationJournal(
            AndroidProductRuntimeLearningActivationJournalState.ACTIVATING
        )
        var calls = 0
        val restarted = AndroidProductRuntimeLearningActivationSession(
            activation = {
                calls += 1
                Any()
            },
            journal = journal
        )

        assertIs<AndroidProductRuntimeLearningActivationSessionResult.RecoveryRequired>(
            restarted.activate(evidence())
        )
        assertEquals(0, calls)
    }

    @Test
    fun process_restart_after_failed_activation_requires_explicit_recovery_and_never_retries() {
        val journal = InMemoryAndroidProductRuntimeLearningActivationJournal(
            AndroidProductRuntimeLearningActivationJournalState.FAILED
        )
        var calls = 0
        val restarted = AndroidProductRuntimeLearningActivationSession(
            activation = {
                calls += 1
                Any()
            },
            journal = journal
        )

        assertIs<AndroidProductRuntimeLearningActivationSessionResult.RecoveryRequired>(
            restarted.activate(evidence())
        )
        assertEquals(0, calls)
    }

    private fun evidence() = AndroidProductRuntimeLearningEnablementEvidence(
        productPolicyApproved = true,
        poisoningResistanceAccepted = true,
        freshAuthorityPerMutationAccepted = true,
        rollbackCompensationAccepted = true,
        durableCrashSemanticsAccepted = true
    )
}
