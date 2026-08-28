package pro.liliya.core.services

import java.util.concurrent.ConcurrentHashMap

sealed interface ServiceRegistrationResult {
    data object Registered : ServiceRegistrationResult
    data class Rejected(val reason: String) : ServiceRegistrationResult
}

class ServiceRegistry {
    private val services = ConcurrentHashMap<String, CoreService>()

    fun register(service: CoreService): ServiceRegistrationResult {
        val previous = services.putIfAbsent(service.descriptor.id, service)
        return if (previous == null) {
            ServiceRegistrationResult.Registered
        } else {
            ServiceRegistrationResult.Rejected(
                "service already registered: ${service.descriptor.id}"
            )
        }
    }

    fun find(id: String): CoreService? = services[id]

    fun contains(id: String): Boolean = services.containsKey(id)

    fun snapshot(): Map<String, CoreService> = services.toMap()
}
