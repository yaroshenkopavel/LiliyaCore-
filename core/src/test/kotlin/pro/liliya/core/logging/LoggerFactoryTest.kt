package pro.liliya.core.logging

import kotlin.test.Test
import kotlin.test.assertEquals

class LoggerFactoryTest {

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
}
