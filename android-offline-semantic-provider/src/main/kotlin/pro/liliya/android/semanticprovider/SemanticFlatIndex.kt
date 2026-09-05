package pro.liliya.android.semanticprovider

import java.nio.charset.StandardCharsets
import java.util.PriorityQueue
import kotlin.math.abs
import pro.liliya.core.knowledge.KnowledgeGeneration
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryRecordId

internal enum class SemanticIndexDomain {
    MEMORY,
    KNOWLEDGE
}

@JvmInline
internal value class SemanticProfileGeneration(val value: Long) {
    init {
        require(value > 0L) { "semantic profile generation must be positive" }
    }
}

internal sealed interface SemanticIndexSourceReference {
    val domain: SemanticIndexDomain
    val generationValue: Long
    fun stableIdUtf8(): ByteArray

    class Memory(
        val id: MemoryRecordId,
        val generation: MemoryGeneration
    ) : SemanticIndexSourceReference {
        override val domain: SemanticIndexDomain = SemanticIndexDomain.MEMORY
        override val generationValue: Long get() = generation.value
        override fun stableIdUtf8(): ByteArray = id.value.toByteArray(StandardCharsets.UTF_8)
        override fun equals(other: Any?): Boolean =
            other is Memory && id == other.id && generation == other.generation
        override fun hashCode(): Int = 31 * id.hashCode() + generation.hashCode()
        override fun toString(): String =
            "SemanticIndexSourceReference.Memory(id=<redacted>, generation=$generation)"
    }

    class Knowledge(
        val id: KnowledgeItemId,
        val generation: KnowledgeGeneration
    ) : SemanticIndexSourceReference {
        override val domain: SemanticIndexDomain = SemanticIndexDomain.KNOWLEDGE
        override val generationValue: Long get() = generation.value
        override fun stableIdUtf8(): ByteArray = id.value.toByteArray(StandardCharsets.UTF_8)
        override fun equals(other: Any?): Boolean =
            other is Knowledge && id == other.id && generation == other.generation
        override fun hashCode(): Int = 31 * id.hashCode() + generation.hashCode()
        override fun toString(): String =
            "SemanticIndexSourceReference.Knowledge(id=<redacted>, generation=$generation)"
    }
}

internal class SemanticEmbeddingVector(values: FloatArray) {
    private val value = values.copyOf()

    init {
        require(value.size == DIMENSION) { "semantic embedding dimension must be exactly $DIMENSION" }
        var normSquared = 0.0
        for (component in value) {
            require(component.isFinite()) { "semantic embedding values must be finite" }
            normSquared += component.toDouble() * component.toDouble()
        }
        require(abs(normSquared - 1.0) <= NORMALIZATION_TOLERANCE) {
            "semantic embedding must be L2 normalized"
        }
    }

    fun dot(other: SemanticEmbeddingVector): Double {
        var sum = 0.0
        for (index in value.indices) {
            sum += value[index].toDouble() * other.value[index].toDouble()
        }
        return sum
    }

    fun copyValues(): FloatArray = value.copyOf()

    override fun toString(): String = "SemanticEmbeddingVector(<redacted:$DIMENSION>)"

    companion object {
        const val DIMENSION: Int = 384
        private const val NORMALIZATION_TOLERANCE: Double = 0.001
    }
}

internal data class SemanticFlatIndexLimits(
    val maxMemoryEntries: Int = 10_000,
    val maxKnowledgeEntries: Int = 10_000,
    val maxTotalEntries: Int = 20_000
) {
    init {
        require(maxMemoryEntries > 0)
        require(maxKnowledgeEntries > 0)
        require(maxTotalEntries > 0)
        require(maxTotalEntries <= maxMemoryEntries + maxKnowledgeEntries)
    }
}

internal sealed interface SemanticIndexAddResult {
    data object Indexed : SemanticIndexAddResult
    data object DuplicateExact : SemanticIndexAddResult
    data object EntityAlreadyIndexed : SemanticIndexAddResult
    data object CapacityRejected : SemanticIndexAddResult
}

internal sealed interface SemanticIndexReplaceResult {
    data object Replaced : SemanticIndexReplaceResult
    data object StaleExpected : SemanticIndexReplaceResult
    data object IdentityMismatch : SemanticIndexReplaceResult
    data object NonForwardGeneration : SemanticIndexReplaceResult
}

internal sealed interface SemanticIndexRemoveResult {
    data object Removed : SemanticIndexRemoveResult
    data object StaleOrMissing : SemanticIndexRemoveResult
}

internal data class SemanticRankedCandidate(
    val source: SemanticIndexSourceReference
) {
    override fun toString(): String = "SemanticRankedCandidate(source=$source)"
}

internal class SemanticFlatIndex(
    private val profileGeneration: SemanticProfileGeneration,
    private val limits: SemanticFlatIndexLimits = SemanticFlatIndexLimits()
) {
    private data class Entry(
        val source: SemanticIndexSourceReference,
        val profileGeneration: SemanticProfileGeneration,
        val vector: SemanticEmbeddingVector,
        val stableIdUtf8: ByteArray
    )

    private data class Ranked(
        val source: SemanticIndexSourceReference,
        val similarity: Double,
        val stableIdUtf8: ByteArray
    )

    private val entries = LinkedHashMap<String, Entry>()
    private var memoryEntryCount = 0
    private var knowledgeEntryCount = 0

    @Synchronized
    fun addExact(
        source: SemanticIndexSourceReference,
        vector: SemanticEmbeddingVector
    ): SemanticIndexAddResult {
        val key = entityKey(source)
        val existing = entries[key]
        if (existing != null) {
            return if (existing.source == source) {
                SemanticIndexAddResult.DuplicateExact
            } else {
                SemanticIndexAddResult.EntityAlreadyIndexed
            }
        }
        if (!hasCapacityFor(source.domain)) {
            return SemanticIndexAddResult.CapacityRejected
        }
        entries[key] = Entry(
            source = source,
            profileGeneration = profileGeneration,
            vector = vector,
            stableIdUtf8 = source.stableIdUtf8()
        )
        incrementDomainCount(source.domain)
        return SemanticIndexAddResult.Indexed
    }

    @Synchronized
    fun replaceExact(
        expected: SemanticIndexSourceReference,
        replacement: SemanticIndexSourceReference,
        vector: SemanticEmbeddingVector
    ): SemanticIndexReplaceResult {
        if (!sameEntity(expected, replacement)) {
            return SemanticIndexReplaceResult.IdentityMismatch
        }
        if (replacement.generationValue <= expected.generationValue) {
            return SemanticIndexReplaceResult.NonForwardGeneration
        }
        val key = entityKey(expected)
        val current = entries[key] ?: return SemanticIndexReplaceResult.StaleExpected
        if (current.source != expected) {
            return SemanticIndexReplaceResult.StaleExpected
        }
        current.vector.clear()
        entries[key] = Entry(
            source = replacement,
            profileGeneration = profileGeneration,
            vector = vector,
            stableIdUtf8 = current.stableIdUtf8
        )
        return SemanticIndexReplaceResult.Replaced
    }

    @Synchronized
    fun removeExact(source: SemanticIndexSourceReference): SemanticIndexRemoveResult {
        val key = entityKey(source)
        val current = entries[key] ?: return SemanticIndexRemoveResult.StaleOrMissing
        if (current.source != source) {
            return SemanticIndexRemoveResult.StaleOrMissing
        }
        entries.remove(key)
        current.vector.clear()
        current.stableIdUtf8.fill(0)
        decrementDomainCount(source.domain)
        return SemanticIndexRemoveResult.Removed
    }

    /**
     * Exact flat scan with a bounded live top-K working set.
     *
     * Every matching entry is scored exactly, but only at most maxCandidates Ranked objects are
     * retained in the heap. This avoids materializing/sorting the full domain merely to return K.
     * Final ordering is identical to the canonical deterministic order.
     */
    @Synchronized
    fun rank(
        domain: SemanticIndexDomain,
        query: SemanticEmbeddingVector,
        maxCandidates: Int
    ): List<SemanticRankedCandidate> {
        require(maxCandidates > 0) { "maximum semantic candidates must be positive" }
        val liveDomainEntries = when (domain) {
            SemanticIndexDomain.MEMORY -> memoryEntryCount
            SemanticIndexDomain.KNOWLEDGE -> knowledgeEntryCount
        }

        val bestFirst = Comparator<Ranked> { left, right -> compareRankedBestFirst(left, right) }
        val worstFirst = bestFirst.reversed()
        // maxCandidates is a result bound, not a requirement that the index contain that many
        // entries. Bound the heap's eager allocation by the exact live domain size while still
        // honoring arbitrarily larger positive request bounds without changing discovery semantics.
        val initialHeapCapacity = maxOf(1, minOf(maxCandidates, liveDomainEntries))
        val top = PriorityQueue(initialHeapCapacity, worstFirst)

        for (entry in entries.values) {
            if (entry.source.domain != domain || entry.profileGeneration != profileGeneration) continue
            val ranked = Ranked(
                source = entry.source,
                similarity = query.dot(entry.vector),
                stableIdUtf8 = entry.stableIdUtf8
            )
            if (top.size < maxCandidates) {
                top.add(ranked)
            } else if (bestFirst.compare(ranked, top.peek()) < 0) {
                top.poll()
                top.add(ranked)
            }
        }

        return top
            .toList()
            .sortedWith(bestFirst)
            .map { SemanticRankedCandidate(it.source) }
    }

    @Synchronized
    fun clear() {
        entries.values.forEach { entry ->
            entry.vector.clear()
            entry.stableIdUtf8.fill(0)
        }
        entries.clear()
        memoryEntryCount = 0
        knowledgeEntryCount = 0
    }

    @Synchronized
    fun size(domain: SemanticIndexDomain? = null): Int = when (domain) {
        null -> entries.size
        SemanticIndexDomain.MEMORY -> memoryEntryCount
        SemanticIndexDomain.KNOWLEDGE -> knowledgeEntryCount
    }

    private fun compareRankedBestFirst(left: Ranked, right: Ranked): Int {
        val similarity = right.similarity.compareTo(left.similarity)
        if (similarity != 0) return similarity

        val generation = left.source.generationValue.compareTo(right.source.generationValue)
        if (generation != 0) return generation

        return compareUtf8(left.stableIdUtf8, right.stableIdUtf8)
    }

    private fun hasCapacityFor(domain: SemanticIndexDomain): Boolean {
        if (entries.size >= limits.maxTotalEntries) return false
        return when (domain) {
            SemanticIndexDomain.MEMORY -> memoryEntryCount < limits.maxMemoryEntries
            SemanticIndexDomain.KNOWLEDGE -> knowledgeEntryCount < limits.maxKnowledgeEntries
        }
    }

    private fun incrementDomainCount(domain: SemanticIndexDomain) {
        when (domain) {
            SemanticIndexDomain.MEMORY -> memoryEntryCount += 1
            SemanticIndexDomain.KNOWLEDGE -> knowledgeEntryCount += 1
        }
    }

    private fun decrementDomainCount(domain: SemanticIndexDomain) {
        when (domain) {
            SemanticIndexDomain.MEMORY -> {
                check(memoryEntryCount > 0) { "memory semantic index count underflow" }
                memoryEntryCount -= 1
            }
            SemanticIndexDomain.KNOWLEDGE -> {
                check(knowledgeEntryCount > 0) { "knowledge semantic index count underflow" }
                knowledgeEntryCount -= 1
            }
        }
    }

    private fun sameEntity(
        first: SemanticIndexSourceReference,
        second: SemanticIndexSourceReference
    ): Boolean = entityKey(first) == entityKey(second)

    private fun entityKey(source: SemanticIndexSourceReference): String = when (source) {
        is SemanticIndexSourceReference.Memory -> "M:${source.id.value}"
        is SemanticIndexSourceReference.Knowledge -> "K:${source.id.value}"
    }

    private fun compareUtf8(
        left: ByteArray,
        right: ByteArray
    ): Int {
        val size = minOf(left.size, right.size)
        for (index in 0 until size) {
            val leftByte = left[index].toInt() and 0xff
            val rightByte = right[index].toInt() and 0xff
            if (leftByte != rightByte) return leftByte.compareTo(rightByte)
        }
        return left.size.compareTo(right.size)
    }
}
