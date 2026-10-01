package pro.liliya.android.runtime

import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test

class AndroidProductRuntimeLearningActivationLifecycleContractTest {

    @Test
    fun clean_start_without_enablement_evidence_stays_disabled_and_does_not_activate() {
        val journal = InMemoryAndroidProductRuntimeLearningActivationJournal()
        var activations = 0
        val lifecycle = lifecycle(journal) {
            activations += 1
            "live"
        }

        assertEquals(
            AndroidProductRuntimeLearningActivationSessionResult.NotActivated,
            lifecycle.start()
        )
        assertEquals(0, activations)
        assertEquals(
            AndroidProductRuntimeLearningActivationJournalState.CLEAN,
            loadedState(journal)
        )
    }

    @Test
    fun clean_start_with_complete_evidence_activates_once() {
        val journal = InMemoryAndroidProductRuntimeLearningActivationJournal()
        var activations = 0
        val lifecycle = lifecycle(journal) {
            activations += 1
            "live"
        }

        val activated = assertIs<AndroidProductRuntimeLearningActivationSessionResult.Activated<String>>(
            lifecycle.start(completeEvidence())
        )

        assertEquals("live", activated.value)
        assertEquals(1, activations)
        assertEquals(
            AndroidProductRuntimeLearningActivationJournalState.ACTIVATED,
            loadedState(journal)
        )
    }

    @Test
    fun restarted_activated_state_restores_without_requiring_fresh_enablement_evidence() {
        val journal = InMemoryAndroidProductRuntimeLearningActivationJournal()
        val first = lifecycle(journal) { "initial" }
        assertIs<AndroidProductRuntimeLearningActivationSessionResult.Activated<String>>(
            first.start(completeEvidence())
        )

        var restorations = 0
        val restartedSession = AndroidProductRuntimeLearningActivationSession(
            journal = journal,
            restoration = {
                restorations += 1
                "restored"
            },
            activation = { error("restart must not run initial activation") }
        )
        val restarted = AndroidProductRuntimeLearningActivationLifecycle(
            session = restartedSession,
            journal = journal,
            recoverySafety = blockedSafety()
        )

        val restored = assertIs<AndroidProductRuntimeLearningActivationSessionResult.Restored<String>>(
            restarted.start()
        )
        assertEquals("restored", restored.value)
        assertEquals(1, restorations)
        assertEquals(
            AndroidProductRuntimeLearningActivationJournalState.ACTIVATED,
            loadedState(journal)
        )
    }

    @Test
    fun interrupted_start_never_rearms_or_retries_implicitly() {
        val journal = InMemoryAndroidProductRuntimeLearningActivationJournal(
            AndroidProductRuntimeLearningActivationJournalState.ACTIVATING
        )
        var activations = 0
        var safetyCalls = 0
        val session = AndroidProductRuntimeLearningActivationSession(
            journal = journal,
            activation = {
                activations += 1
                "live"
            }
        )
        val lifecycle = AndroidProductRuntimeLearningActivationLifecycle(
            session = session,
            journal = journal,
            recoverySafety = AndroidProductRuntimeLearningActivationRecoverySafetyPort {
                safetyCalls += 1
                AndroidProductRuntimeLearningActivationRecoverySafetyResult.SafeToRearm
            }
        )

        assertEquals(
            AndroidProductRuntimeLearningActivationSessionResult.RecoveryRequired,
            lifecycle.start(completeEvidence())
        )
        assertEquals(0, activations)
        assertEquals(0, safetyCalls)
        assertEquals(
            AndroidProductRuntimeLearningActivationJournalState.ACTIVATING,
            loadedState(journal)
        )
    }

    @Test
    fun recovery_is_explicit_and_rearmed_clean_state_still_requires_enablement_evidence() {
        val journal = InMemoryAndroidProductRuntimeLearningActivationJournal(
            AndroidProductRuntimeLearningActivationJournalState.FAILED
        )
        var activations = 0
        val session = AndroidProductRuntimeLearningActivationSession(
            journal = journal,
            activation = {
                activations += 1
                "live"
            }
        )
        val lifecycle = AndroidProductRuntimeLearningActivationLifecycle(
            session = session,
            journal = journal,
            recoverySafety = AndroidProductRuntimeLearningActivationRecoverySafetyPort {
                AndroidProductRuntimeLearningActivationRecoverySafetyResult.SafeToRearm
            }
        )

        assertEquals(
            AndroidProductRuntimeLearningActivationSessionResult.RecoveryRequired,
            lifecycle.start()
        )
        assertEquals(
            AndroidProductRuntimeLearningActivationRecoveryResult.Rearmed,
            lifecycle.recover()
        )
        assertEquals(
            AndroidProductRuntimeLearningActivationSessionResult.NotActivated,
            lifecycle.start()
        )
        assertEquals(0, activations)

        assertIs<AndroidProductRuntimeLearningActivationSessionResult.Activated<String>>(
            lifecycle.start(completeEvidence())
        )
        assertEquals(1, activations)
    }

    private fun lifecycle(
        journal: AndroidProductRuntimeLearningActivationJournal,
        activation: () -> String
    ): AndroidProductRuntimeLearningActivationLifecycle<String> {
        val session = AndroidProductRuntimeLearningActivationSession(
            journal = journal,
            activation = activation
        )
        return AndroidProductRuntimeLearningActivationLifecycle(
            session = session,
            journal = journal,
            recoverySafety = blockedSafety()
        )
    }

    private fun blockedSafety(): AndroidProductRuntimeLearningActivationRecoverySafetyPort =
        AndroidProductRuntimeLearningActivationRecoverySafetyPort {
            AndroidProductRuntimeLearningActivationRecoverySafetyResult.Blocked
        }

    private fun completeEvidence(): AndroidProductRuntimeLearningEnablementEvidence =
        AndroidProductRuntimeLearningEnablementEvidence(
            productPolicyApproved = true,
            poisoningResistanceAccepted = true,
            freshAuthorityPerMutationAccepted = true,
            rollbackCompensationAccepted = true,
            durableCrashSemanticsAccepted = true
        )

    private fun loadedState(
        journal: AndroidProductRuntimeLearningActivationJournal
    ): AndroidProductRuntimeLearningActivationJournalState =
        (journal.load() as AndroidProductRuntimeLearningActivationJournalLoadResult.Loaded).state
}
