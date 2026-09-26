package pro.liliya.core.semantic

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@JvmInline
value class SemanticClaimConflictGroupId(val value: String) {
    init { require(value.isNotBlank()) { "semantic conflict-group id must not be blank" } }
    override fun toString(): String = value
}

object SemanticClaimIds {
    fun forClaim(
        identity: SemanticClaimIdentity,
        objectValue: SemanticClaimObject
    ): SemanticClaimId {
        val digest = MessageDigest.getInstance("SHA-256")
        putIdentity(digest, identity)
        putObject(digest, objectValue)
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        return SemanticClaimId("claim-$hex")
    }

    fun forConflictGroup(identity: SemanticClaimIdentity): SemanticClaimConflictGroupId {
        val digest = MessageDigest.getInstance("SHA-256")
        putIdentity(digest, identity)
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        return SemanticClaimConflictGroupId("claim-group-$hex")
    }

    private fun putIdentity(digest: MessageDigest, identity: SemanticClaimIdentity) {
        put(digest, identity.subject.namespace)
        put(digest, identity.subject.id)
        put(digest, identity.predicate)
    }

    private fun putObject(digest: MessageDigest, value: SemanticClaimObject) {
        when (value) {
            is SemanticClaimObject.Entity -> {
                put(digest, "entity")
                put(digest, value.reference.namespace)
                put(digest, value.reference.id)
            }
            is SemanticClaimObject.Text -> {
                put(digest, "text")
                put(digest, value.value)
            }
            is SemanticClaimObject.Number -> {
                put(digest, "number")
                put(digest, value.canonical)
            }
            is SemanticClaimObject.BooleanValue -> {
                put(digest, "boolean")
                put(digest, value.value.toString())
            }
        }
    }

    private fun put(digest: MessageDigest, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
}
