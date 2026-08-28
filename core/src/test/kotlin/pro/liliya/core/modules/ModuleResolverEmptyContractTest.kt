package pro.liliya.core.modules

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModuleResolverEmptyContractTest {
    @Test
    fun empty_module_graph_resolves_to_empty_order() {
        val result = ModuleDependencyResolver().resolve(emptyList())
        assertIs<ModuleResolutionResult.Resolved>(result)
        assertTrue(result.modules.isEmpty())
    }
}
