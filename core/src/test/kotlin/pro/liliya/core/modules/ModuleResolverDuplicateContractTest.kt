package pro.liliya.core.modules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ModuleResolverDuplicateContractTest {
    @Test
    fun duplicate_module_ids_are_rejected_deterministically() {
        fun module(id: String) = object : CoreModule {
            override val descriptor = ModuleDescriptor(id)
            override val services = emptyList<pro.liliya.core.services.CoreService>()
        }

        val result = ModuleDependencyResolver().resolve(
            listOf(module("memory"), module("memory"))
        )

        assertIs<ModuleResolutionResult.DuplicateModule>(result)
        assertEquals("memory", result.moduleId)
    }
}
