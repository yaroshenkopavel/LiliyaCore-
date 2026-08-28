package pro.liliya.core.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class StructuredLoggerTest {

    @Test
    fun structured_event_preserves_context_and_sequence() {
        val writer = InMemoryLogWriter()
        val logger = StructuredLogger(
            context = LogContext(
                module = "CORE",
                component = "Logging",
                operation = "test",
                correlationId = "cycle-1"
            ),
            writer = writer,
            clock = { 123L }
        )

        logger.info("BOOT", "first")
        logger.error("FAIL", "second", IllegalStateException("boom"))

        val events = writer.snapshot()

        assertEquals(2, events.size)
        assertEquals(1L, events[0].sequence)
        assertEquals(2L, events[1].sequence)
        assertEquals(123L, events[0].timestampMillis)
        assertEquals("cycle-1", events[0].context.correlationId)
        assertEquals(LogLevel.ERROR, events[1].level)
        assertEquals("java.lang.IllegalStateException", events[1].throwableType)
        assertEquals("boom", events[1].throwableMessage)
        assertNotNull(events[0].threadName)
    }
}
