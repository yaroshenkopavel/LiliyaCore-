package pro.liliya.core.services

sealed interface ServiceResolutionResult {
    data class Resolved(val services: List<CoreService>) : ServiceResolutionResult
    data class DuplicateService(val serviceId: String) : ServiceResolutionResult
    data class MissingDependency(val serviceId: String, val dependencyId: String) : ServiceResolutionResult
    data class CycleDetected(val serviceIds: Set<String>) : ServiceResolutionResult
}

class ServiceDependencyResolver {
    fun resolve(services: Collection<CoreService>): ServiceResolutionResult {
        val serviceList = services.toList()
        val duplicateId = serviceList
            .groupingBy { it.descriptor.id }
            .eachCount()
            .entries
            .firstOrNull { it.value > 1 }
            ?.key
        if (duplicateId != null) {
            return ServiceResolutionResult.DuplicateService(duplicateId)
        }

        val byId = serviceList.associateBy { it.descriptor.id }

        for (service in serviceList) {
            for (dependency in service.descriptor.dependencies) {
                if (dependency !in byId) {
                    return ServiceResolutionResult.MissingDependency(
                        serviceId = service.descriptor.id,
                        dependencyId = dependency
                    )
                }
            }
        }

        val remaining = serviceList.associate { service ->
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
            val readySet = readyIds.toSet()
            remaining.values.forEach { dependencies -> dependencies.removeAll(readySet) }
        }

        return ServiceResolutionResult.Resolved(ordered)
    }
}
