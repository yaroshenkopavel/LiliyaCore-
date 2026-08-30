package pro.liliya.core.knowledge

import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.persistence.PersistentInstallResult
import pro.liliya.core.persistence.PersistentMutationResult
import pro.liliya.core.persistence.PersistentRecordBackend
import pro.liliya.core.persistence.PersistentRecordOwnership
import pro.liliya.core.persistence.PersistentRecordStore
import pro.liliya.core.persistence.PersistentStoreId
import pro.liliya.core.persistence.PersistentStoreOpenResult

interface PersistentKnowledgeOwnership {
    val item: KnowledgeItem
    val generation: KnowledgeGeneration
    fun remove(): PersistentKnowledgeMutationResult
}

sealed interface PersistentKnowledgeCreateResult {
    data class Created(val ownership: PersistentKnowledgeOwnership) : PersistentKnowledgeCreateResult
    data class Rejected(val reason: String) : PersistentKnowledgeCreateResult
    data class Failed(val reason: String, val throwable: Throwable? = null) : PersistentKnowledgeCreateResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

sealed interface PersistentKnowledgeMutationResult {
    data object Committed : PersistentKnowledgeMutationResult
    data class Rejected(val reason: String) : PersistentKnowledgeMutationResult
    data class Failed(val reason: String, val throwable: Throwable? = null) : PersistentKnowledgeMutationResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

sealed interface PersistentKnowledgeOpenResult {
    data class Opened(val composition: PersistentKnowledgeComposition) : PersistentKnowledgeOpenResult
    data object Corrupt : PersistentKnowledgeOpenResult
    data class Incompatible(val reason: String) : PersistentKnowledgeOpenResult
    data class Failed(val reason: String, val throwable: Throwable? = null) : PersistentKnowledgeOpenResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
    data class RestorationFailed(val reason: String) : PersistentKnowledgeOpenResult
}

class PersistentKnowledgeComposition private constructor(
    private val foundation: FoundationComposition,
    private val persistentStore: PersistentRecordStore,
    private val knowledgeStore: KnowledgeStore
) {
    @Synchronized
    fun create(item: KnowledgeItem): PersistentKnowledgeCreateResult {
        val persistentRecord = KnowledgePersistentRecordCodec.encode(item)
        return when (val installed = persistentStore.install(persistentRecord)) {
            is PersistentInstallResult.Installed -> installCommittedKnowledge(item, installed.ownership)
            is PersistentInstallResult.Rejected -> PersistentKnowledgeCreateResult.Rejected(installed.reason)
            is PersistentInstallResult.Failed -> PersistentKnowledgeCreateResult.Failed(
                reason = "persistent knowledge durable install failed",
                throwable = installed.throwable
            )
        }
    }

    fun find(id: KnowledgeItemId): KnowledgeItem? = knowledgeStore.find(id)

    fun inspect(id: KnowledgeItemId): KnowledgeItemSnapshot? = knowledgeStore.inspect(id)

    fun contains(id: KnowledgeItemId): Boolean = knowledgeStore.contains(id)

    fun snapshot(): List<KnowledgeItem> = knowledgeStore.snapshot()

    fun snapshotEntries(): List<KnowledgeItemSnapshot> = knowledgeStore.snapshotEntries()

    private fun installCommittedKnowledge(
        item: KnowledgeItem,
        persistentOwnership: PersistentRecordOwnership
    ): PersistentKnowledgeCreateResult {
        val generation = KnowledgeGeneration(persistentOwnership.generation.value)
        val context = foundation.rootContext(
            operation = "createPersistedKnowledge",
            component = "Knowledge",
            metadata = knowledgeMetadata(item, generation)
        )

        return when (
            val local = knowledgeStore.installCommitted(
                item = item,
                generation = generation,
                highWatermark = persistentStore.generationHighWatermark(),
                context = context
            )
        ) {
            is KnowledgeRegistrationResult.Registered -> PersistentKnowledgeCreateResult.Created(
                ownership = ownership(
                    persistentOwnership = persistentOwnership,
                    localRegistration = local.registration,
                    operationContext = context
                )
            )

            is KnowledgeRegistrationResult.Rejected -> {
                val compensated = persistentOwnership.remove()
                val reason = if (compensated is PersistentMutationResult.Committed) {
                    "local committed knowledge install rejected after durable commit; durable candidate compensated"
                } else {
                    "local committed knowledge install rejected after durable commit; durable compensation failed"
                }
                PersistentKnowledgeCreateResult.Failed(reason)
            }
        }
    }

    private fun ownership(
        persistentOwnership: PersistentRecordOwnership,
        localRegistration: KnowledgeRegistration,
        operationContext: pro.liliya.core.logging.LogContext
    ): PersistentKnowledgeOwnership = object : PersistentKnowledgeOwnership {
        override val item: KnowledgeItem = localRegistration.item
        override val generation: KnowledgeGeneration = localRegistration.generation

        override fun remove(): PersistentKnowledgeMutationResult = synchronized(this@PersistentKnowledgeComposition) {
            when (val durable = persistentOwnership.remove()) {
                PersistentMutationResult.Committed -> {
                    val removedLocally = localRegistration.remove(
                        foundation.childContext(
                            parent = operationContext,
                            component = "Knowledge",
                            operation = "removePersistedKnowledge",
                            metadata = mapOf(
                                "knowledgeGeneration" to generation.value.toString()
                            )
                        )
                    )
                    if (removedLocally) {
                        PersistentKnowledgeMutationResult.Committed
                    } else {
                        PersistentKnowledgeMutationResult.Failed(
                            "durable knowledge removal committed but local exact removal failed"
                        )
                    }
                }

                is PersistentMutationResult.Rejected ->
                    PersistentKnowledgeMutationResult.Rejected(durable.reason)

                is PersistentMutationResult.Failed ->
                    PersistentKnowledgeMutationResult.Failed(
                        reason = "persistent knowledge durable removal failed",
                        throwable = durable.throwable
                    )
            }
        }
    }

    private fun knowledgeMetadata(
        item: KnowledgeItem,
        generation: KnowledgeGeneration
    ): Map<String, String> = buildMap {
        put("knowledgeItemId", item.id.value)
        put("knowledgeGeneration", generation.value.toString())
        put("createdAt", item.createdAt.toString())
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

    companion object {
        fun open(
            foundation: FoundationComposition,
            storeId: PersistentStoreId,
            backend: PersistentRecordBackend
        ): PersistentKnowledgeOpenResult = when (
            val opened = PersistentRecordStore.open(foundation, storeId, backend)
        ) {
            is PersistentStoreOpenResult.Opened -> restoreOpened(foundation, opened.store)
            PersistentStoreOpenResult.Corrupt -> PersistentKnowledgeOpenResult.Corrupt
            is PersistentStoreOpenResult.Incompatible ->
                PersistentKnowledgeOpenResult.Incompatible(opened.reason)
            is PersistentStoreOpenResult.Failed ->
                PersistentKnowledgeOpenResult.Failed(
                    reason = "persistent knowledge backend open failed",
                    throwable = opened.throwable
                )
        }

        private fun restoreOpened(
            foundation: FoundationComposition,
            persistentStore: PersistentRecordStore
        ): PersistentKnowledgeOpenResult {
            val restoredEntries = mutableListOf<KnowledgeItemSnapshot>()
            for (snapshot in persistentStore.snapshotEntries()) {
                when (val decoded = KnowledgePersistentRecordCodec.decode(snapshot.record)) {
                    is KnowledgePersistentDecodeResult.Decoded -> restoredEntries += KnowledgeItemSnapshot(
                        item = decoded.item,
                        generation = KnowledgeGeneration(snapshot.generation.value)
                    )

                    KnowledgePersistentDecodeResult.Corrupt -> return PersistentKnowledgeOpenResult.Corrupt
                    is KnowledgePersistentDecodeResult.Incompatible ->
                        return PersistentKnowledgeOpenResult.Incompatible(decoded.reason)
                }
            }

            return when (
                val restored = KnowledgeStore.restore(
                    observability = foundation.observability,
                    entries = restoredEntries,
                    highWatermark = persistentStore.generationHighWatermark()
                )
            ) {
                is KnowledgeRestorationResult.Restored -> PersistentKnowledgeOpenResult.Opened(
                    PersistentKnowledgeComposition(
                        foundation = foundation,
                        persistentStore = persistentStore,
                        knowledgeStore = restored.store
                    )
                )

                is KnowledgeRestorationResult.Rejected ->
                    PersistentKnowledgeOpenResult.RestorationFailed(restored.reason)
            }
        }
    }
}
