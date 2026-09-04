package pro.liliya.android.semanticprovider

import android.os.Bundle

/**
 * Test-only identity selection for an ONNX artifact produced in the same CI run from the exact
 * upstream model revision and the repository-controlled export pipeline.
 *
 * There is intentionally no community/default artifact fallback on the ONNX branch. Real-model
 * instrumentation must receive the exact SHA-256 and byte size of the artifact produced by the
 * current provenance run.
 */
internal object SelfReproducedSemanticModelFixture {
    const val SHA256_ARGUMENT = "semanticFixtureSha256"
    const val SIZE_ARGUMENT = "semanticFixtureSizeBytes"

    data class Selection(
        val identity: SemanticModelArtifactIdentity,
        val acceptance: SemanticModelArtifactAcceptance
    )

    fun select(arguments: Bundle): Selection {
        val sha256 = requireNotNull(
            arguments.getString(SHA256_ARGUMENT)?.trim()?.takeIf { it.isNotEmpty() }
        ) {
            "ONNX semantic fixture requires exact SHA-256"
        }
        val sizeText = requireNotNull(
            arguments.getString(SIZE_ARGUMENT)?.trim()?.takeIf { it.isNotEmpty() }
        ) {
            "ONNX semantic fixture requires exact byte size"
        }
        val sizeBytes = sizeText.toLong()

        return Selection(
            identity = SemanticModelArtifactIdentity(
                profileId = SemanticModelProfileV01.PROFILE_ID,
                profileGeneration = SemanticModelProfileV01.PROFILE_GENERATION,
                upstreamModelRepository = SemanticModelProfileV01.UPSTREAM_MODEL_REPOSITORY,
                upstreamModelRevision = SemanticModelProfileV01.UPSTREAM_MODEL_REVISION,
                conversionProvenance = SemanticConversionProvenance.ReproducibleCiFixture(
                    conversionToolRevision = SemanticModelProfileV01.ONNX_EXPORT_PIPELINE_REVISION
                ),
                modelFileName = SemanticModelProfileV01.ONNX_FILE_NAME,
                modelFormat = SemanticModelFormat.ONNX,
                expectedSizeBytes = sizeBytes,
                expectedSha256 = sha256,
                architecture = SemanticModelArchitecture.BERT,
                embeddingDimension = SemanticModelProfileV01.EMBEDDING_DIMENSION,
                poolingType = SemanticPoolingType.MEAN,
                normalizationRule = SemanticNormalizationRule.L2,
                tokenizerProfileId = SemanticModelProfileV01.TOKENIZER_PROFILE_ID,
                runtimeIdentity = SemanticModelProfileV01.RUNTIME_IDENTITY
            ),
            acceptance = SemanticModelArtifactAcceptance.REPRODUCIBLE_CI_FIXTURE
        )
    }
}
