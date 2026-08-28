package pro.liliya.core.modules

class ModuleDescriptor(
    val id: String,
    dependencies: Set<String> = emptySet()
) {
    val dependencies: Set<String> = dependencies.toSet()

    init {
        require(id.isNotBlank()) { "module id must not be blank" }
        require(this.dependencies.none { it.isBlank() }) { "module dependencies must not contain blank ids" }
        require(id !in this.dependencies) { "module must not depend on itself" }
    }

    override fun equals(other: Any?): Boolean =
        other is ModuleDescriptor && id == other.id && dependencies == other.dependencies

    override fun hashCode(): Int = 31 * id.hashCode() + dependencies.hashCode()

    override fun toString(): String = "ModuleDescriptor(id=$id, dependencies=$dependencies)"
}
