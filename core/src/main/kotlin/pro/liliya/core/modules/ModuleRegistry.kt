package pro.liliya.core.modules

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

interface ModuleRegistration {
    val moduleId: String
    fun unregister(): Boolean
}

sealed interface ModuleRegistrationResult {
    data class Registered(val registration: ModuleRegistration) : ModuleRegistrationResult
    data class Rejected(val reason: String) : ModuleRegistrationResult
}

class ModuleRegistry {
    private data class Entry(
        val token: Long,
        val module: CoreModule
    )

    private val nextToken = AtomicLong(0)
    private val modules = ConcurrentHashMap<String, Entry>()

    fun register(module: CoreModule): ModuleRegistrationResult {
        val id = module.descriptor.id
        val entry = Entry(
            token = nextToken.incrementAndGet(),
            module = module
        )
        val previous = modules.putIfAbsent(id, entry)
        if (previous != null) {
            return ModuleRegistrationResult.Rejected(
                "module already registered: $id"
            )
        }

        return ModuleRegistrationResult.Registered(
            registration = object : ModuleRegistration {
                override val moduleId: String = id

                override fun unregister(): Boolean =
                    modules.remove(id, entry)
            }
        )
    }

    fun find(id: String): CoreModule? = modules[id]?.module

    fun contains(id: String): Boolean = modules.containsKey(id)

    fun snapshot(): Map<String, CoreModule> =
        modules.mapValues { (_, entry) -> entry.module }
}
