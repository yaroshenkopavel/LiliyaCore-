package pro.liliya.android.semanticprovider

import java.io.File
import pro.liliya.core.cognitive.KnowledgeRelevanceDiscoveryPort
import pro.liliya.core.cognitive.MemoryRelevanceDiscoveryPort
import pro.liliya.core.knowledge.KnowledgeItemSnapshot
import pro.liliya.core.memory.MemoryRecordSnapshot

/**
 * Narrow public Android production boundary for Offline Semantic Provider v0.1.
 *
 * The assembly intentionally exposes only lifecycle, exact Core relevance discovery ports,
 * explicit local-artifact loading, full authoritative rebuild, and close. Provider-private
 * vectors, scores, ORT handles, physical paths and mutable index internals remain inaccessible.
 *
 * All methods are blocking. A production host must schedule artifact hashing, ORT load and rebuild
 * away from the Android main/UI thread.
 */
class AndroidOfflineSemanticProviderAssembly internal constructor(
    private val provider: OfflineSemanticProviderComposition
) {
    @Volatile
    private var publicState: AndroidOfflineSemanticProviderState =
        AndroidOfflineSemanticProviderState.UNAVAILABLE

    private val memoryAdapter = OfflineSemanticMemoryRelevanceDiscoveryAdapter(provider)
    private val knowledgeAdapter = OfflineSemanticKnowledgeRelevanceDiscoveryAdapter(provider)

    val memoryRelevanceDiscovery: MemoryRelevanceDiscoveryPort =
        MemoryRelevanceDiscoveryPort { request ->
            requireReady()
            memoryAdapter.discover(request)
        }

    val knowledgeRelevanceDiscovery: KnowledgeRelevanceDiscoveryPort =
        KnowledgeRelevanceDiscoveryPort { request ->
            requireReady()
            knowledgeAdapter.discover(request)
        }

    fun state(): AndroidOfflineSemanticProviderState = publicState

    /**
     * Validates and loads the exact repository-approved v0.1 ONNX encoder/tokenizer bundle.
     *
     * [encoderFile] must be inside [appPrivateRoot]. The tokenizer must be present beside it under
     * its exact pinned file name. Callers cannot supply alternate digests, model identities or
     * generic model-loading parameters.
     */
    /**
     * Loads the exact provisioned production bundle from [appPrivateRoot] without requiring the
     * host to expose or pass the private encoder path.
     *
     * Blocking; schedule off the Android main/UI thread.
     */
    fun loadProvisioned(
        appPrivateRoot: File
    ): AndroidOfflineSemanticProviderLoadResult =
        load(
            appPrivateRoot = appPrivateRoot,
            encoderFile = File(appPrivateRoot, SemanticModelProfileV01.ONNX_FILE_NAME)
        )

    @Synchronized
    fun load(
        appPrivateRoot: File,
        encoderFile: File
    ): AndroidOfflineSemanticProviderLoadResult {
        if (publicState != AndroidOfflineSemanticProviderState.UNAVAILABLE) {
            return AndroidOfflineSemanticProviderLoadResult.Busy
        }

        publicState = AndroidOfflineSemanticProviderState.LOADING

        val validated = when (
            val validation = SemanticModelArtifactValidator(
                appPrivateRoot = appPrivateRoot,
                trustedIdentity = productionSemanticModelIdentity()
            ).validate(
                candidate = encoderFile,
                spec = SemanticModelArtifactSpec(productionSemanticModelIdentity())
            )
        ) {
            is SemanticModelArtifactValidationResult.Validated -> validation.artifact
            SemanticModelArtifactValidationResult.Missing ->
                return failLoad(AndroidOfflineSemanticProviderLoadResult.ArtifactMissing)
            SemanticModelArtifactValidationResult.OutsideAppPrivateRoot ->
                return failLoad(AndroidOfflineSemanticProviderLoadResult.ArtifactRejected)
            SemanticModelArtifactValidationResult.NotRegularFile,
            SemanticModelArtifactValidationResult.FileNameMismatch,
            SemanticModelArtifactValidationResult.ProfileMismatch,
            SemanticModelArtifactValidationResult.ArtifactIdentityMismatch,
            SemanticModelArtifactValidationResult.IncompleteConversionProvenance,
            SemanticModelArtifactValidationResult.ArtifactTooLarge,
            SemanticModelArtifactValidationResult.SizeMismatch,
            SemanticModelArtifactValidationResult.DigestMismatch ->
                return failLoad(AndroidOfflineSemanticProviderLoadResult.ArtifactRejected)
            is SemanticModelArtifactValidationResult.Failed ->
                return failLoad(AndroidOfflineSemanticProviderLoadResult.ProviderFailed)
        }

        return when (provider.load(validated)) {
            OfflineSemanticProviderLoadResult.Ready -> {
                publicState = AndroidOfflineSemanticProviderState.LOADED
                AndroidOfflineSemanticProviderLoadResult.Loaded
            }
            OfflineSemanticProviderLoadResult.Busy -> {
                publicState = AndroidOfflineSemanticProviderState.UNAVAILABLE
                AndroidOfflineSemanticProviderLoadResult.Busy
            }
            OfflineSemanticProviderLoadResult.ResourceRejected ->
                failLoad(AndroidOfflineSemanticProviderLoadResult.ResourceRejected)
            OfflineSemanticProviderLoadResult.Unsupported ->
                failLoad(AndroidOfflineSemanticProviderLoadResult.Unsupported)
            OfflineSemanticProviderLoadResult.Rejected ->
                failLoad(AndroidOfflineSemanticProviderLoadResult.ArtifactRejected)
            OfflineSemanticProviderLoadResult.ProviderFailed ->
                failLoad(AndroidOfflineSemanticProviderLoadResult.ProviderFailed)
        }
    }

    /**
     * Rebuilds one complete derived semantic index from exact authoritative snapshots.
     *
     * No partial replacement index is exposed. Public READY is published only after the provider
     * reports a complete successful rebuild.
     */
    fun rebuild(
        memory: List<MemoryRecordSnapshot>,
        knowledge: List<KnowledgeItemSnapshot>
    ): AndroidOfflineSemanticProviderRebuildResult {
        synchronized(this) {
            if (publicState != AndroidOfflineSemanticProviderState.LOADED &&
                publicState != AndroidOfflineSemanticProviderState.READY
            ) {
                return AndroidOfflineSemanticProviderRebuildResult.NotLoaded
            }
            publicState = AndroidOfflineSemanticProviderState.REBUILDING
        }

        val observations = ArrayList<SemanticSourceObservation>(memory.size + knowledge.size)
        memory.forEach { snapshot ->
            observations += SemanticSourceObservation(
                source = SemanticIndexSourceReference.Memory(
                    id = snapshot.record.id,
                    generation = snapshot.generation
                ),
                content = snapshot.record.content
            )
        }
        knowledge.forEach { snapshot ->
            observations += SemanticSourceObservation(
                source = SemanticIndexSourceReference.Knowledge(
                    id = snapshot.item.id,
                    generation = snapshot.generation
                ),
                content = snapshot.item.content
            )
        }

        val result = provider.rebuild(observations)
        synchronized(this) {
            return when (result) {
                is OfflineSemanticRebuildResult.Published -> {
                    publicState = AndroidOfflineSemanticProviderState.READY
                    AndroidOfflineSemanticProviderRebuildResult.Ready(result.entryCount)
                }
                OfflineSemanticRebuildResult.Busy -> {
                    publicState = AndroidOfflineSemanticProviderState.LOADED
                    AndroidOfflineSemanticProviderRebuildResult.Busy
                }
                OfflineSemanticRebuildResult.NotReady,
                OfflineSemanticRebuildResult.ResourceRejected,
                OfflineSemanticRebuildResult.EmbeddingRejected,
                OfflineSemanticRebuildResult.EmbeddingFailed,
                OfflineSemanticRebuildResult.SessionFailed,
                OfflineSemanticRebuildResult.IndexRejected -> {
                    publicState = AndroidOfflineSemanticProviderState.FAILED
                    AndroidOfflineSemanticProviderRebuildResult.Failed
                }
            }
        }
    }

    internal fun synchronizeAdd(
        observation: SemanticSourceObservation
    ): AndroidOfflineSemanticMutationApplyResult {
        synchronized(this) {
            if (publicState != AndroidOfflineSemanticProviderState.READY) {
                return AndroidOfflineSemanticMutationApplyResult.NotReady
            }
        }

        return when (provider.add(observation)) {
            OfflineSemanticAddResult.Indexed ->
                AndroidOfflineSemanticMutationApplyResult.Applied
            OfflineSemanticAddResult.DuplicateExact ->
                AndroidOfflineSemanticMutationApplyResult.AlreadyApplied
            OfflineSemanticAddResult.EmbeddingFailed,
            OfflineSemanticAddResult.SessionFailed -> {
                markProviderFailed()
                AndroidOfflineSemanticMutationApplyResult.RebuildRequired
            }
            OfflineSemanticAddResult.Busy,
            OfflineSemanticAddResult.NotReady,
            OfflineSemanticAddResult.ResourceRejected,
            OfflineSemanticAddResult.EmbeddingRejected,
            OfflineSemanticAddResult.EntityAlreadyIndexed,
            OfflineSemanticAddResult.CapacityRejected -> {
                markRebuildRequired()
                AndroidOfflineSemanticMutationApplyResult.RebuildRequired
            }
        }
    }

    internal fun synchronizeReplace(
        expected: SemanticIndexSourceReference,
        replacement: SemanticSourceObservation
    ): AndroidOfflineSemanticMutationApplyResult {
        synchronized(this) {
            if (publicState != AndroidOfflineSemanticProviderState.READY) {
                return AndroidOfflineSemanticMutationApplyResult.NotReady
            }
        }

        return when (provider.replace(expected, replacement)) {
            OfflineSemanticReplaceResult.Replaced ->
                AndroidOfflineSemanticMutationApplyResult.Applied
            OfflineSemanticReplaceResult.EmbeddingFailed,
            OfflineSemanticReplaceResult.SessionFailed -> {
                markProviderFailed()
                AndroidOfflineSemanticMutationApplyResult.RebuildRequired
            }
            OfflineSemanticReplaceResult.Busy,
            OfflineSemanticReplaceResult.NotReady,
            OfflineSemanticReplaceResult.ResourceRejected,
            OfflineSemanticReplaceResult.EmbeddingRejected,
            OfflineSemanticReplaceResult.StaleExpected,
            OfflineSemanticReplaceResult.IdentityMismatch,
            OfflineSemanticReplaceResult.NonForwardGeneration -> {
                markRebuildRequired()
                AndroidOfflineSemanticMutationApplyResult.RebuildRequired
            }
        }
    }

    internal fun synchronizeRemove(
        source: SemanticIndexSourceReference
    ): AndroidOfflineSemanticMutationApplyResult {
        synchronized(this) {
            if (publicState != AndroidOfflineSemanticProviderState.READY) {
                return AndroidOfflineSemanticMutationApplyResult.NotReady
            }
        }

        return when (provider.remove(source)) {
            OfflineSemanticRemoveResult.Removed ->
                AndroidOfflineSemanticMutationApplyResult.Applied
            OfflineSemanticRemoveResult.Busy,
            OfflineSemanticRemoveResult.NotReady,
            OfflineSemanticRemoveResult.StaleOrMissing -> {
                markRebuildRequired()
                AndroidOfflineSemanticMutationApplyResult.RebuildRequired
            }
        }
    }

    @Synchronized
    private fun markRebuildRequired() {
        if (publicState == AndroidOfflineSemanticProviderState.READY) {
            publicState = AndroidOfflineSemanticProviderState.LOADED
        }
    }

    @Synchronized
    private fun markProviderFailed() {
        publicState = AndroidOfflineSemanticProviderState.FAILED
    }

    @Synchronized
    fun close(): AndroidOfflineSemanticProviderCloseResult {
        if (publicState == AndroidOfflineSemanticProviderState.CLOSED) {
            return AndroidOfflineSemanticProviderCloseResult.AlreadyClosed
        }
        val previous = publicState
        publicState = AndroidOfflineSemanticProviderState.CLOSING
        return when (provider.close()) {
            OfflineSemanticProviderCloseResult.Closed -> {
                publicState = AndroidOfflineSemanticProviderState.CLOSED
                AndroidOfflineSemanticProviderCloseResult.Closed
            }
            OfflineSemanticProviderCloseResult.AlreadyClosed -> {
                publicState = AndroidOfflineSemanticProviderState.CLOSED
                AndroidOfflineSemanticProviderCloseResult.AlreadyClosed
            }
            OfflineSemanticProviderCloseResult.Busy -> {
                publicState = previous
                AndroidOfflineSemanticProviderCloseResult.Busy
            }
            OfflineSemanticProviderCloseResult.ProviderFailed -> {
                publicState = AndroidOfflineSemanticProviderState.FAILED
                AndroidOfflineSemanticProviderCloseResult.ProviderFailed
            }
        }
    }

    private fun requireReady() {
        if (publicState != AndroidOfflineSemanticProviderState.READY) {
            throw AndroidOfflineSemanticProviderUnavailableException(publicState)
        }
    }

    private fun failLoad(
        result: AndroidOfflineSemanticProviderLoadResult
    ): AndroidOfflineSemanticProviderLoadResult {
        publicState = AndroidOfflineSemanticProviderState.FAILED
        return result
    }

    companion object {
        fun create(): AndroidOfflineSemanticProviderAssembly =
            AndroidOfflineSemanticProviderAssembly(
                OfflineSemanticProviderComposition(
                    profileGeneration = SemanticModelProfileV01.PROFILE_GENERATION
                )
            )
    }
}

enum class AndroidOfflineSemanticProviderState {
    UNAVAILABLE,
    LOADING,
    LOADED,
    REBUILDING,
    READY,
    FAILED,
    CLOSING,
    CLOSED
}

sealed interface AndroidOfflineSemanticProviderLoadResult {
    data object Loaded : AndroidOfflineSemanticProviderLoadResult
    data object Busy : AndroidOfflineSemanticProviderLoadResult
    data object ArtifactMissing : AndroidOfflineSemanticProviderLoadResult
    data object ArtifactRejected : AndroidOfflineSemanticProviderLoadResult
    data object ResourceRejected : AndroidOfflineSemanticProviderLoadResult
    data object Unsupported : AndroidOfflineSemanticProviderLoadResult
    data object ProviderFailed : AndroidOfflineSemanticProviderLoadResult
}

sealed interface AndroidOfflineSemanticProviderRebuildResult {
    data class Ready(val entryCount: Int) : AndroidOfflineSemanticProviderRebuildResult {
        init {
            require(entryCount >= 0)
        }
    }

    data object Busy : AndroidOfflineSemanticProviderRebuildResult
    data object NotLoaded : AndroidOfflineSemanticProviderRebuildResult
    data object Failed : AndroidOfflineSemanticProviderRebuildResult
}

internal sealed interface AndroidOfflineSemanticMutationApplyResult {
    data object Applied : AndroidOfflineSemanticMutationApplyResult
    data object AlreadyApplied : AndroidOfflineSemanticMutationApplyResult
    data object RebuildRequired : AndroidOfflineSemanticMutationApplyResult
    data object NotReady : AndroidOfflineSemanticMutationApplyResult
}

sealed interface AndroidOfflineSemanticProviderCloseResult {
    data object Closed : AndroidOfflineSemanticProviderCloseResult
    data object Busy : AndroidOfflineSemanticProviderCloseResult
    data object AlreadyClosed : AndroidOfflineSemanticProviderCloseResult
    data object ProviderFailed : AndroidOfflineSemanticProviderCloseResult
}

class AndroidOfflineSemanticProviderUnavailableException(
    val providerState: AndroidOfflineSemanticProviderState
) : IllegalStateException("offline semantic provider is not ready") {
    override fun toString(): String =
        "AndroidOfflineSemanticProviderUnavailableException(providerState=$providerState)"
}

internal fun productionSemanticModelIdentity(): SemanticModelArtifactIdentity =
    SemanticModelArtifactIdentity(
        profileId = SemanticModelProfileV01.PROFILE_ID,
        profileGeneration = SemanticModelProfileV01.PROFILE_GENERATION,
        upstreamModelRepository = SemanticModelProfileV01.UPSTREAM_MODEL_REPOSITORY,
        upstreamModelRevision = SemanticModelProfileV01.UPSTREAM_MODEL_REVISION,
        conversionProvenance = SemanticConversionProvenance.Reproducible(
            artifactRepository = "yaroshenkopavel/LiliyaCore-",
            artifactRevision = "7ff8a913036aa051fe7b35d5b4dcc05118357cb6",
            conversionToolRevision = SemanticModelProfileV01.ONNX_EXPORT_PIPELINE_REVISION
        ),
        modelFileName = SemanticModelProfileV01.ONNX_FILE_NAME,
        modelFormat = SemanticModelFormat.ONNX,
        expectedSizeBytes = SemanticModelProfileV01.ONNX_SIZE_BYTES,
        expectedSha256 = SemanticModelProfileV01.ONNX_SHA256,
        architecture = SemanticModelArchitecture.BERT,
        embeddingDimension = SemanticModelProfileV01.EMBEDDING_DIMENSION,
        poolingType = SemanticPoolingType.MEAN,
        normalizationRule = SemanticNormalizationRule.L2,
        tokenizerProfileId = SemanticModelProfileV01.TOKENIZER_PROFILE_ID,
        runtimeIdentity = SemanticModelProfileV01.RUNTIME_IDENTITY
    )
