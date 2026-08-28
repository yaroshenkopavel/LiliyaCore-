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
            .filterValues { it > 1 }
            .keys
            .minOrNull()
        if (duplicateId != null) {
            return ServiceResolutionResult.DuplicateService(duplicateId)
        }

        val byId = serviceList.associateBy { it.descriptor.id }
        val missing = serviceList
            .flatMap { service ->
                service.descriptor.dependencies
                    .filter { dependency -> dependency !in byId }
                    .map { dependency -> service.descriptor.id to dependency }
            }
            .minWithOrNull(compareBy<Pair<String, String>>({ it.first }, { it.second }))
        if (missing != null) {
            return ServiceResolutionResult.MissingDependency(
                serviceId = missing.first,
                dependencyId = missing.second
            )
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
