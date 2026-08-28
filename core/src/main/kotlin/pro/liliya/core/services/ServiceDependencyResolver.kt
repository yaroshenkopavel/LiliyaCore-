package pro.liliya.core.services

sealed interface ServiceResolutionResult {
    data class Resolved(val services: List<CoreService>) : ServiceResolutionResult
    data class MissingDependency(val serviceId: String, val dependencyId: String) : ServiceResolutionResult
    data class CycleDetected(val serviceIds: Set<String>) : ServiceResolutionResult
}

class ServiceDependencyResolver {
    fun resolve(services: Collection<CoreService>): ServiceResolutionResult {
        val byId = services.associateBy { it.descriptor.id }

        for (service in services) {
            for (dependency in service.descriptor.dependencies) {
                if (dependency !in byId) {
                    return ServiceResolutionResult.MissingDependency(
                        serviceId = service.descriptor.id,
                        dependencyId = dependency
                    )
                }
            }
        }

        val remaining = services.associate { service ->
            service.descriptor.id to service.descriptor.dependencies.toMutableSet()
        }.toMutableMap()
        val ordered = mutableListOf<CoreService>()

        while (remaining.isNotEmpty()) {
            val readyIds = remaining
                .filterValues { it.isEmpty() }
                .keys
                .sorted()

            if (readyIds.isEmpty()) {
                return ServiceResolutionResult.CycleDetected(remaining.keys.toSet())
            }

            for (id in readyIds) {
                ordered += byId.getValue(id)
                remaining.remove(id)
            }
            remaining.values.forEach { dependencies -> dependencies.removeAll(readyIds.toSet()) }
        }

        return ServiceResolutionResult.Resolved(ordered)
    }
}
