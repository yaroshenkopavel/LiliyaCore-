package pro.liliya.core.foundation

import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.lifecycle.LifecycleCommand
import pro.liliya.core.lifecycle.LifecyclePhase
import pro.liliya.core.lifecycle.LifecycleResult
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class FoundationCompositionContractTest {
    @Test
    fun composition_owns_foundation_subsystems_with_one_observability_pipeline() {
        val logs = InMemoryLogWriter()
        val diagnostics = InMemoryDiagnosticSink()
        var nextId = 0
        val composition = FoundationComposition(
            diagnostics = DiagnosticRecorder(diagnostics),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "foundation-${++nextId}" }
        )
        val context = composition.rootContext(
            operation = "prepare",
            metadata = mapOf("scope" to "foundation")
        )

        val result = composition.lifecycle.execute(
            LifecycleCommand(
                phase = LifecyclePhase.PREPARE,
                reason = "foundation startup",
                context = context
            )
        )

        assertIs<LifecycleResult.Applied>(result)
        assertSame(composition.runtimeStateHolder, composition.runtimeStateHolder)
        assertEquals(
            listOf("RUNTIME_TRANSITION_APPLIED", "LIFECYCLE_COMMAND_APPLIED"),
            logs.snapshot().map { it.marker }
        )
        assertEquals(
            listOf("RUNTIME_TRANSITION_APPLIED", "LIFECYCLE_COMMAND_APPLIED"),
            diagnostics.snapshot().map { it.code }
        )
        assertEquals(setOf("foundation-1"), logs.snapshot().map { it.context.correlationId }.toSet())
        assertEquals(setOf("foundation-1"), diagnostics.snapshot().map { it.context.correlationId }.toSet())
    }

    @Test
    fun child_context_preserves_parent_correlation_and_inherits_metadata() {
        var nextId = 0
        val composition = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, InMemoryLogWriter()) },
            correlationIds = CorrelationIdGenerator { "correlation-${++nextId}" }
        )
        val root = composition.rootContext(
            operation = "request",
            metadata = mapOf("requestId" to "42")
        )
        val child = composition.childContext(
            parent = root,
            component = "Services",
            operation = "start",
            metadata = mapOf("serviceId" to "memory")
        )

        assertNotEquals(root.correlationId, child.correlationId)
        assertEquals(root.correlationId, child.parentCorrelationId)
        assertEquals("42", child.metadata["requestId"])
        assertEquals("memory", child.metadata["serviceId"])
    }
}
