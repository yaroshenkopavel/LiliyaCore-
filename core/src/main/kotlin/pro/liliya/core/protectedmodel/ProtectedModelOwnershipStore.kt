package pro.liliya.core.protectedmodel

import java.util.concurrent.atomic.AtomicLong

interface ProtectedModelOwnership {
    val reference: ProtectedModelReference
    fun retire(): Boolean
}

sealed interface ProtectedModelRegistrationResult {
    data class Registered(val ownership: ProtectedModelOwnership) : ProtectedModelRegistrationResult
    data class Rejected(val reason: String) : ProtectedModelRegistrationResult
}

class ProtectedModelOwnershipStore internal constructor(
    initialGeneration: Long = 0L
) {
    private data class Entry(val reference: ProtectedModelReference)
    private val lock = Any()
    private val nextGeneration = AtomicLong(initialGeneration)
    private val entries = mutableMapOf<ProtectedModelPackageId, Entry>()

    fun register(id: ProtectedModelPackageId): ProtectedModelRegistrationResult = synchronized(lock) {
        if (entries.containsKey(id)) {
            return@synchronized ProtectedModelRegistrationResult.Rejected("protected model package id is already registered")
        }
        val next = nextGeneration.incrementAndGet()
        if (next <= 0L) {
            return@synchronized ProtectedModelRegistrationResult.Rejected("protected model generation overflow")
        }
        val entry = Entry(ProtectedModelReference(id, ProtectedModelGeneration(next)))
        entries[id] = entry
        ProtectedModelRegistrationResult.Registered(ownership(entry))
    }

    fun inspect(id: ProtectedModelPackageId): ProtectedModelReference? = synchronized(lock) {
        entries[id]?.reference
    }

    fun snapshot(): List<ProtectedModelReference> = synchronized(lock) {
        entries.values.map { it.reference }.sortedWith(
            compareBy<ProtectedModelReference> { it.generation.value }.thenBy { it.packageId.value }
        )
    }

    private fun ownership(entry: Entry): ProtectedModelOwnership = object : ProtectedModelOwnership {
        override val reference: ProtectedModelReference = entry.reference
        override fun retire(): Boolean = synchronized(lock) {
            entries[entry.reference.packageId] === entry && entries.remove(entry.reference.packageId) === entry
        }
    }
}
