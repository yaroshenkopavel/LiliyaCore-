package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceResolverCycleDeterminismContractTest {
    @Test
    fun equivalent_cycles_report_same_unresolved_identity_set() {
        fun service(id: String, dependency: String) = object : CoreService {
            override val descriptor = ServiceDescriptor(id, setOf(dependency))
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }
        val a = service("a", "b")
        val b = service("b", "a")
        val resolver = ServiceDependencyResolver()

        val first = resolver.resolve(listOf(a, b))
        val second = resolver.resolve(listOf(b, a))

        assertIs<ServiceResolutionResult.CycleDetected>(first)
        assertIs<ServiceResolutionResult.CycleDetected>(second)
        assertEquals(first.serviceIds, second.serviceIds)
    }
}
