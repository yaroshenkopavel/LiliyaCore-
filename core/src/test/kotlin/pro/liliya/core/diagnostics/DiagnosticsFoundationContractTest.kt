package pro.liliya.core.diagnostics

import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.LogContextPropagation
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class DiagnosticsFoundationContractTest {

    @AfterTest
    fun resetSequence() {
        GlobalDiagnosticSequence.resetForTest()
    }

    @Test
    fun recorder_captures_structured_diagnostic_event() {
        GlobalDiagnosticSequence.resetForTest()
        val sink = InMemoryDiagnosticSink()
        val recorder = DiagnosticRecorder(sink = sink, clock = { 1234L })
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "Diagnostics",
            operation = "record",
            generator = CorrelationIdGenerator { "diag-root" }
        )

        recorder.record(
            severity = DiagnosticSeverity.WARNING,
            code = "CORE_WARN",
            message = "warning observed",
            context = context,
            metadata = mapOf("service" to "memory")
        )

        val event = sink.snapshot().single()
        assertEquals(1234L, event.timestampMillis)
        assertEquals(1L, event.sequence)
        assertEquals(DiagnosticSeverity.WARNING, event.severity)
        assertEquals("CORE_WARN", event.code)
        assertEquals("warning observed", event.message)
        assertEquals("diag-root", event.context.correlationId)
        assertEquals(mapOf("service" to "memory"), event.metadata)
    }

    @Test
    fun recorder_snapshots_metadata_and_throwable_details() {
        GlobalDiagnosticSequence.resetForTest()
        val sink = InMemoryDiagnosticSink()
        val recorder = DiagnosticRecorder(sink = sink)
        val mutableMetadata = linkedMapOf("stage" to "before")
        val context = LogContextPropagation.root(
            module = "CORE",
            component = "Diagnostics",
            operation = "failure",
            generator = CorrelationIdGenerator { "diag-failure" }
        )
        val failure = IllegalStateException("boom")

        recorder.record(
            severity = DiagnosticSeverity.ERROR,
            code = "CORE_FAILURE",
            message = "failure observed",
            context = context,
            metadata = mutableMetadata,
            throwable = failure
        )
        mutableMetadata["stage"] = "after"

        val event = sink.snapshot().single()
        assertEquals(mapOf("stage" to "before"), event.metadata)
        assertNotSame(mutableMetadata, event.metadata)
        assertEquals(IllegalStateException::class.java.name, event.throwableType)
        assertEquals("boom", event.throwableMessage)
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
        assertEquals(7L, failure.eventSequence)
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
        assertTrue(values.all { it > 0L })
    }
}
