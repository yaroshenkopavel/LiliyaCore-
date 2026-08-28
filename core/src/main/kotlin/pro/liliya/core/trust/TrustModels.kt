package pro.liliya.core.trust

import java.time.Instant
import pro.liliya.core.identity.SelfGeneration
import pro.liliya.core.identity.SelfIdentityId

@JvmInline
value class TrustAnchorId(val value: String) {
    init {
        require(value.isNotBlank()) { "trust anchor id must not be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
value class TrustSubjectId(val value: String) {
    init {
        require(value.isNotBlank()) { "trust subject id must not be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
value class TrustSourceId(val value: String) {
    init {
        require(value.isNotBlank()) { "trust source id must not be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
value class TrustSourceReference(val value: String) {
    init {
        require(value.isNotBlank()) { "trust source reference must not be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
value class TrustGeneration(val value: Long) {
    init {
        require(value > 0L) { "trust generation must be positive" }
    }

    override fun toString(): String = value.toString()
}

sealed interface TrustSubject {
    data class Self(
        val identityId: SelfIdentityId,
        val generation: SelfGeneration
    ) : TrustSubject

    data class Declared(
        val subjectId: TrustSubjectId
    ) : TrustSubject
}

data class TrustProvenance(
    val sourceId: TrustSourceId,
    val sourceReference: TrustSourceReference? = null
)

data class TrustAnchor(
    val id: TrustAnchorId,
    val subject: TrustSubject,
    val provenance: TrustProvenance,
    val createdAt: Instant
)

data class TrustAnchorSnapshot(
    val anchor: TrustAnchor,
    val generation: TrustGeneration
)
