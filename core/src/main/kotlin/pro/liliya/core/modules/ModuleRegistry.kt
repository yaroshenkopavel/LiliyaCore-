package pro.liliya.core.modules

import java.util.concurrent.ConcurrentHashMap

sealed interface ModuleRegistrationResult {
    data object Registered : ModuleRegistrationResult
    data class Rejected(val reason: String) : ModuleRegistrationResult
}

class ModuleRegistry {
    private val modules = ConcurrentHashMap<String, CoreModule>()

    fun register(module: CoreModule): ModuleRegistrationResult {
        val previous = modules.putIfAbsent(module.descriptor.id, module)
        return if (previous == null) {
            ModuleRegistrationResult.Registered
        } else {
            ModuleRegistrationResult.Rejected(
                "module already registered: ${module.descriptor.id}"
            )
        }
    }

    fun find(id: String): CoreModule? = modules[id]

    fun contains(id: String): Boolean = modules.containsKey(id)

    fun snapshot(): Map<String, CoreModule> = modules.toMap()
}
