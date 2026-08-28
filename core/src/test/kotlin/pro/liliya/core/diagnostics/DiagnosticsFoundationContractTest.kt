package pro.liliya.core.diagnostics

import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DiagnosticsFoundationContractTest {

    @AfterTest
    fun resetSequence() {
        GlobalDiagnosticSequence.resetForTest()
    }

    @Test
    fun recorder_creates_structured_diagnostic_event() {
        GlobalDiagnosticSequence.resetForTest()
        val sink = InMemoryDiagnosticSink()
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "Diagnostics",
            operation = "record",
            metadata = mapOf("request" to "42"),
            generator = CorrelationIdGenerator { "root-id" }
        )
        val recorder = DiagnosticRecorder(
            sink = sink,
            clock = { 1234L }
        )

        recorder.record(
            severity = DiagnosticSeverity.WARNING,
            code = "TEST_WARNING",
            message = "warning",
            context = context,
            metadata = mapOf("stage" to "validation")
        )

        val event = sink.snapshot().single()
        assertEquals(1234L, event.timestampMillis)
        assertEquals(1L, event.sequence)
        assertEquals(DiagnosticSeverity.WARNING, event.severity)
        assertEquals("TEST_WARNING", event.code)
        assertEquals("warning", event.message)
        assertEquals("root-id", event.context.correlationId)
        assertEquals(
            mapOf("request" to "42", "stage" to "validation"),
            event.metadata
        )
    }

    @Test
    fun recorder_snapshots_metadata() {
        val sink = InMemoryDiagnosticSink()
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "Diagnostics",
            operation = "metadata",
            generator = CorrelationIdGenerator { "metadata-root" }
        )
        val metadata = linkedMapOf("state" to "before")
        val recorder = DiagnosticRecorder(sink = sink)

        recorder.record(
            severity = DiagnosticSeverity.INFO,
            code = "METADATA",
            message = "snapshot",
            context = context,
            metadata = metadata
        )
        metadata["state"] = "after"

        assertEquals("before", sink.snapshot().single().metadata["state"])
    }

    @Test
    fun recorder_preserves_throwable_details() {
        val sink = InMemoryDiagnosticSink()
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "Diagnostics",
            operation = "failure",
            generator = CorrelationIdGenerator { "failure-root" }
        )
        val recorder = DiagnosticRecorder(sink = sink)
        val error = IllegalStateException("diagnostic failure")

        recorder.record(
            severity = DiagnosticSeverity.ERROR,
            code = "FAILURE",
            message = "failed",
            context = context,
            throwable = error
        )

        val event = sink.snapshot().single()
        assertEquals(IllegalStateException::class.java.name, event.throwableType)
        assertEquals("diagnostic failure", event.throwableMessage)
    }

    @Test
    fun safe_sink_isolates_failure_and_reports_it() {
        val observer = InMemoryDiagnosticFailureObserver()
        val safeSink = SafeDiagnosticSink(
            delegate = DiagnosticSink { throw IllegalArgumentException("sink failed") },
            onFailure = observer::record
        )
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "Diagnostics",
            operation = "safe-sink",
            generator = CorrelationIdGenerator { "safe-root" }
        )
        val event = DiagnosticEvent(
            timestampMillis = 10L,
            sequence = 7L,
            severity = DiagnosticSeverity.ERROR,
            code = "SINK_FAILURE_TEST",
            message = "test",
            context = context
        )

        safeSink.record(event)

        val failure = observer.snapshot().single()
        assertEquals(7L, failure.sequence)
        assertEquals("SINK_FAILURE_TEST", failure.code)
        assertEquals(IllegalArgumentException::class.java.name, failure.throwableType)
        assertEquals("sink failed", failure.throwableMessage)
    }

    @Test
    fun diagnostic_sequence_is_unique_under_concurrency() {
        GlobalDiagnosticSequence.resetForTest()
        val values = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val workers = 8
        val perWorker = 50
        val start = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(workers)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(workers)

        repeat(workers) {
            executor.execute {
                start.await()
                repeat(perWorker) {
                    values += GlobalDiagnosticSequence.next()
                }
                done.countDown()
            }
        }

        start.countDown()
        done.await()
        executor.shutdown()

        assertEquals(workers * perWorker, values.size)
        assertEquals(values.size, values.toSet().size)
        assertEquals((1L..values.size.toLong()).toSet(), values.toSet())
    }

    @Test
    fun correlation_context_is_preserved_across_diagnostics() {
        val sink = InMemoryDiagnosticSink()
        val ids = ArrayDeque(listOf("root-id", "child-id"))
        val generator = CorrelationIdGenerator { ids.removeFirst() }
        val root = LogContextPropagation.root(
            module = "CORE",
            component = "Runtime",
            operation = "start",
            generator = generator
        )
        val child = LogContextPropagation.child(
            parent = root,
            component = "Diagnostics",
            operation = "inspect",
            generator = generator
        )
        val recorder = DiagnosticRecorder(sink = sink)

        recorder.record(
            severity = DiagnosticSeverity.INFO,
            code = "ROOT",
            message = "root",
            context = root
        )
        recorder.record(
            severity = DiagnosticSeverity.INFO,
            code = "CHILD",
            message = "child",
            context = child
        )

        val events = sink.snapshot()
        assertNotEquals(events[0].context.correlationId, events[1].context.correlationId)
        assertEquals("root-id", events[1].context.parentCorrelationId)
        assertTrue(events.all { it.context.correlationId.isNotBlank() })
    }
}
