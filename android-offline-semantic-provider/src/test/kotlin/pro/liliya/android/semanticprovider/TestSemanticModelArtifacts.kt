package pro.liliya.android.semanticprovider

import java.io.File

internal object TestSemanticModelArtifacts {
    fun validated(file: File): ValidatedSemanticModelArtifact = ValidatedSemanticModelArtifact(
        file = file,
        spec = SemanticModelArtifactSpec(
            SemanticModelArtifactIdentity(
                profileId = SemanticModelProfileV01.PROFILE_ID,
                profileGeneration = SemanticModelProfileV01.PROFILE_GENERATION,
                upstreamModelRepository = SemanticModelProfileV01.UPSTREAM_MODEL_REPOSITORY,
                upstreamModelRevision = SemanticModelProfileV01.UPSTREAM_MODEL_REVISION,
                conversionProvenance = SemanticConversionProvenance.Reproducible(
                    artifactRepository = "test/reproducible-semantic-artifact",
                    artifactRevision = "test-artifact-revision",
                    conversionToolRevision = "test-conversion-tool-revision"
                ),
                modelFileName = SemanticModelProfileV01.ONNX_FILE_NAME,
                modelFormat = SemanticModelFormat.ONNX,
                expectedSizeBytes = 1L,
                expectedSha256 = "0".repeat(64),
                architecture = SemanticModelArchitecture.BERT,
                embeddingDimension = SemanticModelProfileV01.EMBEDDING_DIMENSION,
                poolingType = SemanticPoolingType.MEAN,
                normalizationRule = SemanticNormalizationRule.L2,
                tokenizerProfileId = SemanticModelProfileV01.TOKENIZER_PROFILE_ID,
                runtimeIdentity = SemanticModelProfileV01.RUNTIME_IDENTITY
            )
        )
    )
}
