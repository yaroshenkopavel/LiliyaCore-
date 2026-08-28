package pro.liliya.core.services

import pro.liliya.core.logging.LogContext
import kotlin.test.Test
import kotlin.test.assertIs

class ServiceResolverDuplicatePrecedenceContractTest {
    @Test
    fun duplicate_identity_is_rejected_before_dependency_analysis() {
        fun service() = object : CoreService {
            override val descriptor = ServiceDescriptor("memory", setOf("missing"))
            override fun start(context: LogContext) = Unit
            override fun stop(context: LogContext) = Unit
        }

        val result = ServiceDependencyResolver().resolve(listOf(service(), service()))

        assertIs<ServiceResolutionResult.DuplicateService>(result)
    }
}
