package pro.liliya.core.reflection

import pro.liliya.core.foundation.FoundationComposition

interface ReflectionOwnership {
    val record: ReflectionRecord
    val generation: ReflectionGeneration
    fun remove(): Boolean
}

sealed interface ReflectionInstallResult {
    data class Installed(val ownership: ReflectionOwnership) : ReflectionInstallResult
    data class Rejected(val reason: String) : ReflectionInstallResult
}

class ReflectionComposition(
    private val foundation: FoundationComposition
) {
    private val store = ReflectionRecordStore(foundation.observability)

    fun install(record: ReflectionRecord): ReflectionInstallResult {
        val context = foundation.rootContext(
            operation = "installReflectionRecord",
            component = "Reflection",
            metadata = recordMetadata(record)
        )
        return when (val result = store.register(record, context)) {
            is ReflectionRecordRegistrationResult.Registered -> {
                val registration = result.registration
                ReflectionInstallResult.Installed(
                    ownership = object : ReflectionOwnership {
                        override val record: ReflectionRecord = registration.record
                        override val generation: ReflectionGeneration = registration.generation

                        override fun remove(): Boolean = registration.remove(
                            foundation.rootContext(
                                operation = "removeReflectionRecord",
                                component = "Reflection",
                                metadata = recordMetadata(record) +
                                    ("reflectionGeneration" to generation.value.toString())
                            )
                        )
                    }
                )
            }

            is ReflectionRecordRegistrationResult.Rejected ->
                ReflectionInstallResult.Rejected(result.reason)
        }
    }

    fun find(id: ReflectionRecordId): ReflectionRecord? = store.find(id)

    fun inspect(id: ReflectionRecordId): ReflectionRecordSnapshot? = store.inspect(id)

    fun contains(id: ReflectionRecordId): Boolean = store.contains(id)

    fun snapshot(): List<ReflectionRecord> = store.snapshot()

    fun snapshotEntries(): List<ReflectionRecordSnapshot> = store.snapshotEntries()

    private fun recordMetadata(record: ReflectionRecord): Map<String, String> = buildMap {
        put("reflectionRecordId", record.id.value)
        put("createdAt", record.createdAt.toString())
        when (val origin = record.origin) {
            is ReflectionOrigin.Memory -> {
                put("reflectionOriginType", "memory")
                put("memoryRecordId", origin.recordId.value)
                put("memoryGeneration", origin.generation.value.toString())
            }

            is ReflectionOrigin.Knowledge -> {
                put("reflectionOriginType", "knowledge")
                put("knowledgeItemId", origin.itemId.value)
                put("knowledgeGeneration", origin.generation.value.toString())
            }

            is ReflectionOrigin.Declared -> {
                put("reflectionOriginType", "declared")
                put("reflectionSourceId", origin.sourceId.value)
                origin.sourceReference?.let { reference ->
                    put("reflectionSourceReference", reference.value)
                }
            }
        }
    }
}
