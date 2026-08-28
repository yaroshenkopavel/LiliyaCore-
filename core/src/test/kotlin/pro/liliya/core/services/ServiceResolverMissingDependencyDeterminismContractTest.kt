package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceResolverMissingDependencyDeterminismContractTest {
    @Test
    fun equivalent_invalid_graphs_report_same_missing_dependency_regardless_of_input_order() {
        fun service(id: String, dependency: String) = object : CoreService {
            override val descriptor = ServiceDescriptor(id, setOf(dependency))
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }
        val alpha = service("alpha", "missing-a")
        val beta = service("beta", "missing-b")
        val resolver = ServiceDependencyResolver()

        val first = resolver.resolve(listOf(beta, alpha))
        val second = resolver.resolve(listOf(alpha, beta))

        assertIs<ServiceResolutionResult.MissingDependency>(first)
        assertIs<ServiceResolutionResult.MissingDependency>(second)
        assertEquals(first, second)
    }
}
