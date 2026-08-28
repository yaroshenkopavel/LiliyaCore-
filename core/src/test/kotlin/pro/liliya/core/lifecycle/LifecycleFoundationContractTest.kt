package pro.liliya.core.lifecycle

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContextPropagation
import pro.liliya.core.runtime.RuntimeState
import pro.liliya.core.runtime.RuntimeStateController
import pro.liliya.core.runtime.RuntimeStateHolder
import pro.liliya.core.runtime.RuntimeTransitionPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LifecycleFoundationContractTest {

    private fun context(operation: String) = LogContextPropagation.root(
        module = "CORE",
        component = "Lifecycle",
        operation = operation,
        generator = CorrelationIdGenerator { "lifecycle-$operation" }
    )

    private fun fixture(initial: RuntimeState = RuntimeState.CREATED): Triple<LifecycleController, InMemoryDiagnosticSink, RuntimeStateController> {
        val diagnostics = InMemoryDiagnosticSink()
        val recorder = DiagnosticRecorder(diagnostics)
        val runtime = RuntimeStateController(
            stateHolder = RuntimeStateHolder(initial),
            transitionPolicy = RuntimeTransitionPolicy(),
            diagnostics = recorder
        )
        val lifecycle = LifecycleController(
            runtime = runtime,
            diagnostics = recorder
        )
        return Triple(lifecycle, diagnostics, runtime)
    }

    @Test
    fun prepare_start_stop_follow_nominal_runtime_lifecycle() {
        val (lifecycle, diagnostics, runtime) = fixture()

        assertIs<LifecycleResult.Applied>(
            lifecycle.execute(LifecycleCommand(LifecyclePhase.PREPARE, "prepare", context("prepare")))
        )
        assertEquals(RuntimeState.STARTING, runtime.currentState())

        assertIs<LifecycleResult.Applied>(
            lifecycle.execute(LifecycleCommand(LifecyclePhase.START, "start", context("start")))
        )
        assertEquals(RuntimeState.RUNNING, runtime.currentState())

        assertIs<LifecycleResult.Applied>(
            lifecycle.execute(LifecycleCommand(LifecyclePhase.STOP, "stop", context("stop")))
        )
        assertEquals(RuntimeState.STOPPING, runtime.currentState())

        assertEquals(
            listOf(
                "RUNTIME_TRANSITION_APPLIED",
                "LIFECYCLE_COMMAND_APPLIED",
                "RUNTIME_TRANSITION_APPLIED",
                "LIFECYCLE_COMMAND_APPLIED",
                "RUNTIME_TRANSITION_APPLIED",
                "LIFECYCLE_COMMAND_APPLIED"
            ),
            diagnostics.snapshot().map { it.code }
        )
    }

    @Test
    fun invalid_lifecycle_command_is_rejected_without_state_change() {
        val (lifecycle, diagnostics, runtime) = fixture()

        val result = lifecycle.execute(
            LifecycleCommand(
                phase = LifecyclePhase.START,
                reason = "start without prepare",
                context = context("invalid-start")
            )
        )

        assertIs<LifecycleResult.Rejected>(result)
        assertEquals(RuntimeState.CREATED, runtime.currentState())
        assertEquals(
            listOf("RUNTIME_TRANSITION_REJECTED", "LIFECYCLE_COMMAND_REJECTED"),
            diagnostics.snapshot().map { it.code }
        )
    }

    @Test
    fun repeated_prepare_is_rejected_after_first_application() {
        val (lifecycle, diagnostics, runtime) = fixture()

        assertIs<LifecycleResult.Applied>(
            lifecycle.execute(LifecycleCommand(LifecyclePhase.PREPARE, "first", context("prepare-first")))
        )
        assertIs<LifecycleResult.Rejected>(
            lifecycle.execute(LifecycleCommand(LifecyclePhase.PREPARE, "second", context("prepare-second")))
        )

        assertEquals(RuntimeState.STARTING, runtime.currentState())
        assertEquals(4, diagnostics.snapshot().size)
    }
}
