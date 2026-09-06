package pro.liliya.android.runtime

import kotlin.test.assertEquals
import org.junit.Test

class HeartRuntimeStartupCoordinatorContractTest {

    @Test
    fun startup_is_storage_then_semantic_then_generation_then_ready() {
        val events = mutableListOf<String>()
        val coordinator = coordinator(events)

        assertEquals(HeartRuntimeStartResult.Ready, coordinator.start())
        assertEquals(listOf("storage-start", "semantic-start", "generation-start"), events)
        assertEquals(HeartRuntimeState.READY, coordinator.state())
    }

    @Test
    fun storage_failure_blocks_later_startup_and_compensates_storage_attempt() {
        val events = mutableListOf<String>()
        val coordinator = coordinator(
            events = events,
            storageResult = HeartDependencyStartResult.Failed
        )

        assertEquals(
            HeartRuntimeStartResult.Failed(HeartRuntimePhase.STORAGE),
            coordinator.start()
        )
        assertEquals(listOf("storage-start", "storage-close"), events)
        assertEquals(HeartRuntimeState.FAILED, coordinator.state())
    }

    @Test
    fun semantic_failure_blocks_generation_and_compensates_reverse() {
        val events = mutableListOf<String>()
        val coordinator = coordinator(
            events = events,
            semanticResult = HeartDependencyStartResult.Failed
        )

        assertEquals(
            HeartRuntimeStartResult.Failed(HeartRuntimePhase.SEMANTIC),
            coordinator.start()
        )
        assertEquals(
            listOf("storage-start", "semantic-start", "semantic-close", "storage-close"),
            events
        )
        assertEquals(HeartRuntimeState.FAILED, coordinator.state())
    }

    @Test
    fun generation_failure_compensates_generation_semantic_storage() {
        val events = mutableListOf<String>()
        val coordinator = coordinator(
            events = events,
            generationResult = HeartDependencyStartResult.Failed
        )

        assertEquals(
            HeartRuntimeStartResult.Failed(HeartRuntimePhase.GENERATION),
            coordinator.start()
        )
        assertEquals(
            listOf(
                "storage-start",
                "semantic-start",
                "generation-start",
                "generation-close",
                "semantic-close",
                "storage-close"
            ),
            events
        )
        assertEquals(HeartRuntimeState.FAILED, coordinator.state())
    }

    @Test
    fun cleanup_attempts_all_lower_phases_even_when_generation_close_fails() {
        val events = mutableListOf<String>()
        val coordinator = coordinator(
            events = events,
            generationResult = HeartDependencyStartResult.Failed,
            generationCloseResult = HeartDependencyCloseResult.Failed
        )

        assertEquals(
            HeartRuntimeStartResult.CleanupFailed(
                failedPhase = HeartRuntimePhase.GENERATION,
                cleanupPhase = HeartRuntimePhase.GENERATION
            ),
            coordinator.start()
        )
        assertEquals(
            listOf(
                "storage-start",
                "semantic-start",
                "generation-start",
                "generation-close",
                "semantic-close",
                "storage-close"
            ),
            events
        )
        assertEquals(HeartRuntimeState.FAILED, coordinator.state())
    }

    @Test
    fun cleanup_failure_never_publishes_ready_or_closed() {
        val events = mutableListOf<String>()
        val coordinator = coordinator(
            events = events,
            generationResult = HeartDependencyStartResult.Failed,
            semanticCloseResult = HeartDependencyCloseResult.Failed
        )

        assertEquals(
            HeartRuntimeStartResult.CleanupFailed(
                failedPhase = HeartRuntimePhase.GENERATION,
                cleanupPhase = HeartRuntimePhase.SEMANTIC
            ),
            coordinator.start()
        )
        assertEquals(HeartRuntimeState.FAILED, coordinator.state())
    }

    @Test
    fun repeated_start_is_rejected_without_replaying_dependencies() {
        val events = mutableListOf<String>()
        val coordinator = coordinator(events)

        assertEquals(HeartRuntimeStartResult.Ready, coordinator.start())
        assertEquals(
            HeartRuntimeStartResult.Busy(HeartRuntimeState.READY),
            coordinator.start()
        )
        assertEquals(listOf("storage-start", "semantic-start", "generation-start"), events)
    }

    @Test
    fun close_is_reverse_order_and_closed_owner_does_not_restart() {
        val events = mutableListOf<String>()
        val coordinator = coordinator(events)

        assertEquals(HeartRuntimeStartResult.Ready, coordinator.start())
        assertEquals(HeartRuntimeCloseResult.Closed, coordinator.close())
        assertEquals(
            listOf(
                "storage-start",
                "semantic-start",
                "generation-start",
                "generation-close",
                "semantic-close",
                "storage-close"
            ),
            events
        )
        assertEquals(HeartRuntimeState.CLOSED, coordinator.state())
        assertEquals(
            HeartRuntimeStartResult.Busy(HeartRuntimeState.CLOSED),
            coordinator.start()
        )
        assertEquals(HeartRuntimeCloseResult.AlreadyClosed, coordinator.close())
    }

    private fun coordinator(
        events: MutableList<String>,
        storageResult: HeartDependencyStartResult = HeartDependencyStartResult.Ready,
        semanticResult: HeartDependencyStartResult = HeartDependencyStartResult.Ready,
        generationResult: HeartDependencyStartResult = HeartDependencyStartResult.Ready,
        storageCloseResult: HeartDependencyCloseResult = HeartDependencyCloseResult.Closed,
        semanticCloseResult: HeartDependencyCloseResult = HeartDependencyCloseResult.Closed,
        generationCloseResult: HeartDependencyCloseResult = HeartDependencyCloseResult.Closed
    ): HeartRuntimeStartupCoordinator =
        HeartRuntimeStartupCoordinator(
            storageStart = HeartStorageStartupPort {
                events += "storage-start"
                storageResult
            },
            storageClose = HeartStorageClosePort {
                events += "storage-close"
                storageCloseResult
            },
            semanticStart = HeartSemanticStartupPort {
                events += "semantic-start"
                semanticResult
            },
            semanticClose = HeartSemanticClosePort {
                events += "semantic-close"
                semanticCloseResult
            },
            generationStart = HeartGenerationStartupPort {
                events += "generation-start"
                generationResult
            },
            generationClose = HeartGenerationClosePort {
                events += "generation-close"
                generationCloseResult
            }
        )
}
