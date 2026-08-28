package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServiceResolverBranchingGraphContractTest {
    @Test
    fun shared_dependency_precedes_all_of_its_dependents() {
        fun service(id: String, dependencies: Set<String> = emptySet()) = object : CoreService {
            override val descriptor = ServiceDescriptor(id, dependencies)
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }
        val result = ServiceDependencyResolver().resolve(
            listOf(
                service("memory", setOf("storage")),
                service("knowledge", setOf("storage")),
                service("storage")
            )
        )

        assertIs<ServiceResolutionResult.Resolved>(result)
        val ids = result.services.map { it.descriptor.id }
        assertTrue(ids.indexOf("storage") < ids.indexOf("memory"))
        assertTrue(ids.indexOf("storage") < ids.indexOf("knowledge"))
    }
}
