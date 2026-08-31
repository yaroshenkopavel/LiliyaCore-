package pro.liliya.core.encryption

import java.util.concurrent.atomic.AtomicLong

interface CognitiveDekOwnership {
    val reference: CognitiveDekReference
    fun retire(): Boolean
}

sealed interface CognitiveDekRegistrationResult {
    data class Registered(val ownership: CognitiveDekOwnership) : CognitiveDekRegistrationResult
    data class Rejected(val reason: String) : CognitiveDekRegistrationResult
}

class CognitiveDekStore internal constructor(
    initialGeneration: Long = 0L
) {
    private data class Entry(
        val reference: CognitiveDekReference
    )

    private val lock = Any()
    private val nextGeneration = AtomicLong(initialGeneration)
    private val entries = mutableMapOf<CognitiveDekId, Entry>()

    fun register(id: CognitiveDekId): CognitiveDekRegistrationResult = synchronized(lock) {
        if (entries.containsKey(id)) {
            return@synchronized CognitiveDekRegistrationResult.Rejected(
                "cognitive DEK id is already registered"
            )
        }

        val nextValue = nextGeneration.incrementAndGet()
        if (nextValue <= 0L) {
            return@synchronized CognitiveDekRegistrationResult.Rejected(
                "cognitive DEK generation overflow"
            )
        }

        val entry = Entry(
            CognitiveDekReference(
                id = id,
                generation = CognitiveDekGeneration(nextValue)
            )
        )
        entries[id] = entry
        CognitiveDekRegistrationResult.Registered(ownership(entry))
    }

    fun inspect(id: CognitiveDekId): CognitiveDekReference? = synchronized(lock) {
        entries[id]?.reference
    }

    fun snapshot(): List<CognitiveDekReference> = synchronized(lock) {
        entries.values
            .map { it.reference }
            .sortedWith(
                compareBy<CognitiveDekReference> { it.generation.value }
                    .thenBy { it.id.value }
            )
    }

    private fun ownership(entry: Entry): CognitiveDekOwnership =
        object : CognitiveDekOwnership {
            override val reference: CognitiveDekReference = entry.reference

            override fun retire(): Boolean = synchronized(lock) {
                entries[entry.reference.id] === entry &&
                    entries.remove(entry.reference.id) === entry
            }
        }
}
