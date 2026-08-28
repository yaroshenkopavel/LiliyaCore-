package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import kotlin.test.Test
import kotlin.test.assertIs

class ServiceResolverDuplicateInputContractTest {
    @Test
    fun duplicate_service_ids_in_direct_resolver_input_are_rejected() {
        fun service() = object : CoreService {
            override val descriptor = ServiceDescriptor("memory")
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }

        val result = ServiceDependencyResolver().resolve(listOf(service(), service()))

        assertIs<ServiceResolutionResult.DuplicateService>(result)
    }
}
