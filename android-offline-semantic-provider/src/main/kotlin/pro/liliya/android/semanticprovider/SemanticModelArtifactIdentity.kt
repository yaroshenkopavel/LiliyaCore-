package pro.liliya.android.semanticprovider

internal enum class SemanticModelArchitecture {
    BERT
}

internal enum class SemanticModelFormat {
    ONNX
}

internal enum class SemanticPoolingType {
    MEAN
}

internal enum class SemanticNormalizationRule {
    L2
}

internal data class SemanticRuntimeIdentity(
    val runtimeId: String,
    val runtimeVersion: String,
    val extensionsId: String,
    val extensionsVersion: String
) {
    init {
        require(runtimeId.isNotBlank())
        require(runtimeVersion.isNotBlank())
        require(extensionsId.isNotBlank())
        require(extensionsVersion.isNotBlank())
    }
}

internal sealed interface SemanticConversionProvenance {
    data class Reproducible(
        val artifactRepository: String,
        val artifactRevision: String,
        val conversionToolRevision: String
    ) : SemanticConversionProvenance {
        init {
            require(artifactRepository.isNotBlank())
            require(artifactRevision.isNotBlank())
            require(conversionToolRevision.isNotBlank())
        }
    }

    data class ReproducibleCiFixture(
        val conversionToolRevision: String
    ) : SemanticConversionProvenance {
        init {
            require(conversionToolRevision.isNotBlank())
        }
    }

    data class ControlledBenchmark(
        val artifactRepository: String,
        val artifactRevision: String
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
    val modelFileName: String,
    val modelFormat: SemanticModelFormat,
    val expectedSizeBytes: Long,
    val expectedSha256: String,
    val tokenizerFileName: String = SemanticModelProfileV01.TOKENIZER_ONNX_FILE_NAME,
    val tokenizerExpectedSizeBytes: Long = SemanticModelProfileV01.TOKENIZER_ONNX_SIZE_BYTES,
    val tokenizerExpectedSha256: String = SemanticModelProfileV01.TOKENIZER_ONNX_SHA256,
    val architecture: SemanticModelArchitecture,
    val embeddingDimension: Int,
    val poolingType: SemanticPoolingType,
    val normalizationRule: SemanticNormalizationRule,
    val tokenizerProfileId: String,
    val runtimeIdentity: SemanticRuntimeIdentity
) {
    init {
        require(profileId.isNotBlank())
        require(upstreamModelRepository.isNotBlank())
        require(upstreamModelRevision.isNotBlank())
        require(modelFileName.isNotBlank())
        require(!modelFileName.contains('/') && !modelFileName.contains('\\'))
        require(expectedSizeBytes > 0L) { "semantic model artifact size must be positive" }
        require(SHA256_PATTERN.matches(expectedSha256)) {
            "semantic model artifact SHA-256 must be lowercase hexadecimal"
        }
        require(tokenizerFileName.isNotBlank())
        require(!tokenizerFileName.contains('/') && !tokenizerFileName.contains('\\'))
        require(tokenizerExpectedSizeBytes > 0L) {
            "semantic tokenizer artifact size must be positive"
        }
        require(SHA256_PATTERN.matches(tokenizerExpectedSha256)) {
            "semantic tokenizer artifact SHA-256 must be lowercase hexadecimal"
        }
        require(embeddingDimension > 0)
        require(tokenizerProfileId.isNotBlank())
    }

    override fun toString(): String =
        "SemanticModelArtifactIdentity(profileId=$profileId, profileGeneration=$profileGeneration, " +
            "format=$modelFormat, runtime=${runtimeIdentity.runtimeId}@${runtimeIdentity.runtimeVersion}, " +
            "provenance=${conversionProvenance::class.simpleName}, encoderBytes=$expectedSizeBytes, " +
            "tokenizerBytes=$tokenizerExpectedSizeBytes, digests=<redacted>)"

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
    const val ONNX_RUNTIME_ID = "onnxruntime-android"
    const val ONNX_RUNTIME_VERSION = "1.29.0"
    const val ONNX_EXTENSIONS_ID = "onnxruntime-extensions-android"
    const val ONNX_EXTENSIONS_VERSION = "0.12.4"
    const val ONNX_EXPORT_PIPELINE_REVISION = "liliya-onnx-export-v0.1"
    const val ONNX_FILE_NAME = "multilingual-e5-small-liliya-v0.1.onnx"
    const val ONNX_SIZE_BYTES = 118258170L
    const val ONNX_SHA256 = "11f460a6600163508a6eca0f2ccd8df9272fafbbee10579b3dafa74217b084dc"
    const val TOKENIZER_ONNX_FILE_NAME = "multilingual-e5-small-tokenizer-v0.1.onnx"
    const val TOKENIZER_ONNX_SIZE_BYTES = 5069533L
    const val TOKENIZER_ONNX_SHA256 = "4d28a2a61017a7b222164065d832e51103fbb3a4451c4e4938a2eeb8e83e44e8"
    const val EMBEDDING_DIMENSION = 384
    const val MAX_ARTIFACT_BYTES = 160L * 1024L * 1024L

    val PROFILE_GENERATION = SemanticProfileGeneration(1)

    val RUNTIME_IDENTITY = SemanticRuntimeIdentity(
        runtimeId = ONNX_RUNTIME_ID,
        runtimeVersion = ONNX_RUNTIME_VERSION,
        extensionsId = ONNX_EXTENSIONS_ID,
        extensionsVersion = ONNX_EXTENSIONS_VERSION
    )

    fun matches(identity: SemanticModelArtifactIdentity): Boolean =
        identity.profileId == PROFILE_ID &&
            identity.profileGeneration == PROFILE_GENERATION &&
            identity.upstreamModelRepository == UPSTREAM_MODEL_REPOSITORY &&
            identity.upstreamModelRevision == UPSTREAM_MODEL_REVISION &&
            identity.modelFormat == SemanticModelFormat.ONNX &&
            identity.tokenizerFileName == TOKENIZER_ONNX_FILE_NAME &&
            identity.tokenizerExpectedSizeBytes == TOKENIZER_ONNX_SIZE_BYTES &&
            identity.tokenizerExpectedSha256 == TOKENIZER_ONNX_SHA256 &&
            identity.architecture == SemanticModelArchitecture.BERT &&
            identity.embeddingDimension == EMBEDDING_DIMENSION &&
            identity.embeddingDimension == SemanticEmbeddingVector.DIMENSION &&
            identity.poolingType == SemanticPoolingType.MEAN &&
            identity.normalizationRule == SemanticNormalizationRule.L2 &&
            identity.tokenizerProfileId == TOKENIZER_PROFILE_ID &&
            identity.runtimeIdentity == RUNTIME_IDENTITY
}
