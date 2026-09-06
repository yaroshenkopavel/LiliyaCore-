package pro.liliya.android.runtime

import java.io.File
import pro.liliya.android.cognitivestorage.AndroidCognitiveStorageAssembly
import pro.liliya.android.cognitivestorage.AndroidEncryptedKnowledgeOpenResult
import pro.liliya.android.cognitivestorage.AndroidEncryptedMemoryOpenResult
import pro.liliya.android.llamacppengine.AndroidLlamaCppCognitiveModelAssembly
import pro.liliya.android.semanticprovider.AndroidOfflineSemanticAuthoritativeSnapshot
import pro.liliya.android.semanticprovider.AndroidOfflineSemanticCognitiveRetrievalAssembly
import pro.liliya.android.semanticprovider.AndroidOfflineSemanticProviderAssembly
import pro.liliya.android.semanticprovider.AndroidOfflineSemanticProviderCloseResult
import pro.liliya.android.semanticprovider.AndroidOfflineSemanticProviderRebuildResult
import pro.liliya.android.semanticprovider.AndroidOfflineSemanticMutationSynchronizer
import pro.liliya.android.semanticprovider.AndroidOfflineSemanticStartupCoordinator
import pro.liliya.android.semanticprovider.AndroidOfflineSemanticStartupResult
import pro.liliya.core.cognitive.CognitiveGovernedLearningComposition
import pro.liliya.core.cognitive.CognitiveInferencePort
import pro.liliya.core.cognitive.CognitiveModelActivationResult
import pro.liliya.core.cognitive.CognitiveModelQuiesceResult
import pro.liliya.core.cognitive.CognitiveModelRetirementResult
import pro.liliya.core.cognitive.CognitiveRuntimeComposition
import pro.liliya.core.cognitive.CognitiveStreamingInferencePort
import pro.liliya.core.cognitive.KnowledgeAuthoritativeResolutionResult
import pro.liliya.core.cognitive.KnowledgeAuthoritativeResolverPort
import pro.liliya.core.cognitive.KnowledgeRetrievalPort
import pro.liliya.core.cognitive.MemoryAuthoritativeResolutionResult
import pro.liliya.core.cognitive.MemoryAuthoritativeResolverPort
import pro.liliya.core.cognitive.MemoryRetrievalPort
import pro.liliya.core.encryption.CognitiveDekReference
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.knowledge.EncryptedPersistentKnowledgeComposition
import pro.liliya.core.learning.EncryptedPersistentLearningApplicationMutationComposition
import pro.liliya.core.learning.LearningApplicationMutationApplicationPort
import pro.liliya.core.learning.LearningApplicationMutationAuthorizationGate
import pro.liliya.core.learning.PersistentEncryptedLearningApplicationMutationApplier
import pro.liliya.core.learning.PersistentLearningApplicationMutationComposition
import pro.liliya.core.memory.EncryptedPersistentMemoryComposition
import pro.liliya.core.persistence.PersistentStoreId
import pro.liliya.core.protectedmodel.LargeProtectedModelStagedSourceOwnership
import pro.liliya.core.runtime.hardening.RuntimeModelSessionReference

/**
 * Host policy seam for constructing the turn-level Cognitive Runtime only after authoritative
 * storage, semantic retrieval and generation inference are all ready.
 *
 * Product-specific Self/Personality/materialization policy remains outside this platform assembly.
 */
fun interface AndroidHeartCognitiveRuntimeFactory {
    fun create(
        memoryRetrieval: MemoryRetrievalPort,
        knowledgeRetrieval: KnowledgeRetrievalPort,
        inference: CognitiveInferencePort,
        streamingInference: CognitiveStreamingInferencePort
    ): CognitiveRuntimeComposition
}

sealed interface AndroidHeartSemanticRecoveryResult {
    data class Recovered(val entryCount: Int) : AndroidHeartSemanticRecoveryResult
    data object NotRequired : AndroidHeartSemanticRecoveryResult
    data object Busy : AndroidHeartSemanticRecoveryResult
    data object Failed : AndroidHeartSemanticRecoveryResult
}

/**
 * Concrete Android cold-start composition root.
 *
 * Provisioning is deliberately external:
 * - this assembly does not create/rotate DEKs;
 * - it does not download or stage a model;
 * - it never accepts a raw generation-model path.
 *
 * It consumes already-authorized exact ownership inputs and sequences the existing production
 * owners into one product-level HEART READY boundary.
 */
class AndroidHeartRuntimeAssembly private constructor(
    private val cognitiveStorage: AndroidCognitiveStorageAssembly,
    private val memoryStoreId: PersistentStoreId,
    private val knowledgeStoreId: PersistentStoreId,
    private val activeDek: CognitiveDekReference,
    private val semanticRoot: File,
    private val semanticEncoderFile: File,
    private var semanticAssembly: AndroidOfflineSemanticProviderAssembly,
    private val llamaAssembly: AndroidLlamaCppCognitiveModelAssembly,
    private val stagedModel: LargeProtectedModelStagedSourceOwnership,
    private val maxCandidatesPerSource: Int,
    private val cognitiveRuntimeFactory: AndroidHeartCognitiveRuntimeFactory
) {
    init {
        require(maxCandidatesPerSource > 0) {
            "maximum semantic candidates per source must be positive"
        }
    }

    @Volatile
    private var memory: EncryptedPersistentMemoryComposition? = null

    @Volatile
    private var knowledge: EncryptedPersistentKnowledgeComposition? = null

    @Volatile
    private var semanticStartup: AndroidOfflineSemanticStartupCoordinator? = null

    @Volatile
    private var retrieval: AndroidOfflineSemanticCognitiveRetrievalAssembly? = null

    @Volatile
    private var generationSession: RuntimeModelSessionReference? = null

    @Volatile
    private var cognitiveRuntime: CognitiveRuntimeComposition? = null

    @Volatile
    private var semanticRecoveryRequired: Boolean = false

    private val startup = HeartRuntimeStartupCoordinator(
        storageStart = HeartStorageStartupPort { startStorage() },
        storageClose = HeartStorageClosePort { closeStorage() },
        semanticStart = HeartSemanticStartupPort { startSemantic() },
        semanticClose = HeartSemanticClosePort { closeSemantic() },
        generationStart = HeartGenerationStartupPort { startGeneration() },
        generationClose = HeartGenerationClosePort { closeGeneration() }
    )

    fun state(): HeartRuntimeState = startup.state()

    fun start(): HeartRuntimeStartResult = startup.start()

    fun runtime(): CognitiveRuntimeComposition? =
        if (startup.state() == HeartRuntimeState.READY) cognitiveRuntime else null

    fun learningMutationApplicationPort(
        foundation: FoundationComposition,
        mutations: EncryptedPersistentLearningApplicationMutationComposition,
        authorizationGate: LearningApplicationMutationAuthorizationGate
    ): LearningApplicationMutationApplicationPort? {
        if (startup.state() != HeartRuntimeState.READY) return null
        val activeMemory = memory ?: return null
        val activeKnowledge = knowledge ?: return null
        return PersistentEncryptedLearningApplicationMutationApplier(
            foundation = foundation,
            mutations = mutations,
            authorizationGate = authorizationGate,
            memory = activeMemory,
            knowledge = activeKnowledge
        )
    }

    @Deprecated(
        message = "Use encrypted persistent learning mutation lifecycle for production heart wiring"
    )
    fun learningMutationApplicationPort(
        foundation: FoundationComposition,
        mutations: PersistentLearningApplicationMutationComposition,
        authorizationGate: LearningApplicationMutationAuthorizationGate
    ): LearningApplicationMutationApplicationPort? {
        if (startup.state() != HeartRuntimeState.READY) return null
        val activeMemory = memory ?: return null
        val activeKnowledge = knowledge ?: return null
        return PersistentEncryptedLearningApplicationMutationApplier(
            foundation = foundation,
            mutations = mutations,
            authorizationGate = authorizationGate,
            memory = activeMemory,
            knowledge = activeKnowledge
        )
    }

    fun governedLearning(
        composition: CognitiveGovernedLearningComposition
    ): AndroidHeartGovernedLearningComposition? {
        if (startup.state() != HeartRuntimeState.READY) return null
        val activeMemory = memory ?: return null
        val activeKnowledge = knowledge ?: return null
        val semanticSynchronizer =
            AndroidOfflineSemanticMutationSynchronizer.create(semanticAssembly)

        return AndroidHeartGovernedLearningComposition(
            governed = AndroidHeartGovernedLearningPort { reference ->
                composition.process(reference)
            },
            semantic = AndroidHeartAppliedSemanticSyncPort { applied ->
                val sync = when (val downstream = applied.receipt.downstream) {
                    is pro.liliya.core.learning.LearningApplicationDownstreamReference.Memory -> {
                        val snapshot = activeMemory.inspect(downstream.recordId)
                        if (snapshot == null || snapshot.generation != downstream.generation) {
                            null
                        } else {
                            semanticSynchronizer.addMemory(snapshot)
                        }
                    }

                    is pro.liliya.core.learning.LearningApplicationDownstreamReference.Knowledge -> {
                        val snapshot = activeKnowledge.inspect(downstream.itemId)
                        if (snapshot == null || snapshot.generation != downstream.generation) {
                            null
                        } else {
                            semanticSynchronizer.addKnowledge(snapshot)
                        }
                    }
                }

                when (sync) {
                    pro.liliya.android.semanticprovider.AndroidOfflineSemanticMutationSyncResult.Synchronized,
                    pro.liliya.android.semanticprovider.AndroidOfflineSemanticMutationSyncResult.AlreadySynchronized ->
                        AndroidHeartSemanticLearningSyncStatus.SYNCHRONIZED

                    pro.liliya.android.semanticprovider.AndroidOfflineSemanticMutationSyncResult.RebuildRequired,
                    pro.liliya.android.semanticprovider.AndroidOfflineSemanticMutationSyncResult.NotReady,
                    null -> AndroidHeartSemanticLearningSyncStatus.REBUILD_REQUIRED
                }
            },
            onSemanticUnavailable = {
                semanticRecoveryRequired = true
                startup.markFailedFromReady()
            }
        )
    }

    @Synchronized
    fun recoverSemantic(): AndroidHeartSemanticRecoveryResult {
        if (!semanticRecoveryRequired) {
            return AndroidHeartSemanticRecoveryResult.NotRequired
        }
        if (startup.state() != HeartRuntimeState.FAILED) {
            return AndroidHeartSemanticRecoveryResult.Failed
        }
        val activeMemory = memory ?: return AndroidHeartSemanticRecoveryResult.Failed
        val activeKnowledge = knowledge ?: return AndroidHeartSemanticRecoveryResult.Failed

        val entryCount = when (semanticAssembly.state()) {
            pro.liliya.android.semanticprovider.AndroidOfflineSemanticProviderState.LOADED,
            pro.liliya.android.semanticprovider.AndroidOfflineSemanticProviderState.READY -> {
                when (
                    val rebuilt = semanticAssembly.rebuild(
                        memory = activeMemory.snapshotEntries(),
                        knowledge = activeKnowledge.snapshotEntries()
                    )
                ) {
                    is AndroidOfflineSemanticProviderRebuildResult.Ready -> rebuilt.entryCount
                    AndroidOfflineSemanticProviderRebuildResult.Busy ->
                        return AndroidHeartSemanticRecoveryResult.Busy
                    AndroidOfflineSemanticProviderRebuildResult.NotLoaded,
                    AndroidOfflineSemanticProviderRebuildResult.Failed ->
                        return AndroidHeartSemanticRecoveryResult.Failed
                }
            }

            pro.liliya.android.semanticprovider.AndroidOfflineSemanticProviderState.FAILED -> {
                val oldCoordinator = semanticStartup
                val close = if (oldCoordinator != null) {
                    oldCoordinator.close()
                } else {
                    semanticAssembly.close()
                }
                when (close) {
                    AndroidOfflineSemanticProviderCloseResult.Closed,
                    AndroidOfflineSemanticProviderCloseResult.AlreadyClosed -> Unit
                    AndroidOfflineSemanticProviderCloseResult.Busy ->
                        return AndroidHeartSemanticRecoveryResult.Busy
                    AndroidOfflineSemanticProviderCloseResult.ProviderFailed ->
                        return AndroidHeartSemanticRecoveryResult.Failed
                }

                val replacement = AndroidOfflineSemanticProviderAssembly.create()
                val replacementStartup = AndroidOfflineSemanticStartupCoordinator.create(
                    assembly = replacement,
                    authoritativeSnapshots = {
                        AndroidOfflineSemanticAuthoritativeSnapshot(
                            memory = activeMemory.snapshotEntries(),
                            knowledge = activeKnowledge.snapshotEntries()
                        )
                    }
                )
                val started = replacementStartup.start(semanticRoot, semanticEncoderFile)
                val recoveredEntries = when (started) {
                    is AndroidOfflineSemanticStartupResult.Ready -> started.entryCount
                    AndroidOfflineSemanticStartupResult.Busy ->
                        return AndroidHeartSemanticRecoveryResult.Busy
                    AndroidOfflineSemanticStartupResult.ArtifactMissing,
                    AndroidOfflineSemanticStartupResult.ArtifactRejected,
                    AndroidOfflineSemanticStartupResult.ResourceRejected,
                    AndroidOfflineSemanticStartupResult.Unsupported,
                    AndroidOfflineSemanticStartupResult.ProviderFailed,
                    AndroidOfflineSemanticStartupResult.AuthoritativeSnapshotFailed,
                    AndroidOfflineSemanticStartupResult.RebuildFailed ->
                        return AndroidHeartSemanticRecoveryResult.Failed
                }
                semanticAssembly = replacement
                semanticStartup = replacementStartup

                if (!recreateRetrievalAndRuntime(activeMemory, activeKnowledge)) {
                    return AndroidHeartSemanticRecoveryResult.Failed
                }
                recoveredEntries
            }

            else -> return AndroidHeartSemanticRecoveryResult.Failed
        }

        if (!startup.markReadyAfterExplicitRecovery()) {
            return AndroidHeartSemanticRecoveryResult.Failed
        }
        semanticRecoveryRequired = false
        return AndroidHeartSemanticRecoveryResult.Recovered(entryCount)
    }

    fun close(): HeartRuntimeCloseResult = startup.close()

    private fun recreateRetrievalAndRuntime(
        activeMemory: EncryptedPersistentMemoryComposition,
        activeKnowledge: EncryptedPersistentKnowledgeComposition
    ): Boolean {
        val memoryResolver = MemoryAuthoritativeResolverPort { candidate ->
            val current = activeMemory.inspect(candidate.recordId)
            if (
                current != null &&
                current.record.id == candidate.recordId &&
                current.generation == candidate.generation
            ) {
                MemoryAuthoritativeResolutionResult.Resolved(current)
            } else {
                MemoryAuthoritativeResolutionResult.Stale
            }
        }
        val knowledgeResolver = KnowledgeAuthoritativeResolverPort { candidate ->
            val current = activeKnowledge.inspect(candidate.itemId)
            if (
                current != null &&
                current.item.id == candidate.itemId &&
                current.generation == candidate.generation
            ) {
                KnowledgeAuthoritativeResolutionResult.Resolved(current)
            } else {
                KnowledgeAuthoritativeResolutionResult.Stale
            }
        }

        val replacementRetrieval = try {
            AndroidOfflineSemanticCognitiveRetrievalAssembly.create(
                semantic = semanticAssembly,
                memoryResolver = memoryResolver,
                knowledgeResolver = knowledgeResolver,
                maxCandidatesPerSource = maxCandidatesPerSource
            )
        } catch (_: Throwable) {
            return false
        }
        val replacementRuntime = try {
            cognitiveRuntimeFactory.create(
                memoryRetrieval = replacementRetrieval.memoryRetrieval,
                knowledgeRetrieval = replacementRetrieval.knowledgeRetrieval,
                inference = llamaAssembly.inferencePort,
                streamingInference = llamaAssembly.streamingInferencePort
            )
        } catch (_: Throwable) {
            return false
        }

        retrieval = replacementRetrieval
        cognitiveRuntime = replacementRuntime
        return true
    }

    private fun startStorage(): HeartDependencyStartResult {
        val openedMemory = when (
            val result = cognitiveStorage.openEncryptedMemory(memoryStoreId, activeDek)
        ) {
            is AndroidEncryptedMemoryOpenResult.Opened -> result.composition
            AndroidEncryptedMemoryOpenResult.Corrupt,
            is AndroidEncryptedMemoryOpenResult.Incompatible,
            is AndroidEncryptedMemoryOpenResult.EncryptionUnavailable,
            is AndroidEncryptedMemoryOpenResult.Failed -> return HeartDependencyStartResult.Failed
        }
        memory = openedMemory

        val openedKnowledge = when (
            val result = cognitiveStorage.openEncryptedKnowledge(knowledgeStoreId, activeDek)
        ) {
            is AndroidEncryptedKnowledgeOpenResult.Opened -> result.composition
            AndroidEncryptedKnowledgeOpenResult.Corrupt,
            is AndroidEncryptedKnowledgeOpenResult.Incompatible,
            is AndroidEncryptedKnowledgeOpenResult.EncryptionUnavailable,
            is AndroidEncryptedKnowledgeOpenResult.Failed -> return HeartDependencyStartResult.Failed
        }
        knowledge = openedKnowledge
        return HeartDependencyStartResult.Ready
    }

    private fun startSemantic(): HeartDependencyStartResult {
        val activeMemory = memory ?: return HeartDependencyStartResult.Failed
        val activeKnowledge = knowledge ?: return HeartDependencyStartResult.Failed

        val coordinator = AndroidOfflineSemanticStartupCoordinator.create(
            assembly = semanticAssembly,
            authoritativeSnapshots = {
                AndroidOfflineSemanticAuthoritativeSnapshot(
                    memory = activeMemory.snapshotEntries(),
                    knowledge = activeKnowledge.snapshotEntries()
                )
            }
        )
        semanticStartup = coordinator

        return when (coordinator.start(semanticRoot, semanticEncoderFile)) {
            is AndroidOfflineSemanticStartupResult.Ready -> {
                semanticRecoveryRequired = false
                HeartDependencyStartResult.Ready
            }
            AndroidOfflineSemanticStartupResult.Busy,
            AndroidOfflineSemanticStartupResult.ArtifactMissing,
            AndroidOfflineSemanticStartupResult.ArtifactRejected,
            AndroidOfflineSemanticStartupResult.ResourceRejected,
            AndroidOfflineSemanticStartupResult.Unsupported,
            AndroidOfflineSemanticStartupResult.ProviderFailed,
            AndroidOfflineSemanticStartupResult.AuthoritativeSnapshotFailed,
            AndroidOfflineSemanticStartupResult.RebuildFailed -> HeartDependencyStartResult.Failed
        }
    }

    private fun startGeneration(): HeartDependencyStartResult {
        val activeMemory = memory ?: return HeartDependencyStartResult.Failed
        val activeKnowledge = knowledge ?: return HeartDependencyStartResult.Failed

        val memoryResolver = MemoryAuthoritativeResolverPort { candidate ->
            val current = activeMemory.inspect(candidate.recordId)
            if (
                current != null &&
                current.record.id == candidate.recordId &&
                current.generation == candidate.generation
            ) {
                MemoryAuthoritativeResolutionResult.Resolved(current)
            } else {
                MemoryAuthoritativeResolutionResult.Stale
            }
        }
        val knowledgeResolver = KnowledgeAuthoritativeResolverPort { candidate ->
            val current = activeKnowledge.inspect(candidate.itemId)
            if (
                current != null &&
                current.item.id == candidate.itemId &&
                current.generation == candidate.generation
            ) {
                KnowledgeAuthoritativeResolutionResult.Resolved(current)
            } else {
                KnowledgeAuthoritativeResolutionResult.Stale
            }
        }

        val activeRetrieval = try {
            AndroidOfflineSemanticCognitiveRetrievalAssembly.create(
                semantic = semanticAssembly,
                memoryResolver = memoryResolver,
                knowledgeResolver = knowledgeResolver,
                maxCandidatesPerSource = maxCandidatesPerSource
            )
        } catch (_: Throwable) {
            return HeartDependencyStartResult.Failed
        }
        retrieval = activeRetrieval

        val activation = llamaAssembly.stagedActivation.activate(stagedModel)
        val session = when (activation) {
            is CognitiveModelActivationResult.Activated -> activation.session
            is CognitiveModelActivationResult.Rejected,
            is CognitiveModelActivationResult.Failed -> return HeartDependencyStartResult.Failed
        }
        generationSession = session

        cognitiveRuntime = try {
            cognitiveRuntimeFactory.create(
                memoryRetrieval = activeRetrieval.memoryRetrieval,
                knowledgeRetrieval = activeRetrieval.knowledgeRetrieval,
                inference = llamaAssembly.inferencePort,
                streamingInference = llamaAssembly.streamingInferencePort
            )
        } catch (_: Throwable) {
            null
        }

        return if (cognitiveRuntime != null) {
            HeartDependencyStartResult.Ready
        } else {
            HeartDependencyStartResult.Failed
        }
    }

    private fun closeGeneration(): HeartDependencyCloseResult {
        cognitiveRuntime = null
        retrieval = null

        val session = generationSession ?: return HeartDependencyCloseResult.Closed
        when (llamaAssembly.cognitiveRuntime.beginQuiescing(session)) {
            CognitiveModelQuiesceResult.Quiescing,
            CognitiveModelQuiesceResult.AlreadyQuiescing -> Unit
            CognitiveModelQuiesceResult.Busy,
            CognitiveModelQuiesceResult.Stale -> return HeartDependencyCloseResult.Failed
        }

        return when (llamaAssembly.cognitiveRuntime.retireIfDrained(session)) {
            CognitiveModelRetirementResult.Retired -> {
                generationSession = null
                HeartDependencyCloseResult.Closed
            }
            is CognitiveModelRetirementResult.DrainRequired,
            CognitiveModelRetirementResult.Busy,
            CognitiveModelRetirementResult.Stale,
            CognitiveModelRetirementResult.CleanupFailed -> HeartDependencyCloseResult.Failed
        }
    }

    private fun closeSemantic(): HeartDependencyCloseResult {
        val coordinator = semanticStartup
        val result = if (coordinator != null) {
            coordinator.close()
        } else {
            semanticAssembly.close()
        }
        return when (result) {
            AndroidOfflineSemanticProviderCloseResult.Closed,
            AndroidOfflineSemanticProviderCloseResult.AlreadyClosed -> {
                semanticStartup = null
                semanticRecoveryRequired = false
                HeartDependencyCloseResult.Closed
            }
            AndroidOfflineSemanticProviderCloseResult.Busy,
            AndroidOfflineSemanticProviderCloseResult.ProviderFailed ->
                HeartDependencyCloseResult.Failed
        }
    }

    private fun closeStorage(): HeartDependencyCloseResult {
        // Current encrypted persistent compositions have no open native/session resource to close.
        // Releasing the top-level references is the exact composition ownership boundary.
        knowledge = null
        memory = null
        return HeartDependencyCloseResult.Closed
    }

    companion object {
        fun create(
            cognitiveStorage: AndroidCognitiveStorageAssembly,
            memoryStoreId: PersistentStoreId,
            knowledgeStoreId: PersistentStoreId,
            activeDek: CognitiveDekReference,
            semanticRoot: File,
            semanticEncoderFile: File,
            llamaAssembly: AndroidLlamaCppCognitiveModelAssembly,
            stagedModel: LargeProtectedModelStagedSourceOwnership,
            maxCandidatesPerSource: Int,
            cognitiveRuntimeFactory: AndroidHeartCognitiveRuntimeFactory
        ): AndroidHeartRuntimeAssembly =
            AndroidHeartRuntimeAssembly(
                cognitiveStorage = cognitiveStorage,
                memoryStoreId = memoryStoreId,
                knowledgeStoreId = knowledgeStoreId,
                activeDek = activeDek,
                semanticRoot = semanticRoot,
                semanticEncoderFile = semanticEncoderFile,
                semanticAssembly = AndroidOfflineSemanticProviderAssembly.create(),
                llamaAssembly = llamaAssembly,
                stagedModel = stagedModel,
                maxCandidatesPerSource = maxCandidatesPerSource,
                cognitiveRuntimeFactory = cognitiveRuntimeFactory
            )
    }
}
