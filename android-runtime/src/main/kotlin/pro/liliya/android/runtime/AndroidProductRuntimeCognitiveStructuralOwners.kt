package pro.liliya.android.runtime

import java.time.Clock
import java.util.UUID
import pro.liliya.core.cognitive.CognitiveArtifactIdKind
import pro.liliya.core.cognitive.CognitiveArtifactIdSource
import pro.liliya.core.cognitive.CognitiveRuntimeLimits
import pro.liliya.core.cognitive.CognitiveStructuredResponseBudgets
import pro.liliya.core.cognitive.CognitiveTimestampSource
import pro.liliya.core.cognitive.StructuredCognitiveMaterializationPort
import pro.liliya.core.cognitive.StructuredCognitiveOutcomeMaterializationPort

data class AndroidProductRuntimeCognitiveStructuralOwners(
    val cognitiveMaterialization: StructuredCognitiveMaterializationPort,
    val outcomeMaterialization: StructuredCognitiveOutcomeMaterializationPort,
    val artifactIds: CognitiveArtifactIdSource,
    val timestamps: CognitiveTimestampSource
)

sealed interface AndroidProductRuntimeCognitiveStructuralOwnersResult {
    data class Ready(
        val owners: AndroidProductRuntimeCognitiveStructuralOwners
    ) : AndroidProductRuntimeCognitiveStructuralOwnersResult

    data object Rejected : AndroidProductRuntimeCognitiveStructuralOwnersResult
}

/**
 * Builds the product-owned structural cognition helpers that already have frozen Core semantics.
 *
 * Structural Owners != Learning Governance.
 * Structural Owners != Learning Application Materialization.
 * Structural Owners != Authority.
 * Structural Owners != Inference.
 *
 * Artifact identifiers contain only a structural kind prefix and a random 128-bit token. They
 * never embed user input, model output, policy text or durable content.
 */
object AndroidProductRuntimeCognitiveStructuralOwnersFactory {
    private const val RANDOM_TOKEN_CHARS = 32

    fun create(
        limits: CognitiveRuntimeLimits
    ): AndroidProductRuntimeCognitiveStructuralOwnersResult =
        create(
            limits = limits,
            clock = Clock.systemUTC(),
            tokenSource = {
                UUID.randomUUID().toString().replace("-", "")
            }
        )

    internal fun create(
        limits: CognitiveRuntimeLimits,
        clock: Clock,
        tokenSource: () -> String
    ): AndroidProductRuntimeCognitiveStructuralOwnersResult {
        val longestKindPrefix = CognitiveArtifactIdKind.values()
            .maxOf { idPrefix(it).length }
        if (
            longestKindPrefix + 1 + RANDOM_TOKEN_CHARS >
            limits.maxGeneratedArtifactIdChars
        ) {
            return AndroidProductRuntimeCognitiveStructuralOwnersResult.Rejected
        }

        val responseBudgets = CognitiveStructuredResponseBudgets.from(limits)
        val ids = CognitiveArtifactIdSource { kind ->
            val token = tokenSource()
            require(
                token.length == RANDOM_TOKEN_CHARS &&
                    token.all { it in '0'..'9' || it in 'a'..'f' }
            ) { "cognitive artifact id token must be lowercase 128-bit hex" }

            val value = idPrefix(kind) + "-" + token
            require(value.length <= limits.maxGeneratedArtifactIdChars) {
                "cognitive artifact id exceeds configured bound"
            }
            value
        }

        return AndroidProductRuntimeCognitiveStructuralOwnersResult.Ready(
            AndroidProductRuntimeCognitiveStructuralOwners(
                cognitiveMaterialization =
                    StructuredCognitiveMaterializationPort(responseBudgets),
                outcomeMaterialization =
                    StructuredCognitiveOutcomeMaterializationPort(responseBudgets),
                artifactIds = ids,
                timestamps = CognitiveTimestampSource(clock::instant)
            )
        )
    }

    private fun idPrefix(kind: CognitiveArtifactIdKind): String =
        kind.name.lowercase().replace('_', '-')
}
