package pro.liliya.core.observability

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.lifecycle.LifecycleCommand
import pro.liliya.core.lifecycle.LifecycleController
import pro.liliya.core.lifecycle.LifecyclePhase
import pro.liliya.core.lifecycle.LifecycleResult
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.LogContextPropagation
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.runtime.RuntimeStateController
import pro.liliya.core.runtime.RuntimeStateHolder
import pro.liliya.core.runtime.RuntimeTransitionPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FoundationRuntimeLifecycleObservabilityContractTest {
    @Test
    fun lifecycle_operation_is_visible_in_logs_and_diagnostics_with_one_correlation() {
        val logWriter = InMemoryLogWriter()
        val diagnosticSink = InMemoryDiagnosticSink()
        val diagnostics = DiagnosticRecorder(diagnosticSink)
        val observability = CoreObservability(
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logWriter) },
            diagnostics = diagnostics
        )
        val runtime = RuntimeStateController(
            stateHolder = RuntimeStateHolder(),
            transitionPolicy = RuntimeTransitionPolicy(),
            diagnostics = diagnostics,
            observability = observability
        )
        val lifecycle = LifecycleController(
            runtime = runtime,
            diagnostics = diagnostics,
            observability = observability
        )
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "FoundationIntegration",
            operation = "prepare",
            generator = CorrelationIdGenerator { "foundation-prepare" }
        )

        val result = lifecycle.execute(
            LifecycleCommand(
                phase = LifecyclePhase.PREPARE,
                reason = "foundation startup",
                context = context
            )
        )

        assertIs<LifecycleResult.Applied>(result)
        assertEquals(
            listOf("RUNTIME_TRANSITION_APPLIED", "LIFECYCLE_COMMAND_APPLIED"),
            logWriter.snapshot().map { it.marker }
        )
        assertEquals(
            listOf("RUNTIME_TRANSITION_APPLIED", "LIFECYCLE_COMMAND_APPLIED"),
            diagnosticSink.snapshot().map { it.code }
        )
        assertEquals(
            setOf("foundation-prepare"),
            logWriter.snapshot().map { it.context.correlationId }.toSet()
        )
        assertEquals(
            setOf("foundation-prepare"),
            diagnosticSink.snapshot().map { it.context.correlationId }.toSet()
        )
    }
}
