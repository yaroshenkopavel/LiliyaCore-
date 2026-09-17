package pro.liliya.core.memory

import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentGeneration
import pro.liliya.core.persistence.PersistentInstallResult
import pro.liliya.core.persistence.PersistentRecordBackend
import pro.liliya.core.persistence.PersistentRecordStore
import pro.liliya.core.persistence.PersistentRecordTransitionResult
import pro.liliya.core.persistence.PersistentStoreId
import pro.liliya.core.persistence.PersistentStoreOpenResult

sealed interface PersistentMemoryRetentionMultiTargetPrepareResult {
    data class Prepared(val snapshot: MemoryRetentionMultiTargetSnapshot) :
        PersistentMemoryRetentionMultiTargetPrepareResult
    data class Rejected(val reason: String) : PersistentMemoryRetentionMultiTargetPrepareResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        PersistentMemoryRetentionMultiTargetPrepareResult
}

sealed interface PersistentMemoryRetentionMultiTargetProgressResult {
    data class Committed(val snapshot: MemoryRetentionMultiTargetSnapshot) :
        PersistentMemoryRetentionMultiTargetProgressResult
    data class Rejected(val reason: String) : PersistentMemoryRetentionMultiTargetProgressResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        PersistentMemoryRetentionMultiTargetProgressResult
}

sealed interface PersistentMemoryRetentionMultiTargetJournalOpenResult {
    data class Opened(val journal: PersistentMemoryRetentionMultiTargetJournal) :
        PersistentMemoryRetentionMultiTargetJournalOpenResult
    data object Corrupt : PersistentMemoryRetentionMultiTargetJournalOpenResult
    data class Incompatible(val reason: String) : PersistentMemoryRetentionMultiTargetJournalOpenResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        PersistentMemoryRetentionMultiTargetJournalOpenResult
}

class PersistentMemoryRetentionMultiTargetJournal private constructor(
    private val store: PersistentRecordStore
) {
    @Synchronized
    fun prepare(plan: MemoryRetentionMultiTargetPlan): PersistentMemoryRetentionMultiTargetPrepareResult {
        val record = MemoryRetentionMultiTargetPersistentCodec.encode(
            plan = plan,
            nextIndex = 0,
            state = MemoryRetentionMultiTargetState.ACTIVE
        )
        return when (val installed = store.install(record)) {
            is PersistentInstallResult.Installed ->
                PersistentMemoryRetentionMultiTargetPrepareResult.Prepared(
                    MemoryRetentionMultiTargetSnapshot(
                        plan = plan,
                        generation = MemoryRetentionMultiTargetGeneration(
                            installed.ownership.generation.value
                        ),
                        nextIndex = 0,
                        state = MemoryRetentionMultiTargetState.ACTIVE
                    )
                )
            is PersistentInstallResult.Rejected ->
                PersistentMemoryRetentionMultiTargetPrepareResult.Rejected(installed.reason)
            is PersistentInstallResult.Failed ->
                PersistentMemoryRetentionMultiTargetPrepareResult.Failed(
                    "memory retention multi-target durable prepare failed",
                    installed.throwable
                )
        }
    }

    @Synchronized
    fun advance(
        reference: MemoryRetentionMultiTargetReference,
        expectedIndex: Int
    ): PersistentMemoryRetentionMultiTargetProgressResult = transition(
        reference = reference,
        expectedIndex = expectedIndex,
        reject = false
    )

    @Synchronized
    fun reject(
        reference: MemoryRetentionMultiTargetReference,
        expectedIndex: Int
    ): PersistentMemoryRetentionMultiTargetProgressResult = transition(
        reference = reference,
        expectedIndex = expectedIndex,
        reject = true
    )

    fun inspect(id: MemoryRetentionMultiTargetId): MemoryRetentionMultiTargetSnapshot? {
        val record = store.inspect(
            PersistentEntityId(MemoryRetentionMultiTargetPersistentCodec.entityId(id))
        ) ?: return null
        val decoded = MemoryRetentionMultiTargetPersistentCodec.decode(record.record)
            as? MemoryRetentionMultiTargetPersistentDecodeResult.Decoded ?: return null
        return MemoryRetentionMultiTargetSnapshot(
            plan = decoded.plan,
            generation = MemoryRetentionMultiTargetGeneration(record.generation.value),
            nextIndex = decoded.nextIndex,
            state = decoded.state
        )
    }

    fun snapshotEntries(): List<MemoryRetentionMultiTargetSnapshot> = store.snapshotEntries().mapNotNull {
        val decoded = MemoryRetentionMultiTargetPersistentCodec.decode(it.record)
            as? MemoryRetentionMultiTargetPersistentDecodeResult.Decoded ?: return@mapNotNull null
        MemoryRetentionMultiTargetSnapshot(
            plan = decoded.plan,
            generation = MemoryRetentionMultiTargetGeneration(it.generation.value),
            nextIndex = decoded.nextIndex,
            state = decoded.state
        )
    }.sortedWith(compareBy({ it.plan.createdAt }, { it.plan.id.value }))

    private fun transition(
        reference: MemoryRetentionMultiTargetReference,
        expectedIndex: Int,
        reject: Boolean
    ): PersistentMemoryRetentionMultiTargetProgressResult {
        val entityId = PersistentEntityId(
            MemoryRetentionMultiTargetPersistentCodec.entityId(reference.id)
        )
        val current = store.inspect(entityId)
            ?: return PersistentMemoryRetentionMultiTargetProgressResult.Rejected(
                "memory retention multi-target is not live"
            )
        if (current.generation.value != reference.generation.value) {
            return PersistentMemoryRetentionMultiTargetProgressResult.Rejected(
                "memory retention multi-target generation is stale"
            )
        }
        val decoded = when (val result = MemoryRetentionMultiTargetPersistentCodec.decode(current.record)) {
            is MemoryRetentionMultiTargetPersistentDecodeResult.Decoded -> result
            MemoryRetentionMultiTargetPersistentDecodeResult.Corrupt ->
                return PersistentMemoryRetentionMultiTargetProgressResult.Failed(
                    "memory retention multi-target became corrupt"
                )
            is MemoryRetentionMultiTargetPersistentDecodeResult.Incompatible ->
                return PersistentMemoryRetentionMultiTargetProgressResult.Failed(result.reason)
        }
        if (decoded.plan.id != reference.id) {
            return PersistentMemoryRetentionMultiTargetProgressResult.Rejected(
                "memory retention multi-target identity is stale"
            )
        }
        if (decoded.state != MemoryRetentionMultiTargetState.ACTIVE) {
            return PersistentMemoryRetentionMultiTargetProgressResult.Rejected(
                "memory retention multi-target is not active"
            )
        }
        if (decoded.nextIndex != expectedIndex) {
            return PersistentMemoryRetentionMultiTargetProgressResult.Rejected(
                "memory retention multi-target progress is stale"
            )
        }

        val nextIndex: Int
        val nextState: MemoryRetentionMultiTargetState
        if (reject) {
            nextIndex = decoded.nextIndex
            nextState = MemoryRetentionMultiTargetState.REJECTED
        } else {
            nextIndex = decoded.nextIndex + 1
            nextState = if (nextIndex == decoded.plan.targets.size) {
                MemoryRetentionMultiTargetState.COMPLETED
            } else {
                MemoryRetentionMultiTargetState.ACTIVE
            }
        }

        val replacement = MemoryRetentionMultiTargetPersistentCodec.encode(
            decoded.plan,
            nextIndex,
            nextState
        )
        return when (
            val transitioned = store.transitionExact(
                sourceId = entityId,
                sourceGeneration = PersistentGeneration(reference.generation.value),
                replacement = replacement
            )
        ) {
            is PersistentRecordTransitionResult.Committed ->
                PersistentMemoryRetentionMultiTargetProgressResult.Committed(
                    MemoryRetentionMultiTargetSnapshot(
                        plan = decoded.plan,
                        generation = reference.generation,
                        nextIndex = nextIndex,
                        state = nextState
                    )
                )
            is PersistentRecordTransitionResult.Rejected ->
                PersistentMemoryRetentionMultiTargetProgressResult.Rejected(transitioned.reason)
            is PersistentRecordTransitionResult.Failed ->
                PersistentMemoryRetentionMultiTargetProgressResult.Failed(
                    "memory retention multi-target durable progress failed",
                    transitioned.throwable
                )
        }
    }

    companion object {
        fun open(
            foundation: FoundationComposition,
            storeId: PersistentStoreId,
            backend: PersistentRecordBackend
        ): PersistentMemoryRetentionMultiTargetJournalOpenResult = when (
            val opened = PersistentRecordStore.open(foundation, storeId, backend)
        ) {
            is PersistentStoreOpenResult.Opened -> {
                for (entry in opened.store.snapshotEntries()) {
                    when (val decoded = MemoryRetentionMultiTargetPersistentCodec.decode(entry.record)) {
                        MemoryRetentionMultiTargetPersistentDecodeResult.Corrupt ->
                            return PersistentMemoryRetentionMultiTargetJournalOpenResult.Corrupt
                        is MemoryRetentionMultiTargetPersistentDecodeResult.Incompatible ->
                            return PersistentMemoryRetentionMultiTargetJournalOpenResult.Incompatible(
                                decoded.reason
                            )
                        is MemoryRetentionMultiTargetPersistentDecodeResult.Decoded -> Unit
                    }
                }
                PersistentMemoryRetentionMultiTargetJournalOpenResult.Opened(
                    PersistentMemoryRetentionMultiTargetJournal(opened.store)
                )
            }
            PersistentStoreOpenResult.Corrupt ->
                PersistentMemoryRetentionMultiTargetJournalOpenResult.Corrupt
            is PersistentStoreOpenResult.Incompatible ->
                PersistentMemoryRetentionMultiTargetJournalOpenResult.Incompatible(opened.reason)
            is PersistentStoreOpenResult.Failed ->
                PersistentMemoryRetentionMultiTargetJournalOpenResult.Failed(
                    "memory retention multi-target backend open failed",
                    opened.throwable
                )
        }
    }
}
