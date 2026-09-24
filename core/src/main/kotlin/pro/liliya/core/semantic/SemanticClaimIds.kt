package pro.liliya.core.semantic

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object SemanticClaimIds {
    fun forIdentity(identity: SemanticClaimIdentity): SemanticClaimId {
        val digest = MessageDigest.getInstance("SHA-256")
        put(digest, identity.subject.namespace)
        put(digest, identity.subject.id)
        put(digest, identity.predicate)
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        return SemanticClaimId("claim-$hex")
    }

    private fun put(digest: MessageDigest, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
}
