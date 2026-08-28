package pro.liliya.core.services

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServiceResolverEmptyContractTest {
    @Test
    fun empty_input_resolves_to_empty_order() {
        val result = ServiceDependencyResolver().resolve(emptyList())

        assertIs<ServiceResolutionResult.Resolved>(result)
        assertTrue(result.services.isEmpty())
    }
}
