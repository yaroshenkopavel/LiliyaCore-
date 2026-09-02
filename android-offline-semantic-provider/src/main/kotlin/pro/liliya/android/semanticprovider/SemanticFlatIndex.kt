package pro.liliya.android.semanticprovider

import java.nio.charset.StandardCharsets
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
        val vector: SemanticEmbeddingVector
    )

    private val entries = LinkedHashMap<String, Entry>()

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
        entries[key] = Entry(source, profileGeneration, vector)
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
        entries[key] = Entry(replacement, profileGeneration, vector)
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
        return SemanticIndexRemoveResult.Removed
    }

    @Synchronized
    fun rank(
        domain: SemanticIndexDomain,
        query: SemanticEmbeddingVector,
        maxCandidates: Int
    ): List<SemanticRankedCandidate> {
        require(maxCandidates > 0) { "maximum semantic candidates must be positive" }

        return entries.values
            .asSequence()
            .filter { it.source.domain == domain && it.profileGeneration == profileGeneration }
            .map { entry -> Ranked(entry.source, query.dot(entry.vector)) }
            .sortedWith(
                compareByDescending<Ranked> { it.similarity }
                    .thenBy { it.source.generationValue }
                    .thenComparator { left, right -> compareUtf8(left.source, right.source) }
            )
            .take(maxCandidates)
            .map { SemanticRankedCandidate(it.source) }
            .toList()
    }

    @Synchronized
    fun size(domain: SemanticIndexDomain? = null): Int =
        if (domain == null) entries.size else entries.values.count { it.source.domain == domain }

    private data class Ranked(
        val source: SemanticIndexSourceReference,
        val similarity: Double
    )

    private fun hasCapacityFor(domain: SemanticIndexDomain): Boolean {
        if (entries.size >= limits.maxTotalEntries) return false
        val domainSize = entries.values.count { it.source.domain == domain }
        return when (domain) {
            SemanticIndexDomain.MEMORY -> domainSize < limits.maxMemoryEntries
            SemanticIndexDomain.KNOWLEDGE -> domainSize < limits.maxKnowledgeEntries
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
        left: SemanticIndexSourceReference,
        right: SemanticIndexSourceReference
    ): Int {
        val a = left.stableIdUtf8()
        val b = right.stableIdUtf8()
        val size = minOf(a.size, b.size)
        for (index in 0 until size) {
            val av = a[index].toInt() and 0xff
            val bv = b[index].toInt() and 0xff
            if (av != bv) return av.compareTo(bv)
        }
        return a.size.compareTo(b.size)
    }
}
