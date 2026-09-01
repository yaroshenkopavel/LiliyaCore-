package pro.liliya.core.cognitive

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import pro.liliya.core.learning.LearningApplicationTarget
import pro.liliya.core.learning.LearningCandidateReference

internal const val COGNITIVE_RUNTIME_SOURCE_ID = "cognitive-runtime"
internal const val COGNITIVE_RUNTIME_RESULT_SOURCE_ID = "cognitive-runtime-result"
internal const val COGNITIVE_RUNTIME_LEARNING_SOURCE_ID = "cognitive-runtime-learning"

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

@JvmInline
internal value class CognitiveLearningIdempotencyToken(val value: String) {
    init { require(value.isNotBlank()) { "cognitive learning idempotency token must not be blank" } }
    override fun toString(): String = "CognitiveLearningIdempotencyToken([redacted])"
}

@JvmInline
internal value class CognitiveLearningMutationProvenanceToken(val value: String) {
    init { require(value.isNotBlank()) { "cognitive learning mutation provenance token must not be blank" } }
    override fun toString(): String = "CognitiveLearningMutationProvenanceToken([redacted])"
}

internal object CognitiveProvenance {
    private const val TURN_ID_DOMAIN = "liliya-cognitive-turn-id-v1"
    private const val TURN_DOMAIN = "liliya-cognitive-turn-v1"
    private const val RESULT_DOMAIN = "liliya-cognitive-result-v1"
    private const val LEARNING_IDEMPOTENCY_DOMAIN = "liliya-cognitive-learning-idempotency-v1"
    private const val LEARNING_MUTATION_DOMAIN = "liliya-cognitive-learning-mutation-v1"

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

    fun learningIdempotencyToken(
        scope: CognitiveRuntimeScopeId,
        candidate: LearningCandidateReference,
        target: LearningApplicationTarget
    ): CognitiveLearningIdempotencyToken = CognitiveLearningIdempotencyToken(
        digest(
            domain = LEARNING_IDEMPOTENCY_DOMAIN,
            fields = listOf(
                scope.value,
                candidate.candidateId.value,
                candidate.generation.value.toString(),
                target.name
            )
        )
    )

    fun learningMutationToken(
        scope: CognitiveRuntimeScopeId,
        candidate: LearningCandidateReference,
        target: LearningApplicationTarget
    ): CognitiveLearningMutationProvenanceToken = CognitiveLearningMutationProvenanceToken(
        digest(
            domain = LEARNING_MUTATION_DOMAIN,
            fields = listOf(
                scope.value,
                candidate.candidateId.value,
                candidate.generation.value.toString(),
                target.name
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
