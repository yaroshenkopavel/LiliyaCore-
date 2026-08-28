package pro.liliya.core.services

import kotlin.test.Test
import kotlin.test.assertEquals

class ServiceDescriptorHashContractTest {
    @Test
    fun equivalent_descriptors_have_equal_hash_codes() {
        val first = ServiceDescriptor("memory", setOf("storage"))
        val second = ServiceDescriptor("memory", setOf("storage"))

        assertEquals(first.hashCode(), second.hashCode())
    }
}
