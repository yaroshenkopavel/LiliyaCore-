package pro.liliya.core.modules

import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleDescriptorValueContractTest {
    @Test
    fun equivalent_descriptors_have_value_equality() {
        assertEquals(
            ModuleDescriptor("memory", setOf("storage")),
            ModuleDescriptor("memory", setOf("storage"))
        )
    }
}
