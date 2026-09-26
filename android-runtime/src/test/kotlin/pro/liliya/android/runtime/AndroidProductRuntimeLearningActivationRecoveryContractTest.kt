package pro.liliya.android.runtime

import kotlin.test.assertEquals
import org.junit.Test

class AndroidProductRuntimeLearningActivationRecoveryContractTest {
    @Test
    fun clean_and_activated_states_never_call_recovery_safety() {
        var calls = 0
        val safety = AndroidProductRuntimeLearningActivationRecoverySafetyPort {
            calls += 1
            AndroidProductRuntimeLearningActivationRecoverySafetyResult.SafeToRearm
        }

        assertEquals(
            AndroidProductRuntimeLearningActivationRecoveryResult.NoRecoveryRequired,
            AndroidProductRuntimeLearningActivationRecovery.recover(
                InMemoryAndroidProductRuntimeLearningActivationJournal(),
                safety
            )
        )
        assertEquals(
            AndroidProductRuntimeLearningActivationRecoveryResult.AlreadyActivated,
            AndroidProductRuntimeLearningActivationRecovery.recover(
                InMemoryAndroidProductRuntimeLearningActivationJournal(
                    AndroidProductRuntimeLearningActivationJournalState.ACTIVATED
                ),
                safety
            )
        )
        assertEquals(0, calls)
    }

    @Test
    fun interrupted_activation_rearms_only_after_explicit_safe_result() {
        val journal = InMemoryAndroidProductRuntimeLearningActivationJournal(
            AndroidProductRuntimeLearningActivationJournalState.ACTIVATING
        )
        assertEquals(
            AndroidProductRuntimeLearningActivationRecoveryResult.Rearmed,
            AndroidProductRuntimeLearningActivationRecovery.recover(
                journal,
                AndroidProductRuntimeLearningActivationRecoverySafetyPort {
                    assertEquals(
                        AndroidProductRuntimeLearningActivationJournalState.ACTIVATING,
                        it
                    )
                    AndroidProductRuntimeLearningActivationRecoverySafetyResult.SafeToRearm
                }
            )
        )
        assertEquals(
            AndroidProductRuntimeLearningActivationJournalLoadResult.Loaded(
                AndroidProductRuntimeLearningActivationJournalState.CLEAN
            ),
            journal.load()
        )
    }

    @Test
    fun interrupted_restoration_blocked_by_safety_remains_unchanged() {
        val journal = InMemoryAndroidProductRuntimeLearningActivationJournal(
            AndroidProductRuntimeLearningActivationJournalState.RESTORING
        )
        assertEquals(
            AndroidProductRuntimeLearningActivationRecoveryResult.Blocked,
            AndroidProductRuntimeLearningActivationRecovery.recover(
                journal,
                AndroidProductRuntimeLearningActivationRecoverySafetyPort {
                    AndroidProductRuntimeLearningActivationRecoverySafetyResult.Blocked
                }
            )
        )
        assertEquals(
            AndroidProductRuntimeLearningActivationJournalLoadResult.Loaded(
                AndroidProductRuntimeLearningActivationJournalState.RESTORING
            ),
            journal.load()
        )
    }

    @Test
    fun failed_activation_can_rearm_only_through_same_explicit_gate() {
        val journal = InMemoryAndroidProductRuntimeLearningActivationJournal(
            AndroidProductRuntimeLearningActivationJournalState.FAILED
        )
        assertEquals(
            AndroidProductRuntimeLearningActivationRecoveryResult.Rearmed,
            AndroidProductRuntimeLearningActivationRecovery.recover(
                journal,
                AndroidProductRuntimeLearningActivationRecoverySafetyPort {
                    AndroidProductRuntimeLearningActivationRecoverySafetyResult.SafeToRearm
                }
            )
        )
        assertEquals(
            AndroidProductRuntimeLearningActivationJournalLoadResult.Loaded(
                AndroidProductRuntimeLearningActivationJournalState.CLEAN
            ),
            journal.load()
        )
    }

    @Test
    fun recovery_safety_exception_fails_closed_without_rearming() {
        val journal = InMemoryAndroidProductRuntimeLearningActivationJournal(
            AndroidProductRuntimeLearningActivationJournalState.ACTIVATING
        )
        assertEquals(
            AndroidProductRuntimeLearningActivationRecoveryResult.Failed,
            AndroidProductRuntimeLearningActivationRecovery.recover(
                journal,
                AndroidProductRuntimeLearningActivationRecoverySafetyPort {
                    error("synthetic recovery verification failure")
                }
            )
        )
        assertEquals(
            AndroidProductRuntimeLearningActivationJournalLoadResult.Loaded(
                AndroidProductRuntimeLearningActivationJournalState.ACTIVATING
            ),
            journal.load()
        )
    }
}
