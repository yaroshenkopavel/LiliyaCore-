package pro.liliya.core.services

import kotlin.test.Test
import kotlin.test.assertTrue

class ServiceDescriptorToStringContractTest {
    @Test
    fun descriptor_string_contains_service_identity_and_dependencies() {
        val text = ServiceDescriptor("memory", setOf("storage")).toString()

        assertTrue("memory" in text)
        assertTrue("storage" in text)
    }
}
