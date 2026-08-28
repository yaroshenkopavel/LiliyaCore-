package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceResolverMissingDependencyLexicalContractTest {
    @Test
    fun multiple_missing_dependencies_on_same_service_report_lexically_first_id() {
        val service = object : CoreService {
            override val descriptor = ServiceDescriptor("memory", setOf("zeta", "alpha"))
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }

        val result = ServiceDependencyResolver().resolve(listOf(service))

        assertIs<ServiceResolutionResult.MissingDependency>(result)
        assertEquals("memory", result.serviceId)
        assertEquals("alpha", result.dependencyId)
    }
}
