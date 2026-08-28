package pro.liliya.core.services

class ServiceDescriptor(
    val id: String,
    dependencies: Set<String> = emptySet()
) {
    val dependencies: Set<String> = dependencies.toSet()

    init {
        require(id.isNotBlank()) { "service id must not be blank" }
        require(this.dependencies.none { it.isBlank() }) { "service dependencies must not contain blank ids" }
        require(id !in this.dependencies) { "service must not depend on itself" }
    }

    override fun equals(other: Any?): Boolean =
        other is ServiceDescriptor && id == other.id && dependencies == other.dependencies

    override fun hashCode(): Int = 31 * id.hashCode() + dependencies.hashCode()

    override fun toString(): String = "ServiceDescriptor(id=$id, dependencies=$dependencies)"
}
