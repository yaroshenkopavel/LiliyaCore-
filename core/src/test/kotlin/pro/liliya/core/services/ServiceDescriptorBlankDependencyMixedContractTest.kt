package pro.liliya.core.services

import kotlin.test.Test
import kotlin.test.assertFailsWith

class ServiceDescriptorBlankDependencyMixedContractTest {
    @Test
    fun blank_dependency_is_rejected_even_when_other_dependencies_are_valid() {
        assertFailsWith<IllegalArgumentException> {
            ServiceDescriptor("assistant", setOf("memory", " ", "model"))
        }
    }
}
