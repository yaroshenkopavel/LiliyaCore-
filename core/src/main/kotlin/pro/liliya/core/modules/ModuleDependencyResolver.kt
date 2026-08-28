package pro.liliya.core.modules

sealed interface ModuleResolutionResult {
    data class Resolved(val modules: List<CoreModule>) : ModuleResolutionResult
    data class DuplicateModule(val moduleId: String) : ModuleResolutionResult
    data class MissingDependency(val moduleId: String, val dependencyId: String) : ModuleResolutionResult
    data class CycleDetected(val moduleIds: Set<String>) : ModuleResolutionResult
}

class ModuleDependencyResolver {
    fun resolve(modules: Collection<CoreModule>): ModuleResolutionResult {
        val moduleList = modules.toList()
        val duplicateId = moduleList
            .groupingBy { it.descriptor.id }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .minOrNull()
        if (duplicateId != null) {
            return ModuleResolutionResult.DuplicateModule(duplicateId)
        }

        val byId = moduleList.associateBy { it.descriptor.id }
        val missing = moduleList
            .flatMap { module ->
                module.descriptor.dependencies
                    .filter { dependency -> dependency !in byId }
                    .map { dependency -> module.descriptor.id to dependency }
            }
            .minWithOrNull(compareBy<Pair<String, String>>({ it.first }, { it.second }))
        if (missing != null) {
            return ModuleResolutionResult.MissingDependency(
                moduleId = missing.first,
                dependencyId = missing.second
            )
        }

        val remaining = moduleList.associate { module ->
            module.descriptor.id to module.descriptor.dependencies.toMutableSet()
        }.toMutableMap()
        val ordered = mutableListOf<CoreModule>()

        while (remaining.isNotEmpty()) {
            val readyIds = remaining
                .filterValues { it.isEmpty() }
                .keys
                .sorted()

            if (readyIds.isEmpty()) {
                return ModuleResolutionResult.CycleDetected(remaining.keys.toSet())
            }

            for (id in readyIds) {
                ordered += byId.getValue(id)
                remaining.remove(id)
            }
            val readySet = readyIds.toSet()
            remaining.values.forEach { dependencies -> dependencies.removeAll(readySet) }
        }

        return ModuleResolutionResult.Resolved(ordered)
    }
}
