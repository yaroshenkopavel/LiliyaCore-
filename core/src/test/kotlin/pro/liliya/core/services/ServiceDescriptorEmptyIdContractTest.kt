package pro.liliya.core.services

import kotlin.test.Test
import kotlin.test.assertFailsWith

class ServiceDescriptorEmptyIdContractTest {
    @Test
    fun empty_service_id_is_rejected() {
        assertFailsWith<IllegalArgumentException> { ServiceDescriptor("") }
    }
}
