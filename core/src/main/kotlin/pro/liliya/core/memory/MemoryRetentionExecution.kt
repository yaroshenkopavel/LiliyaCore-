package pro.liliya.core.memory

internal fun interface MemoryRetentionMutationPort {
    fun removeExact(
        recordId: MemoryRecordId,
        generation: MemoryGeneration
    ): PersistentMemoryMutationResult
}

internal fun PersistentMemoryComposition.retentionMutationPort(): MemoryRetentionMutationPort =
    MemoryRetentionMutationPort { recordId, generation ->
        val snapshot = inspect(recordId)
            ?: return@MemoryRetentionMutationPort PersistentMemoryMutationResult.Rejected(
                "retention memory record is not live"
            )
        if (snapshot.generation != generation) {
            PersistentMemoryMutationResult.Rejected("retention memory generation is stale")
        } else {
            removeExact(snapshot)
        }
    }

internal fun EncryptedPersistentMemoryComposition.retentionMutationPort(): MemoryRetentionMutationPort =
    MemoryRetentionMutationPort { recordId, generation ->
        val snapshot = inspect(recordId)
            ?: return@MemoryRetentionMutationPort PersistentMemoryMutationResult.Rejected(
                "retention memory record is not live"
            )
        if (snapshot.generation != generation) {
            PersistentMemoryMutationResult.Rejected("retention memory generation is stale")
        } else {
            removeExact(snapshot)
        }
    }
