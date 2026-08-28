package pro.liliya.core.runtime

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RuntimeFoundationContractTest {

    private fun context(operation: String) = LogContextPropagation.root(
        module = "CORE",
        component = "Runtime",
        operation = operation,
        generator = CorrelationIdGenerator { "runtime-$operation" }
    )

    @Test
    fun allowed_transition_is_applied_and_observable() {
        val diagnostics = InMemoryDiagnosticSink()
        val controller = RuntimeStateController(
            stateHolder = RuntimeStateHolder(RuntimeState.CREATED),
            transitionPolicy = RuntimeTransitionPolicy(),
            diagnostics = DiagnosticRecorder(diagnostics)
        )

        val result = controller.transition(
            to = RuntimeState.STARTING,
            reason = "startup requested",
            context = context("start")
        )

        val applied = assertIs<RuntimeTransitionResult.Applied>(result)
        assertEquals(RuntimeState.CREATED, applied.transition.from)
        assertEquals(RuntimeState.STARTING, applied.transition.to)
        assertEquals(RuntimeState.STARTING, controller.currentState())

        val event = diagnostics.snapshot().single()
        assertEquals("RUNTIME_TRANSITION_APPLIED", event.code)
        assertEquals("CREATED", event.metadata["from"])
        assertEquals("STARTING", event.metadata["to"])
        assertEquals("startup requested", event.metadata["reason"])
    }

    @Test
    fun forbidden_transition_is_rejected_without_state_change() {
        val diagnostics = InMemoryDiagnosticSink()
        val controller = RuntimeStateController(
            stateHolder = RuntimeStateHolder(RuntimeState.CREATED),
            transitionPolicy = RuntimeTransitionPolicy(),
            diagnostics = DiagnosticRecorder(diagnostics)
        )

        val result = controller.transition(
            to = RuntimeState.RUNNING,
            reason = "skip startup",
            context = context("reject")
        )

        val rejected = assertIs<RuntimeTransitionResult.Rejected>(result)
        assertEquals(RuntimeState.CREATED, rejected.from)
        assertEquals(RuntimeState.RUNNING, rejected.to)
        assertEquals(RuntimeState.CREATED, controller.currentState())

        val event = diagnostics.snapshot().single()
        assertEquals("RUNTIME_TRANSITION_REJECTED", event.code)
        assertEquals("CREATED", event.metadata["from"])
        assertEquals("RUNNING", event.metadata["to"])
    }

    @Test
    fun runtime_can_follow_nominal_lifecycle() {
        val controller = RuntimeStateController(
            stateHolder = RuntimeStateHolder(RuntimeState.CREATED),
            transitionPolicy = RuntimeTransitionPolicy(),
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink())
        )

        val states = listOf(
            RuntimeState.STARTING,
            RuntimeState.RUNNING,
            RuntimeState.STOPPING,
            RuntimeState.STOPPED
        )

        states.forEachIndexed { index, target ->
            assertIs<RuntimeTransitionResult.Applied>(
                controller.transition(
                    to = target,
                    reason = "step-$index",
                    context = context("step-$index")
                )
            )
        }

        assertEquals(RuntimeState.STOPPED, controller.currentState())
    }

    @Test
    fun runtime_can_fail_from_active_lifecycle_states() {
        listOf(
            RuntimeState.STARTING,
            RuntimeState.RUNNING,
            RuntimeState.STOPPING
        ).forEach { initial ->
            val controller = RuntimeStateController(
                stateHolder = RuntimeStateHolder(initial),
                transitionPolicy = RuntimeTransitionPolicy(),
                diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink())
            )

            assertIs<RuntimeTransitionResult.Applied>(
                controller.transition(
                    to = RuntimeState.FAILED,
                    reason = "failure",
                    context = context("fail-${initial.name.lowercase()}")
                )
            )
            assertEquals(RuntimeState.FAILED, controller.currentState())
        }
    }

    @Test
    fun concurrent_competing_transitions_apply_at_most_one_state_change() {
        val diagnostics = InMemoryDiagnosticSink()
        val controller = RuntimeStateController(
            stateHolder = RuntimeStateHolder(RuntimeState.STARTING),
            transitionPolicy = RuntimeTransitionPolicy(),
            diagnostics = DiagnosticRecorder(diagnostics)
        )
        val start = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(2)
        val results = java.util.Collections.synchronizedList(
            mutableListOf<RuntimeTransitionResult>()
        )
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)

        listOf(RuntimeState.RUNNING, RuntimeState.FAILED).forEach { target ->
            executor.execute {
                start.await()
                results += controller.transition(
                    to = target,
                    reason = "competing-${target.name.lowercase()}",
                    context = context("competing-${target.name.lowercase()}")
                )
                done.countDown()
            }
        }

        start.countDown()
        done.await()
        executor.shutdown()

        assertEquals(2, results.size)
        assertEquals(1, results.count { it is RuntimeTransitionResult.Applied })
        assertEquals(1, results.count { it is RuntimeTransitionResult.Rejected })
        assertTrue(controller.currentState() in setOf(RuntimeState.RUNNING, RuntimeState.FAILED))
        assertEquals(2, diagnostics.snapshot().size)
    }
}
