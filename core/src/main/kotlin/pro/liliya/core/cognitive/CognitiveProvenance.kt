package pro.liliya.core.cognitive

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal const val COGNITIVE_RUNTIME_SOURCE_ID = "cognitive-runtime"
internal const val COGNITIVE_RUNTIME_RESULT_SOURCE_ID = "cognitive-runtime-result"

@JvmInline
internal value class CognitiveTurnProvenanceToken(val value: String) {
    init { require(value.isNotBlank()) { "cognitive turn provenance token must not be blank" } }
    override fun toString(): String = "CognitiveTurnProvenanceToken([redacted])"
}

@JvmInline
internal value class CognitiveResultProvenanceToken(val value: String) {
    init { require(value.isNotBlank()) { "cognitive result provenance token must not be blank" } }
    override fun toString(): String = "CognitiveResultProvenanceToken([redacted])"
}

internal object CognitiveProvenance {
    private const val TURN_ID_DOMAIN = "liliya-cognitive-turn-id-v1"
    private const val TURN_DOMAIN = "liliya-cognitive-turn-v1"
    private const val RESULT_DOMAIN = "liliya-cognitive-result-v1"

    fun requestFingerprint(
        scope: CognitiveRuntimeScopeId,
        id: CognitiveTurnId
    ): String = digest(
        domain = TURN_ID_DOMAIN,
        fields = listOf(scope.value, id.value)
    )

    fun turnToken(
        scope: CognitiveRuntimeScopeId,
        reference: CognitiveTurnReference
    ): CognitiveTurnProvenanceToken = CognitiveTurnProvenanceToken(
        digest(
            domain = TURN_DOMAIN,
            fields = listOf(
                scope.value,
                reference.id.value,
                reference.generation.value.toString()
            )
        )
    )

    fun resultToken(
        scope: CognitiveRuntimeScopeId,
        reference: CognitiveTurnReference,
        decision: DecisionReference
    ): CognitiveResultProvenanceToken = CognitiveResultProvenanceToken(
        digest(
            domain = RESULT_DOMAIN,
            fields = listOf(
                scope.value,
                reference.id.value,
                reference.generation.value.toString(),
                decision.id.value,
                decision.generation.value.toString()
            )
        )
    )

    private fun digest(domain: String, fields: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(domain.toByteArray(StandardCharsets.UTF_8))
        fields.forEach { field ->
            digest.update(0.toByte())
            val bytes = field.toByteArray(StandardCharsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
            digest.update(':'.code.toByte())
            digest.update(bytes)
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
