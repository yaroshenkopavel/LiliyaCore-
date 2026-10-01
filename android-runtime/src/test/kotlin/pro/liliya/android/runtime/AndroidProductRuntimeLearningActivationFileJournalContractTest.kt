package pro.liliya.android.runtime

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test

class AndroidProductRuntimeLearningActivationFileJournalContractTest {
    @Test
    fun missing_state_is_clean_and_transitions_survive_new_journal_instance() = withRoot { root ->
        val first = AndroidProductRuntimeLearningActivationFileJournal.createForDirectory(root)
        assertEquals(
            AndroidProductRuntimeLearningActivationJournalLoadResult.Loaded(
                AndroidProductRuntimeLearningActivationJournalState.CLEAN
            ),
            first.load()
        )
        assertEquals(
            AndroidProductRuntimeLearningActivationJournalTransitionResult.Updated,
            first.compareAndSet(
                AndroidProductRuntimeLearningActivationJournalState.CLEAN,
                AndroidProductRuntimeLearningActivationJournalState.ACTIVATING
            )
        )

        val restarted = AndroidProductRuntimeLearningActivationFileJournal.createForDirectory(root)
        assertEquals(
            AndroidProductRuntimeLearningActivationJournalLoadResult.Loaded(
                AndroidProductRuntimeLearningActivationJournalState.ACTIVATING
            ),
            restarted.load()
        )
        assertEquals(
            AndroidProductRuntimeLearningActivationJournalTransitionResult.Updated,
            restarted.compareAndSet(
                AndroidProductRuntimeLearningActivationJournalState.ACTIVATING,
                AndroidProductRuntimeLearningActivationJournalState.ACTIVATED
            )
        )

        val completed = AndroidProductRuntimeLearningActivationFileJournal.createForDirectory(root)
        assertEquals(
            AndroidProductRuntimeLearningActivationJournalLoadResult.Loaded(
                AndroidProductRuntimeLearningActivationJournalState.ACTIVATED
            ),
            completed.load()
        )
    }

    @Test
    fun compare_and_set_conflict_preserves_actual_state() = withRoot { root ->
        val journal = AndroidProductRuntimeLearningActivationFileJournal.createForDirectory(root)
        assertEquals(
            AndroidProductRuntimeLearningActivationJournalTransitionResult.Updated,
            journal.compareAndSet(
                AndroidProductRuntimeLearningActivationJournalState.CLEAN,
                AndroidProductRuntimeLearningActivationJournalState.ACTIVATING
            )
        )
        assertEquals(
            AndroidProductRuntimeLearningActivationJournalTransitionResult.Conflict(
                AndroidProductRuntimeLearningActivationJournalState.ACTIVATING
            ),
            journal.compareAndSet(
                AndroidProductRuntimeLearningActivationJournalState.CLEAN,
                AndroidProductRuntimeLearningActivationJournalState.ACTIVATED
            )
        )
        assertEquals(
            AndroidProductRuntimeLearningActivationJournalLoadResult.Loaded(
                AndroidProductRuntimeLearningActivationJournalState.ACTIVATING
            ),
            journal.load()
        )
    }

    @Test
    fun corrupt_state_fails_closed_and_cannot_be_overwritten_as_clean() = withRoot { root ->
        Files.write(
            root.toPath().resolve("activation.state"),
            "corrupt\n".toByteArray(StandardCharsets.UTF_8)
        )
        val journal = AndroidProductRuntimeLearningActivationFileJournal.createForDirectory(root)

        assertEquals(AndroidProductRuntimeLearningActivationJournalLoadResult.Failed, journal.load())
        assertEquals(
            AndroidProductRuntimeLearningActivationJournalTransitionResult.Failed,
            journal.compareAndSet(
                AndroidProductRuntimeLearningActivationJournalState.CLEAN,
                AndroidProductRuntimeLearningActivationJournalState.ACTIVATING
            )
        )
    }

    @Test
    fun two_journal_instances_cannot_both_claim_clean_activation() = withRoot { root ->
        val first = AndroidProductRuntimeLearningActivationFileJournal.createForDirectory(root)
        val second = AndroidProductRuntimeLearningActivationFileJournal.createForDirectory(root)
        val results = java.util.Collections.synchronizedList(
            mutableListOf<AndroidProductRuntimeLearningActivationJournalTransitionResult>()
        )
        val threads = listOf(first, second).map { journal ->
            Thread {
                results += journal.compareAndSet(
                    AndroidProductRuntimeLearningActivationJournalState.CLEAN,
                    AndroidProductRuntimeLearningActivationJournalState.ACTIVATING
                )
            }
        }

        threads.forEach(Thread::start)
        threads.forEach(Thread::join)

        assertEquals(1, results.count {
            it is AndroidProductRuntimeLearningActivationJournalTransitionResult.Updated
        })
        assertEquals(1, results.count {
            it is AndroidProductRuntimeLearningActivationJournalTransitionResult.Conflict &&
                it.actual == AndroidProductRuntimeLearningActivationJournalState.ACTIVATING
        })
        assertIs<AndroidProductRuntimeLearningActivationJournalLoadResult.Loaded>(first.load())
    }

    private fun withRoot(block: (java.io.File) -> Unit) {
        val root = Files.createTempDirectory("liliya-learning-activation-journal").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
