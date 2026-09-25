package pro.liliya.core.knowledge

import pro.liliya.core.encryption.CognitiveDekReference
import pro.liliya.core.encryption.CognitiveEncryptionFailureCategory
import pro.liliya.core.encryption.CognitiveEncryptionResult
import pro.liliya.core.encryption.CognitivePersistentRecordDraft
import pro.liliya.core.encryption.CognitivePlaintext
import pro.liliya.core.encryption.EncryptedPersistentRecordStore
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentMutationResult
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentRecordLookupResult
import pro.liliya.core.persistence.PersistentRecordOwnership

sealed interface EncryptedPersistentKnowledgeInspectResult {
    data object Missing : EncryptedPersistentKnowledgeInspectResult
    data class Found(val snapshot: KnowledgeItemSnapshot) : EncryptedPersistentKnowledgeInspectResult
    data object Corrupt : EncryptedPersistentKnowledgeInspectResult
    data class Incompatible(val reason: String) : EncryptedPersistentKnowledgeInspectResult
    data class EncryptionUnavailable(
        val category: CognitiveEncryptionFailureCategory,
        val throwable: Throwable? = null
    ) : EncryptedPersistentKnowledgeInspectResult {
        override fun toString(): String =
            "EncryptionUnavailable(category=$category, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : EncryptedPersistentKnowledgeInspectResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

sealed interface EncryptedPersistentKnowledgeOpenResult {
    data class Opened(
        val composition: EncryptedPersistentKnowledgeComposition
    ) : EncryptedPersistentKnowledgeOpenResult

    data object Corrupt : EncryptedPersistentKnowledgeOpenResult
    data class Incompatible(val reason: String) : EncryptedPersistentKnowledgeOpenResult
    data class EncryptionUnavailable(
        val category: CognitiveEncryptionFailureCategory
    ) : EncryptedPersistentKnowledgeOpenResult
    data class RestorationFailed(val reason: String) : EncryptedPersistentKnowledgeOpenResult
}

/**
 * Authoritative Knowledge composition over the existing record-level encrypted persistence boundary.
 */
class EncryptedPersistentKnowledgeComposition private constructor(
    private val foundation: FoundationComposition,
    private val encryptedStore: EncryptedPersistentRecordStore,
    private val knowledgeStore: KnowledgeStore,
    private val activeDek: CognitiveDekReference,
    private val indexedLazyMode: Boolean
) {
    @Synchronized
    fun create(item: KnowledgeItem): PersistentKnowledgeCreateResult {
        val encoded = KnowledgePersistentRecordCodec.encode(item)
        val plaintext = encoded.payload.copyBytes()
        val result = try {
            encryptedStore.install(
                CognitivePersistentRecordDraft(
                    id = encoded.id,
                    schemaId = encoded.schemaId,
                    schemaVersion = encoded.schemaVersion,
                    plaintext = CognitivePlaintext(plaintext),
                    createdAt = encoded.createdAt,
                    dek = activeDek
                )
            )
        } finally {
            plaintext.fill(0)
        }

        return when (result) {
            is CognitiveEncryptionResult.Success ->
                installCommittedKnowledge(item, result.value)
            is CognitiveEncryptionResult.Rejected ->
                PersistentKnowledgeCreateResult.Rejected(
                    "encrypted persistent knowledge rejected: ${result.category}"
                )
            is CognitiveEncryptionResult.Failed ->
                PersistentKnowledgeCreateResult.Failed(
                    "encrypted persistent knowledge durable install failed",
                    result.throwable
                )
        }
    }

    fun find(id: KnowledgeItemId): KnowledgeItem? = inspect(id)?.item

    fun inspect(id: KnowledgeItemId): KnowledgeItemSnapshot? {
        if (!indexedLazyMode) return knowledgeStore.inspect(id)
        return when (val inspected = inspectResult(id)) {
            EncryptedPersistentKnowledgeInspectResult.Missing -> null
            is EncryptedPersistentKnowledgeInspectResult.Found -> inspected.snapshot
            EncryptedPersistentKnowledgeInspectResult.Corrupt ->
                throw IllegalStateException("encrypted persistent Knowledge exact read is corrupt")
            is EncryptedPersistentKnowledgeInspectResult.Incompatible ->
                throw IllegalStateException(inspected.reason)
            is EncryptedPersistentKnowledgeInspectResult.EncryptionUnavailable ->
                throw IllegalStateException(
                    "encrypted persistent Knowledge exact read unavailable: ${inspected.category}",
                    inspected.throwable
                )
            is EncryptedPersistentKnowledgeInspectResult.Failed ->
                throw IllegalStateException(inspected.reason, inspected.throwable)
        }
    }

    fun inspectResult(id: KnowledgeItemId): EncryptedPersistentKnowledgeInspectResult {
        val entityId = PersistentEntityId(id.value)
        val snapshot = when (val inspected = encryptedStore.inspectResult(entityId)) {
            PersistentRecordLookupResult.Missing ->
                return EncryptedPersistentKnowledgeInspectResult.Missing
            is PersistentRecordLookupResult.Found -> inspected.snapshot
            PersistentRecordLookupResult.Corrupt ->
                return EncryptedPersistentKnowledgeInspectResult.Corrupt
            is PersistentRecordLookupResult.Incompatible ->
                return EncryptedPersistentKnowledgeInspectResult.Incompatible(inspected.reason)
            is PersistentRecordLookupResult.Failed ->
                return EncryptedPersistentKnowledgeInspectResult.Failed(
                    inspected.reason,
                    inspected.throwable
                )
        }

        val plaintext = when (val opened = encryptedStore.open(snapshot)) {
            is CognitiveEncryptionResult.Success -> opened.value
            is CognitiveEncryptionResult.Rejected ->
                return EncryptedPersistentKnowledgeInspectResult.EncryptionUnavailable(
                    opened.category
                )
            is CognitiveEncryptionResult.Failed ->
                return EncryptedPersistentKnowledgeInspectResult.EncryptionUnavailable(
                    opened.category,
                    opened.throwable
                )
        }
        val bytes = plaintext.copyBytes()
        return try {
            when (
                val decoded = KnowledgePersistentRecordCodec.decode(
                    PersistentRecord(
                        id = snapshot.record.id,
                        schemaId = snapshot.record.schemaId,
                        schemaVersion = snapshot.record.schemaVersion,
                        payload = PersistentPayload(bytes),
                        createdAt = snapshot.record.createdAt
                    )
                )
            ) {
                is KnowledgePersistentDecodeResult.Decoded ->
                    EncryptedPersistentKnowledgeInspectResult.Found(
                        KnowledgeItemSnapshot(
                            decoded.item,
                            KnowledgeGeneration(snapshot.generation.value)
                        )
                    )
                KnowledgePersistentDecodeResult.Corrupt ->
                    EncryptedPersistentKnowledgeInspectResult.Corrupt
                is KnowledgePersistentDecodeResult.Incompatible ->
                    EncryptedPersistentKnowledgeInspectResult.Incompatible(decoded.reason)
            }
        } finally {
            bytes.fill(0)
        }
    }

    fun contains(id: KnowledgeItemId): Boolean = inspect(id) != null
    fun snapshot(): List<KnowledgeItem> = snapshotEntries().map { it.item }

    fun snapshotEntries(): List<KnowledgeItemSnapshot> {
        if (!indexedLazyMode) return knowledgeStore.snapshotEntries()
        val snapshots = when (val result = encryptedStore.decryptedSnapshotEntries()) {
            is CognitiveEncryptionResult.Success -> result.value
            is CognitiveEncryptionResult.Rejected ->
                throw IllegalStateException(
                    "encrypted persistent Knowledge snapshot unavailable: ${result.category}"
                )
            is CognitiveEncryptionResult.Failed ->
                throw IllegalStateException(
                    "encrypted persistent Knowledge snapshot unavailable: ${result.category}",
                    result.throwable
                )
        }
        return snapshots.map { snapshot ->
            when (val decoded = KnowledgePersistentRecordCodec.decode(snapshot.record)) {
                is KnowledgePersistentDecodeResult.Decoded ->
                    KnowledgeItemSnapshot(
                        decoded.item,
                        KnowledgeGeneration(snapshot.generation.value)
                    )
                KnowledgePersistentDecodeResult.Corrupt ->
                    throw IllegalStateException("encrypted persistent Knowledge snapshot is corrupt")
                is KnowledgePersistentDecodeResult.Incompatible ->
                    throw IllegalStateException(decoded.reason)
            }
        }
    }

    private fun installCommittedKnowledge(
        item: KnowledgeItem,
        persistentOwnership: PersistentRecordOwnership
    ): PersistentKnowledgeCreateResult {
        val generation = KnowledgeGeneration(persistentOwnership.generation.value)
        val context = foundation.rootContext(
            operation = "createEncryptedPersistedKnowledge",
            component = "Knowledge",
            metadata = mapOf("knowledgeGeneration" to generation.value.toString())
        )
        return when (
            val local = knowledgeStore.installCommitted(
                item = item,
                generation = generation,
                highWatermark = encryptedStore.generationHighWatermark(),
                context = context
            )
        ) {
            is KnowledgeRegistrationResult.Registered ->
                PersistentKnowledgeCreateResult.Created(
                    ownership(
                        persistentOwnership = persistentOwnership,
                        localRegistration = local.registration,
                        operationContext = context
                    )
                )

            is KnowledgeRegistrationResult.Rejected -> {
                val compensated = persistentOwnership.remove()
                val reason = if (compensated is PersistentMutationResult.Committed) {
                    "local encrypted knowledge install rejected after durable commit; durable candidate compensated"
                } else {
                    "local encrypted knowledge install rejected after durable commit; durable compensation failed"
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

        override fun remove(): PersistentKnowledgeMutationResult =
            synchronized(this@EncryptedPersistentKnowledgeComposition) {
                when (val durable = persistentOwnership.remove()) {
                    PersistentMutationResult.Committed -> {
                        val removedLocally = localRegistration.remove(
                            foundation.childContext(
                                parent = operationContext,
                                component = "Knowledge",
                                operation = "removeEncryptedPersistedKnowledge",
                                metadata = mapOf(
                                    "knowledgeGeneration" to generation.value.toString()
                                )
                            )
                        )
                        if (removedLocally) PersistentKnowledgeMutationResult.Committed
                        else PersistentKnowledgeMutationResult.Failed(
                            "durable encrypted knowledge removal committed but local exact removal failed"
                        )
                    }

                    is PersistentMutationResult.Rejected ->
                        PersistentKnowledgeMutationResult.Rejected(durable.reason)
                    is PersistentMutationResult.Failed ->
                        PersistentKnowledgeMutationResult.Failed(
                            "encrypted persistent knowledge durable removal failed",
                            durable.throwable
                        )
                }
            }
    }

    companion object {
        fun open(
            foundation: FoundationComposition,
            encryptedStore: EncryptedPersistentRecordStore,
            activeDek: CognitiveDekReference
        ): EncryptedPersistentKnowledgeOpenResult {
            if (encryptedStore.supportsIndexedLazyMode()) {
                return when (
                    val restored = KnowledgeStore.restore(
                        observability = foundation.observability,
                        entries = emptyList(),
                        highWatermark = encryptedStore.generationHighWatermark()
                    )
                ) {
                    is KnowledgeRestorationResult.Restored ->
                        EncryptedPersistentKnowledgeOpenResult.Opened(
                            EncryptedPersistentKnowledgeComposition(
                                foundation,
                                encryptedStore,
                                restored.store,
                                activeDek,
                                indexedLazyMode = true
                            )
                        )
                    is KnowledgeRestorationResult.Rejected ->
                        EncryptedPersistentKnowledgeOpenResult.RestorationFailed(restored.reason)
                }
            }

            val restoredEntries = mutableListOf<KnowledgeItemSnapshot>()

            for (snapshot in encryptedStore.snapshotEntries()) {
                val plaintext = when (val opened = encryptedStore.open(snapshot.record.id)) {
                    is CognitiveEncryptionResult.Success -> opened.value
                    is CognitiveEncryptionResult.Rejected ->
                        return EncryptedPersistentKnowledgeOpenResult.EncryptionUnavailable(
                            opened.category
                        )
                    is CognitiveEncryptionResult.Failed ->
                        return EncryptedPersistentKnowledgeOpenResult.EncryptionUnavailable(
                            opened.category
                        )
                }
                val bytes = plaintext.copyBytes()
                val decoded = try {
                    KnowledgePersistentRecordCodec.decode(
                        PersistentRecord(
                            id = snapshot.record.id,
                            schemaId = snapshot.record.schemaId,
                            schemaVersion = snapshot.record.schemaVersion,
                            payload = PersistentPayload(bytes),
                            createdAt = snapshot.record.createdAt
                        )
                    )
                } finally {
                    bytes.fill(0)
                }

                when (decoded) {
                    is KnowledgePersistentDecodeResult.Decoded ->
                        restoredEntries += KnowledgeItemSnapshot(
                            decoded.item,
                            KnowledgeGeneration(snapshot.generation.value)
                        )
                    KnowledgePersistentDecodeResult.Corrupt ->
                        return EncryptedPersistentKnowledgeOpenResult.Corrupt
                    is KnowledgePersistentDecodeResult.Incompatible ->
                        return EncryptedPersistentKnowledgeOpenResult.Incompatible(decoded.reason)
                }
            }

            return when (
                val restored = KnowledgeStore.restore(
                    observability = foundation.observability,
                    entries = restoredEntries,
                    highWatermark = encryptedStore.generationHighWatermark()
                )
            ) {
                is KnowledgeRestorationResult.Restored ->
                    EncryptedPersistentKnowledgeOpenResult.Opened(
                        EncryptedPersistentKnowledgeComposition(
                            foundation,
                            encryptedStore,
                            restored.store,
                            activeDek,
                            indexedLazyMode = false
                        )
                    )
                is KnowledgeRestorationResult.Rejected ->
                    EncryptedPersistentKnowledgeOpenResult.RestorationFailed(restored.reason)
            }
        }
    }
}
