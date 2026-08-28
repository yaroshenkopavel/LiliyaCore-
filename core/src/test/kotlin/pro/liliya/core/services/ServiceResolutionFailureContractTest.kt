package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceResolutionFailureContractTest {
    @Test
    fun missing_dependency_result_identifies_owner_and_missing_id() {
        val service = object : CoreService {
            override val descriptor = ServiceDescriptor("memory", setOf("storage"))
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }

        val result = ServiceDependencyResolver().resolve(listOf(service))

        assertIs<ServiceResolutionResult.MissingDependency>(result)
        assertEquals("memory", result.serviceId)
        assertEquals("storage", result.dependencyId)
    }

    @Test
    fun cycle_result_contains_all_unresolved_service_ids() {
        fun service(id: String, dependency: String) = object : CoreService {
            override val descriptor = ServiceDescriptor(id, setOf(dependency))
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }

        val result = ServiceDependencyResolver().resolve(
            listOf(service("a", "b"), service("b", "a"))
        )

        assertIs<ServiceResolutionResult.CycleDetected>(result)
        assertEquals(setOf("a", "b"), result.serviceIds)
    }
}
