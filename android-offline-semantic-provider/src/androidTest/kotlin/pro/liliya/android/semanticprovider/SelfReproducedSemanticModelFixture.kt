package pro.liliya.android.semanticprovider

import android.os.Bundle

/**
 * Test-only identity selection for the self-reproduced semantic GGUF acceptance path.
 *
 * Normal instrumentation runs receive no override and therefore keep using the exact immutable
 * TwinSuns controlled benchmark identity. The provenance workflow may supply only SHA-256 + size
 * for bytes produced in the same run from the immutable upstream source and pinned llama.cpp
 * converter. In that mode the identity records the reproducible source/converter chain and uses an
 * explicit ephemeral CI-fixture acceptance mode. It does not claim that the generated GGUF is
 * published at the upstream source repository or accepted as the production artifact.
 */
internal object SelfReproducedSemanticModelFixture {
    const val SHA256_ARGUMENT = "semanticFixtureSha256"
    const val SIZE_ARGUMENT = "semanticFixtureSizeBytes"

    data class Selection(
        val identity: SemanticModelArtifactIdentity,
        val acceptance: SemanticModelArtifactAcceptance
    )

    fun select(arguments: Bundle): Selection {
        val sha256 = arguments.getString(SHA256_ARGUMENT)?.trim()?.takeIf { it.isNotEmpty() }
        val sizeText = arguments.getString(SIZE_ARGUMENT)?.trim()?.takeIf { it.isNotEmpty() }

        if (sha256 == null && sizeText == null) {
            return Selection(
                identity = ControlledBenchmarkSemanticModelArtifactV01.identity,
                acceptance = SemanticModelArtifactAcceptance.CONTROLLED_BENCHMARK
            )
        }

        require(sha256 != null && sizeText != null) {
            "self-reproduced semantic fixture requires both SHA-256 and size"
        }
        val sizeBytes = sizeText.toLong()

        val identity = ControlledBenchmarkSemanticModelArtifactV01.identity.copy(
            conversionProvenance = SemanticConversionProvenance.Reproducible(
                artifactRepository = SemanticModelProfileV01.UPSTREAM_MODEL_REPOSITORY,
                artifactRevision = SemanticModelProfileV01.UPSTREAM_MODEL_REVISION,
                conversionToolRevision = SemanticModelProfileV01.LLAMA_CPP_REVISION
            ),
            expectedSizeBytes = sizeBytes,
            expectedSha256 = sha256
        )

        return Selection(
            identity = identity,
            acceptance = SemanticModelArtifactAcceptance.REPRODUCIBLE_CI_FIXTURE
        )
    }
}
