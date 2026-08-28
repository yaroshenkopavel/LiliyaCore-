package pro.liliya.core.personality

import java.time.Instant
import pro.liliya.core.identity.SelfGeneration
import pro.liliya.core.identity.SelfIdentityId

@JvmInline
value class PersonalityProfileId(val value: String) {
    init {
        require(value.isNotBlank()) { "personality profile id must not be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
value class PersonalityAttributeKey(val value: String) {
    init {
        require(value.isNotBlank()) { "personality attribute key must not be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
value class PersonalityAttributeValue(val value: String) {
    init {
        require(value.isNotBlank()) { "personality attribute value must not be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
value class PersonalitySourceId(val value: String) {
    init {
        require(value.isNotBlank()) { "personality source id must not be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
value class PersonalitySourceReference(val value: String) {
    init {
        require(value.isNotBlank()) { "personality source reference must not be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
value class PersonalityGeneration(val value: Long) {
    init {
        require(value > 0L) { "personality generation must be positive" }
    }

    override fun toString(): String = value.toString()
}

data class PersonalityAttribute(
    val key: PersonalityAttributeKey,
    val value: PersonalityAttributeValue
)

sealed interface PersonalityTarget {
    data class Self(
        val identityId: SelfIdentityId,
        val generation: SelfGeneration
    ) : PersonalityTarget
}

data class PersonalityProvenance(
    val sourceId: PersonalitySourceId,
    val sourceReference: PersonalitySourceReference? = null
)

class PersonalityProfile(
    val id: PersonalityProfileId,
    val target: PersonalityTarget,
    attributes: List<PersonalityAttribute>,
    val provenance: PersonalityProvenance,
    val createdAt: Instant
) {
    val attributes: List<PersonalityAttribute> = attributes.toList()

    init {
        require(this.attributes.isNotEmpty()) { "personality profile must contain at least one attribute" }
        require(this.attributes.map { it.key }.distinct().size == this.attributes.size) {
            "personality profile attribute keys must be unique"
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (
            other is PersonalityProfile &&
                id == other.id &&
                target == other.target &&
                attributes == other.attributes &&
                provenance == other.provenance &&
                createdAt == other.createdAt
            )

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + target.hashCode()
        result = 31 * result + attributes.hashCode()
        result = 31 * result + provenance.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }

    override fun toString(): String =
        "PersonalityProfile(id=$id, target=$target, attributeCount=${attributes.size}, provenance=$provenance, createdAt=$createdAt)"
}

data class PersonalityProfileSnapshot(
    val profile: PersonalityProfile,
    val generation: PersonalityGeneration
)
