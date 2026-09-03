package pro.liliya.android.semanticprovider

internal enum class SemanticModelArchitecture {
    BERT
}

internal enum class SemanticPoolingType {
    MEAN
}

internal enum class SemanticNormalizationRule {
    L2
}

internal sealed interface SemanticConversionProvenance {
    val artifactRepository: String
    val artifactRevision: String

    data class Reproducible(
        override val artifactRepository: String,
        override val artifactRevision: String,
        val conversionToolRevision: String
    ) : SemanticConversionProvenance {
        init {
            require(artifactRepository.isNotBlank())
            require(artifactRevision.isNotBlank())
            require(conversionToolRevision.isNotBlank())
        }
    }

    data class ControlledBenchmark(
        override val artifactRepository: String,
        override val artifactRevision: String
    ) : SemanticConversionProvenance {
        init {
            require(artifactRepository.isNotBlank())
            require(artifactRevision.isNotBlank())
        }
    }
}

internal data class SemanticModelArtifactIdentity(
    val profileId: String,
    val profileGeneration: SemanticProfileGeneration,
    val upstreamModelRepository: String,
    val upstreamModelRevision: String,
    val conversionProvenance: SemanticConversionProvenance,
    val ggufFileName: String,
    val expectedSizeBytes: Long,
    val expectedSha256: String,
    val architecture: SemanticModelArchitecture,
    val embeddingDimension: Int,
    val poolingType: SemanticPoolingType,
    val normalizationRule: SemanticNormalizationRule,
    val tokenizerProfileId: String,
    val llamaCppRevision: String
) {
    init {
        require(profileId.isNotBlank())
        require(upstreamModelRepository.isNotBlank())
        require(upstreamModelRevision.isNotBlank())
        require(ggufFileName.isNotBlank())
        require(!ggufFileName.contains('/') && !ggufFileName.contains('\\'))
        require(expectedSizeBytes > 0L) { "semantic model artifact size must be positive" }
        require(SHA256_PATTERN.matches(expectedSha256)) {
            "semantic model artifact SHA-256 must be lowercase hexadecimal"
        }
        require(embeddingDimension > 0)
        require(tokenizerProfileId.isNotBlank())
        require(llamaCppRevision.isNotBlank())
    }

    val hasReproducibleConversionProvenance: Boolean
        get() = conversionProvenance is SemanticConversionProvenance.Reproducible

    override fun toString(): String =
        "SemanticModelArtifactIdentity(profileId=$profileId, profileGeneration=$profileGeneration, " +
            "provenance=${conversionProvenance::class.simpleName}, expectedSizeBytes=$expectedSizeBytes, " +
            "expectedSha256=<redacted>)"

    private companion object {
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}

/** Exact semantic-profile identity accepted by Offline Semantic Provider v0.1. */
internal object SemanticModelProfileV01 {
    const val PROFILE_ID = "multilingual-e5-small-v0.1"
    const val UPSTREAM_MODEL_REPOSITORY = "intfloat/multilingual-e5-small"
    const val UPSTREAM_MODEL_REVISION = "fd1525a9fd15316a2d503bf26ab031a61d056e98"
    const val TOKENIZER_PROFILE_ID = "multilingual-e5-small:e5-query-passage-v1"
    const val LLAMA_CPP_REVISION = "0f3a71be15af836d277c9f918adfafb45732677e"
    const val EMBEDDING_DIMENSION = 384

    val PROFILE_GENERATION = SemanticProfileGeneration(1)

    fun matches(identity: SemanticModelArtifactIdentity): Boolean =
        identity.profileId == PROFILE_ID &&
            identity.profileGeneration == PROFILE_GENERATION &&
            identity.upstreamModelRepository == UPSTREAM_MODEL_REPOSITORY &&
            identity.upstreamModelRevision == UPSTREAM_MODEL_REVISION &&
            identity.architecture == SemanticModelArchitecture.BERT &&
            identity.embeddingDimension == EMBEDDING_DIMENSION &&
            identity.embeddingDimension == SemanticEmbeddingVector.DIMENSION &&
            identity.poolingType == SemanticPoolingType.MEAN &&
            identity.normalizationRule == SemanticNormalizationRule.L2 &&
            identity.tokenizerProfileId == TOKENIZER_PROFILE_ID &&
            identity.llamaCppRevision == LLAMA_CPP_REVISION
}

/**
 * Exact Q8_0 artifact used only for controlled compatibility/quality/resource evidence.
 *
 * The upstream artifact revision and bytes are pinned, but the third-party conversion does not
 * publish the exact conversion-tool revision. This identity must therefore remain benchmark-only
 * until reproducible conversion provenance is supplied and separately reviewed.
 */
internal object ControlledBenchmarkSemanticModelArtifactV01 {
    const val ARTIFACT_REPOSITORY = "TwinSunsLLC/multilingual-e5-small-gguf"
    const val ARTIFACT_REVISION = "b6cac9615d4ecce28d7f22539b7322d695fc2886"
    const val FILE_NAME = "multilingual-e5-small-q8_0.gguf"
    const val SIZE_BYTES = 132_439_008L
    const val SHA256 = "e011debc1208e31bf7b6aebee2d9fc8bd2ca11694a77ed66ac9d0c9d0a877c93"

    val identity = SemanticModelArtifactIdentity(
        profileId = SemanticModelProfileV01.PROFILE_ID,
        profileGeneration = SemanticModelProfileV01.PROFILE_GENERATION,
        upstreamModelRepository = SemanticModelProfileV01.UPSTREAM_MODEL_REPOSITORY,
        upstreamModelRevision = SemanticModelProfileV01.UPSTREAM_MODEL_REVISION,
        conversionProvenance = SemanticConversionProvenance.ControlledBenchmark(
            artifactRepository = ARTIFACT_REPOSITORY,
            artifactRevision = ARTIFACT_REVISION
        ),
        ggufFileName = FILE_NAME,
        expectedSizeBytes = SIZE_BYTES,
        expectedSha256 = SHA256,
        architecture = SemanticModelArchitecture.BERT,
        embeddingDimension = SemanticModelProfileV01.EMBEDDING_DIMENSION,
        poolingType = SemanticPoolingType.MEAN,
        normalizationRule = SemanticNormalizationRule.L2,
        tokenizerProfileId = SemanticModelProfileV01.TOKENIZER_PROFILE_ID,
        llamaCppRevision = SemanticModelProfileV01.LLAMA_CPP_REVISION
    )
}
