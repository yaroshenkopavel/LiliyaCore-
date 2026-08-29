package pro.liliya.core.learning

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@JvmInline
value class LearningApplicationReservationGeneration(val value: Long) {
    init { require(value > 0L) { "learning application reservation generation must be positive" } }
    override fun toString(): String = value.toString()
}

data class LearningApplicationReservationSnapshot(
    val authorization: LearningApplicationAuthorizationReceipt,
    val generation: LearningApplicationReservationGeneration
)

interface LearningApplicationReservationOwnership {
    val authorization: LearningApplicationAuthorizationReceipt
    val generation: LearningApplicationReservationGeneration
    fun release(): Boolean
}

sealed interface LearningApplicationReserveResult {
    data class Reserved(
        val ownership: LearningApplicationReservationOwnership
    ) : LearningApplicationReserveResult

    data class Rejected(
        val reason: String
    ) : LearningApplicationReserveResult
}

class LearningApplicationReservationRegistry {
    private data class Entry(
        val authorization: LearningApplicationAuthorizationReceipt,
        val generation: LearningApplicationReservationGeneration
    )

    private val nextGeneration = AtomicLong(0L)
    private val entries = ConcurrentHashMap<LearningApplicationIntentReference, Entry>()

    fun reserve(authorization: LearningApplicationAuthorizationReceipt): LearningApplicationReserveResult {
        val key = authorization.preflight.application
        val entry = Entry(
            authorization = authorization,
            generation = LearningApplicationReservationGeneration(nextGeneration.incrementAndGet())
        )
        val existing = entries.putIfAbsent(key, entry)
        if (existing != null) {
            return LearningApplicationReserveResult.Rejected(
                "learning application ${key.applicationId} generation ${key.generation} is already reserved"
            )
        }

        return LearningApplicationReserveResult.Reserved(
            ownership = object : LearningApplicationReservationOwnership {
                override val authorization: LearningApplicationAuthorizationReceipt = entry.authorization
                override val generation: LearningApplicationReservationGeneration = entry.generation

                override fun release(): Boolean = entries.remove(key, entry)
            }
        )
    }

    fun inspect(reference: LearningApplicationIntentReference): LearningApplicationReservationSnapshot? =
        entries[reference]?.let { entry ->
            LearningApplicationReservationSnapshot(entry.authorization, entry.generation)
        }

    fun contains(reference: LearningApplicationIntentReference): Boolean = entries.containsKey(reference)

    fun snapshotEntries(): List<LearningApplicationReservationSnapshot> = entries
        .values
        .map { entry -> LearningApplicationReservationSnapshot(entry.authorization, entry.generation) }
        .sortedWith(
            compareBy<LearningApplicationReservationSnapshot>(
                { it.authorization.preflight.application.applicationId.value },
                { it.authorization.preflight.application.generation.value },
                { it.generation.value }
            )
        )
}
