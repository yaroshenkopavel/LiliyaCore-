package pro.liliya.core.services

data class ServiceDescriptor(
    val id: String,
    val dependencies: Set<String> = emptySet()
) {
    init {
        require(id.isNotBlank()) { "service id must not be blank" }
        require(dependencies.none { it.isBlank() }) { "service dependencies must not contain blank ids" }
        require(id !in dependencies) { "service must not depend on itself" }
    }
}
