package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceResolverMultiLevelContractTest {
    @Test
    fun multi_level_graph_resolves_all_dependencies_before_dependents() {
        fun service(id: String, dependencies: Set<String> = emptySet()) = object : CoreService {
            override val descriptor = ServiceDescriptor(id, dependencies)
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }

        val result = ServiceDependencyResolver().resolve(
            listOf(
                service("assistant", setOf("memory", "model")),
                service("memory", setOf("storage")),
                service("model"),
                service("storage")
            )
        )

        assertIs<ServiceResolutionResult.Resolved>(result)
        val ids = result.services.map { it.descriptor.id }
        assertEquals(setOf("assistant", "memory", "model", "storage"), ids.toSet())
        assert(ids.indexOf("storage") < ids.indexOf("memory"))
        assert(ids.indexOf("memory") < ids.indexOf("assistant"))
        assert(ids.indexOf("model") < ids.indexOf("assistant"))
    }
}
