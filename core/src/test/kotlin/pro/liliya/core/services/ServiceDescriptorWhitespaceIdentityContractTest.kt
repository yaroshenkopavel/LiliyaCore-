package pro.liliya.core.services

import kotlin.test.Test
import kotlin.test.assertEquals

class ServiceDescriptorWhitespaceIdentityContractTest {
    @Test
    fun valid_service_id_is_preserved_exactly_without_hidden_normalization() {
        val descriptor = ServiceDescriptor("memory-service")

        assertEquals("memory-service", descriptor.id)
    }
}
