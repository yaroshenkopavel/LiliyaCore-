package pro.liliya.core.logging

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LoggerFactoryTest {

    @AfterTest
    fun resetFactory() {
        LoggerFactory.resetForTest()
    }

    @Test
    fun factory_uses_installed_writer_without_silent_noop() {
        val writer = InMemoryLogWriter()
        LoggerFactory.installWriter(writer)

        val logger = LoggerFactory.create(
            LogContext(
                module = "CORE",
                component = "Bootstrap",
                operation = "factory-test"
            )
        )

        logger.info("READY", "logging available")

        assertEquals(1, writer.snapshot().size)
        assertEquals("logging available", writer.snapshot().single().message)
    }

    @Test
    fun factory_replays_events_created_before_writer_installation() {
        val logger = LoggerFactory.create(
            LogContext(
                module = "CORE",
                component = "Bootstrap",
                operation = "early-start"
            )
        )

        logger.info("EARLY", "before writer installation")

        val writer = InMemoryLogWriter()
        LoggerFactory.installWriter(writer)

        val events = writer.snapshot()
        assertEquals(1, events.size)
        assertEquals("EARLY", events.single().marker)
        assertEquals("before writer installation", events.single().message)
    }
}
