package pro.liliya.core.logging

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LoggingFoundationContractTest {

    @AfterTest
    fun resetGlobalSequence() {
        GlobalLogSequence.resetForTest()
    }

    private fun event(
        level: LogLevel = LogLevel.INFO,
        sequence: Long = 1L
    ) = LogEvent(
        timestampMillis = 100L,
        sequence = sequence,
        level = level,
        context = LogContext(
            module = "core",
            component = "logging",
            operation = "test",
            correlationId = "corr"
        ),
        marker = "TEST",
        message = "message",
        threadName = "test-thread"
    )

    @Test
    fun correlation_ids_are_unique() {
        val first = UuidCorrelationIdGenerator.nextId()
        val second = UuidCorrelationIdGenerator.nextId()

        assertTrue(first.isNotBlank())
        assertTrue(second.isNotBlank())
        assertNotEquals(first, second)
    }

    @Test
    fun filtering_writer_forwards_only_allowed_levels() {
        val sink = InMemoryLogWriter()
        val writer = FilteringLogWriter(LogLevel.WARN, sink)

        writer.write(event(LogLevel.INFO, 1L))
        writer.write(event(LogLevel.WARN, 2L))
        writer.write(event(LogLevel.ERROR, 3L))

        assertEquals(
            listOf(LogLevel.WARN, LogLevel.ERROR),
            sink.snapshot().map { it.level }
        )
    }

    @Test
    fun safe_writer_isolates_delegate_failure() {
        var observedFailure: Throwable? = null
        var observedEvent: LogEvent? = null
        val failure = IllegalStateException("writer failed")
        val writer = SafeLogWriter(
            delegate = LogWriter { throw failure },
            onFailure = { error, failedEvent ->
                observedFailure = error
                observedEvent = failedEvent
            }
        )

        writer.write(event())

        assertEquals(failure, observedFailure)
        assertEquals("message", observedEvent?.message)
    }

    @Test
    fun writer_failure_can_be_recorded_as_structured_diagnostic() {
        val observer = InMemoryLogFailureObserver()
        val failingWriter = object : LogWriter {
            override fun write(event: LogEvent) {
                throw IllegalStateException("disk unavailable")
            }
        }
        val writer = SafeLogWriter(
            delegate = failingWriter,
            onFailure = { error, failedEvent ->
                observer.record(
                    LogWriterFailure(
                        writerType = failingWriter.javaClass.name,
                        eventSequence = failedEvent.sequence,
                        marker = failedEvent.marker,
                        throwableType = error.javaClass.name,
                        throwableMessage = error.message
                    )
                )
            }
        )

        writer.write(event(sequence = 7L))

        val failure = observer.snapshot().single()
        assertEquals(7L, failure.eventSequence)
        assertEquals("TEST", failure.marker)
        assertEquals(IllegalStateException::class.java.name, failure.throwableType)
        assertEquals("disk unavailable", failure.throwableMessage)
    }

    @Test
    fun composite_writer_attempts_all_delegates() {
        val first = InMemoryLogWriter()
        val second = InMemoryLogWriter()
        val writer = CompositeLogWriter(listOf(first, second))

        writer.write(event())

        assertEquals(1, first.snapshot().size)
        assertEquals(1, second.snapshot().size)
    }

    @Test
    fun bootstrap_writer_replays_buffer_in_order_after_install() {
        val bootstrap = BootstrapLogWriter(capacity = 3)
        bootstrap.write(event(sequence = 1L))
        bootstrap.write(event(sequence = 2L))
        bootstrap.write(event(sequence = 3L))

        val sink = InMemoryLogWriter()
        bootstrap.install(sink)
        bootstrap.write(event(sequence = 4L))

        assertEquals(
            listOf(1L, 2L, 3L, 4L),
            sink.snapshot().map { it.sequence }
        )
        assertTrue(bootstrap.bufferedEvents().isEmpty())
    }

    @Test
    fun bootstrap_writer_keeps_only_latest_events_when_full() {
        val bootstrap = BootstrapLogWriter(capacity = 2)
        bootstrap.write(event(sequence = 1L))
        bootstrap.write(event(sequence = 2L))
        bootstrap.write(event(sequence = 3L))

        assertEquals(
            listOf(2L, 3L),
            bootstrap.bufferedEvents().map { it.sequence }
        )
    }

    @Test
    fun sequence_is_global_across_logger_instances() {
        GlobalLogSequence.resetForTest()

        val writer = InMemoryLogWriter()
        val first = StructuredLogger(
            context = LogContext("CORE", "First", "one"),
            writer = writer
        )
        val second = StructuredLogger(
            context = LogContext("CORE", "Second", "two"),
            writer = writer
        )

        first.info("FIRST", "first")
        second.info("SECOND", "second")
        first.info("THIRD", "third")

        assertEquals(listOf(1L, 2L, 3L), writer.snapshot().map { it.sequence })
    }

    @Test
    fun global_sequence_remains_unique_under_concurrent_logging() {
        GlobalLogSequence.resetForTest()

        val writer = InMemoryLogWriter()
        val workers = 8
        val eventsPerWorker = 50
        val start = CountDownLatch(1)
        val done = CountDownLatch(workers)
        val executor = Executors.newFixedThreadPool(workers)

        repeat(workers) { index ->
            executor.execute {
                val logger = StructuredLogger(
                    context = LogContext("CORE", "Worker-$index", "concurrent"),
                    writer = writer
                )
                start.await()
                repeat(eventsPerWorker) { eventIndex ->
                    logger.debug("EVENT", "$index:$eventIndex")
                }
                done.countDown()
            }
        }

        start.countDown()
        done.await()
        executor.shutdown()

        val sequences = writer.snapshot().map { it.sequence }
        assertEquals(workers * eventsPerWorker, sequences.size)
        assertEquals(sequences.size, sequences.toSet().size)
        assertEquals((1L..sequences.size.toLong()).toSet(), sequences.toSet())
    }

    @Test
    fun child_context_links_to_parent_and_merges_metadata() {
        val ids = ArrayDeque(listOf("root-id", "child-id"))
        val generator = CorrelationIdGenerator { ids.removeFirst() }

        val root = LogContextPropagation.root(
            module = "CORE",
            component = "Interaction",
            operation = "request",
            metadata = mapOf("requestType" to "user"),
            generator = generator
        )

        val child = LogContextPropagation.child(
            parent = root,
            component = "Cognition",
            operation = "interpret",
            metadata = mapOf("stage" to "meaning"),
            generator = generator
        )

        assertEquals("root-id", root.correlationId)
        assertEquals(null, root.parentCorrelationId)
        assertEquals("child-id", child.correlationId)
        assertEquals("root-id", child.parentCorrelationId)
        assertEquals(
            mapOf("requestType" to "user", "stage" to "meaning"),
            child.metadata
        )
    }
}
