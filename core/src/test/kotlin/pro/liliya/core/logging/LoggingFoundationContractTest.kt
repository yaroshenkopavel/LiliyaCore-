package pro.liliya.core.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LoggingFoundationContractTest {

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
}
