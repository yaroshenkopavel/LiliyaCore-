package pro.liliya.core.knowledge

import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.LogContext

interface KnowledgeOwnership {
    val item: KnowledgeItem
    val generation: KnowledgeGeneration
    fun remove(): Boolean
}

sealed interface KnowledgeCreateResult {
    data class Created(val ownership: KnowledgeOwnership) : KnowledgeCreateResult
    data class Rejected(val reason: String) : KnowledgeCreateResult
}

class KnowledgeComposition(
    private val foundation: FoundationComposition
) {
    private val store = KnowledgeStore(foundation.observability)

    fun create(item: KnowledgeItem): KnowledgeCreateResult = create(
        item = item,
        context = foundation.rootContext(
            operation = "createKnowledge",
            component = "Knowledge",
            metadata = originMetadata(item)
        )
    )

    internal fun create(
        item: KnowledgeItem,
        context: LogContext
    ): KnowledgeCreateResult {
        val operationContext = context.copy(
            metadata = (context.metadata + originMetadata(item)).toMap()
        )
        return when (val result = store.register(item, operationContext)) {
            is KnowledgeRegistrationResult.Registered -> KnowledgeCreateResult.Created(
                ownership = object : KnowledgeOwnership {
                    override val item: KnowledgeItem = result.registration.item
                    override val generation: KnowledgeGeneration = result.registration.generation

                    override fun remove(): Boolean = result.registration.remove(
                        foundation.childContext(
                            parent = operationContext,
                            component = "Knowledge",
                            operation = "removeKnowledge",
                            metadata = mapOf(
                                "knowledgeGeneration" to generation.value.toString()
                            )
                        )
                    )
                }
            )

            is KnowledgeRegistrationResult.Rejected -> KnowledgeCreateResult.Rejected(result.reason)
        }
    }

    fun find(id: KnowledgeItemId): KnowledgeItem? = store.find(id)

    fun inspect(id: KnowledgeItemId): KnowledgeItemSnapshot? = store.inspect(id)

    fun contains(id: KnowledgeItemId): Boolean = store.contains(id)

    fun snapshot(): List<KnowledgeItem> = store.snapshot()

    fun snapshotEntries(): List<KnowledgeItemSnapshot> = store.snapshotEntries()

    private fun originMetadata(item: KnowledgeItem): Map<String, String> = buildMap {
        put("knowledgeItemId", item.id.value)
        when (val origin = item.origin) {
            is KnowledgeOrigin.Memory -> {
                put("knowledgeOriginType", "memory")
                put("memoryRecordId", origin.recordId.value)
                put("memoryGeneration", origin.generation.value.toString())
            }

            is KnowledgeOrigin.Declared -> {
                put("knowledgeOriginType", "declared")
                put("knowledgeSourceId", origin.sourceId.value)
                origin.sourceReference?.let { reference ->
                    put("knowledgeSourceReference", reference.value)
                }
            }
        }
    }
}
