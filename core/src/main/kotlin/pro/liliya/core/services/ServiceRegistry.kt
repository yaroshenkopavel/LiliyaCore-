package pro.liliya.core.services

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

interface ServiceRegistration {
    val serviceId: String
    fun unregister(): Boolean
}

sealed interface ServiceRegistrationResult {
    data class Registered(val registration: ServiceRegistration) : ServiceRegistrationResult
    data class Rejected(val reason: String) : ServiceRegistrationResult
}

class ServiceRegistry {
    private data class Entry(
        val token: Long,
        val service: CoreService
    )

    private val nextToken = AtomicLong(0)
    private val services = ConcurrentHashMap<String, Entry>()

    fun register(service: CoreService): ServiceRegistrationResult {
        val id = service.descriptor.id
        val entry = Entry(
            token = nextToken.incrementAndGet(),
            service = service
        )
        val previous = services.putIfAbsent(id, entry)
        if (previous != null) {
            return ServiceRegistrationResult.Rejected(
                "service already registered: $id"
            )
        }

        return ServiceRegistrationResult.Registered(
            registration = object : ServiceRegistration {
                override val serviceId: String = id

                override fun unregister(): Boolean =
                    services.remove(id, entry)
            }
        )
    }

    fun find(id: String): CoreService? = services[id]?.service

    fun contains(id: String): Boolean = services.containsKey(id)

    fun snapshot(): Map<String, CoreService> =
        services.mapValues { (_, entry) -> entry.service }
}
