package pro.liliya.core.cognitive

import pro.liliya.core.protectedmodel.LargeProtectedModelStagedSourceOwnership

/**
 * Narrow production publication seam for an already staged protected-model source.
 *
 * Callers can supply only the exact staged-source ownership boundary. They cannot inject a loaded
 * engine, a raw path/File, a protected-model authorization ticket, or an arbitrary Runtime binding.
 * Exact staging ownership and protected-model publication are revalidated by the composition-owned
 * activation path before any Runtime session becomes authoritative.
 */
fun interface CognitiveStagedModelActivationPort {
    fun activate(
        ownership: LargeProtectedModelStagedSourceOwnership
    ): CognitiveModelActivationResult
}

/**
 * Exposes the composition-owned staged activation entry without widening the internal activation
 * implementation or its authorization/engine ownership details.
 */
fun CognitiveModelRuntimeComposition.stagedModelActivationPort(): CognitiveStagedModelActivationPort =
    CognitiveStagedModelActivationPort { ownership ->
        activateStagedModel(ownership)
    }
