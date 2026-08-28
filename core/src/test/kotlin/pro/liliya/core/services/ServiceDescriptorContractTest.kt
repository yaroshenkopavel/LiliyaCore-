package pro.liliya.core.services

import kotlin.test.Test
import kotlin.test.assertFailsWith

class ServiceDescriptorContractTest {
    @Test
    fun blank_service_id_is_rejected() {
        assertFailsWith<IllegalArgumentException> { ServiceDescriptor(" ") }
    }

    @Test
    fun blank_dependency_id_is_rejected() {
        assertFailsWith<IllegalArgumentException> {
            ServiceDescriptor("memory", setOf(" "))
        }
    }

    @Test
    fun self_dependency_is_rejected() {
        assertFailsWith<IllegalArgumentException> {
            ServiceDescriptor("memory", setOf("memory"))
        }
    }
}
