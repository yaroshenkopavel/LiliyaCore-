package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServiceResolverDuplicateDoesNotExecuteContractTest {
    @Test
    fun resolution_never_invokes_service_lifecycle_methods() {
        var calls = 0
        fun service(id: String) = object : CoreService {
            override val descriptor = ServiceDescriptor(id)
            override fun start(context: LogContext) { calls++ }
            override fun stop(context: LogContext) { calls++ }
        }

        assertIs<ServiceResolutionResult.Resolved>(
            ServiceDependencyResolver().resolve(listOf(service("storage"), service("memory")))
        )
        assertEquals(0, calls)
    }
}
