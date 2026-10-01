package pro.liliya.core.asf

class AgentBlueprintRegistry(blueprints: Collection<AgentBlueprint>) {
    private val byReference: Map<AgentBlueprintReference, AgentBlueprint>

    init {
        val pairs = blueprints.map {
            AgentBlueprintReference(it.id, it.version) to it
        }
        require(pairs.map { it.first }.distinct().size == pairs.size) {
            "duplicate exact-version agent blueprint"
        }
        byReference = pairs.toMap()
    }

    fun resolve(reference: AgentBlueprintReference): AgentBlueprint? = byReference[reference]

    fun requireExact(reference: AgentBlueprintReference): AgentBlueprint =
        requireNotNull(resolve(reference)) {
            "unknown exact-version agent blueprint: ${reference.id.value}@${reference.version.value}"
        }

    fun references(): List<AgentBlueprintReference> =
        byReference.keys.sortedWith(
            compareBy<AgentBlueprintReference> { it.id.value }
                .thenBy { it.version.value }
        )
}
