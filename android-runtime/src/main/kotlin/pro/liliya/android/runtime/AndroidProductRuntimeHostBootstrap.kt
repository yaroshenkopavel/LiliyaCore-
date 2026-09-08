package pro.liliya.android.runtime

import java.io.File
import pro.liliya.android.cognitivestorage.AndroidCognitiveStorageAssembly
import pro.liliya.android.llamacppengine.AndroidLlamaCppCognitiveModelAssembly
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.CapabilityAuthorityComposition
import pro.liliya.core.cognitive.CognitiveArtifactIdSource
import pro.liliya.core.cognitive.CognitiveLearningApplicationMaterializationPort
import pro.liliya.core.cognitive.CognitiveLearningGovernancePort
import pro.liliya.core.cognitive.CognitiveMaterializationPort
import pro.liliya.core.cognitive.CognitiveOutcomeMaterializationPort
import pro.liliya.core.cognitive.CognitiveRuntimeLimits
import pro.liliya.core.cognitive.CognitiveRuntimeScopeId
import pro.liliya.core.cognitive.CognitiveTimestampSource
import pro.liliya.core.encryption.CognitiveDekReference
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.learning.EncryptedPersistentLearningApplicationMutationComposition
import pro.liliya.core.learning.LearningPolicyComposition
import pro.liliya.core.learning.LearningPolicyReference
import pro.liliya.core.persistence.PersistentStoreId
import pro.liliya.core.protectedmodel.LargeProtectedModelStagedSourceOwnership

/**
 * Immutable set of already-prepared production owners required by Product Runtime.
 *
 * This object does not create, authorize, provision, repair or replace any dependency.
 */
data class AndroidProductRuntimeHostPreparedInputs(
    val foundation: FoundationComposition,
    val cognitiveStorage: AndroidCognitiveStorageAssembly,
    val memoryStoreId: PersistentStoreId,
    val knowledgeStoreId: PersistentStoreId,
    val activeDek: CognitiveDekReference,
    val semanticRoot: File,
    val semanticEncoderFile: File,
    val llamaAssembly: AndroidLlamaCppCognitiveModelAssembly,
    val stagedModel: LargeProtectedModelStagedSourceOwnership,
    val maxCandidatesPerSource: Int,
    val personaDefinition: AndroidHeartProductionPersonaDefinition,
    val scope: CognitiveRuntimeScopeId,
    val cognitiveMaterialization: CognitiveMaterializationPort,
    val outcomeMaterialization: CognitiveOutcomeMaterializationPort,
    val policies: LearningPolicyComposition,
    val policyReference: LearningPolicyReference,
    val authority: CapabilityAuthorityComposition,
    val principal: AuthorityPrincipal,
    val governance: CognitiveLearningGovernancePort,
    val learningMaterialization: CognitiveLearningApplicationMaterializationPort,
    val mutations: EncryptedPersistentLearningApplicationMutationComposition,
    val artifactIds: CognitiveArtifactIdSource,
    val timestamps: CognitiveTimestampSource,
    val limits: CognitiveRuntimeLimits = CognitiveRuntimeLimits(),
    val personaLimits: AndroidHeartProductionPersonaLimits =
        AndroidHeartProductionPersonaLimits()
)

enum class AndroidProductRuntimeHostBootstrapFailure {
    CREATE_REJECTED,
    START_REJECTED,
    INTERNAL_FAILURE
}

sealed interface AndroidProductRuntimeHostBootstrapResult {
    data class Ready(
        val session: AndroidProductRuntimeHostSession
    ) : AndroidProductRuntimeHostBootstrapResult

    data class Rejected(
        val reason: AndroidProductRuntimeHostBootstrapFailure,
        val cleanup: HeartRuntimeCloseResult? = null
    ) : AndroidProductRuntimeHostBootstrapResult
}

internal fun interface AndroidProductRuntimeHostCreateFactory {
    fun create(): AndroidProductRuntimeCreateResult
}

/**
 * Canonical host-facing bootstrap for already-prepared Product Runtime ownership.
 *
 * Host Bootstrap != Provisioning Authority.
 * Host Bootstrap != License Authority.
 * Host Bootstrap != Capability Authority.
 * Host Bootstrap != Heart State Authority.
 */
object AndroidProductRuntimeHostBootstrap {

    fun start(
        inputs: AndroidProductRuntimeHostPreparedInputs,
        admission: AndroidProductRuntimeAdmissionResult.Admitted
    ): AndroidProductRuntimeHostBootstrapResult {
        admission.ownership
        return start(
            AndroidProductRuntimeHostCreateFactory {
                createProductionRuntime(inputs)
            }
        )
    }

    internal fun start(
        factory: AndroidProductRuntimeHostCreateFactory
    ): AndroidProductRuntimeHostBootstrapResult {
        val runtime = try {
            when (val created = factory.create()) {
                is AndroidProductRuntimeCreateResult.Ready -> created.runtime
                is AndroidProductRuntimeCreateResult.Rejected ->
                    return AndroidProductRuntimeHostBootstrapResult.Rejected(
                        AndroidProductRuntimeHostBootstrapFailure.CREATE_REJECTED
                    )
            }
        } catch (_: Exception) {
            return AndroidProductRuntimeHostBootstrapResult.Rejected(
                AndroidProductRuntimeHostBootstrapFailure.INTERNAL_FAILURE
            )
        }

        val started = try {
            runtime.start()
        } catch (_: Exception) {
            return AndroidProductRuntimeHostBootstrapResult.Rejected(
                reason = AndroidProductRuntimeHostBootstrapFailure.INTERNAL_FAILURE,
                cleanup = safeClose(runtime)
            )
        }

        if (started != AndroidProductRuntimeStartResult.Ready) {
            return AndroidProductRuntimeHostBootstrapResult.Rejected(
                reason = AndroidProductRuntimeHostBootstrapFailure.START_REJECTED,
                cleanup = safeClose(runtime)
            )
        }

        return AndroidProductRuntimeHostBootstrapResult.Ready(
            AndroidProductRuntimeHostSession(
                object : AndroidProductRuntimeHostSessionBridge {
                    override fun state(): HeartRuntimeState = runtime.state()
                    override fun chat(): ProductChatHost? = runtime.chat()
                    override fun conversation(
                        maxRetainedMessages: Int,
                        maxRetainedCharacters: Int,
                        maxMessageCharacters: Int
                    ): ProductConversationHost? =
                        runtime.conversation(
                            maxRetainedMessages = maxRetainedMessages,
                            maxRetainedCharacters = maxRetainedCharacters,
                            maxMessageCharacters = maxMessageCharacters
                        )
                    override fun learningFollowUp(): ProductLearningFollowUpHost? =
                        runtime.learningFollowUp()
                    override fun recoverSemantic(): AndroidProductRuntimeSemanticRecoveryResult =
                        runtime.recoverSemantic()
                    override fun close(): HeartRuntimeCloseResult = runtime.close()
                }
            )
        )
    }

    private fun createProductionRuntime(
        inputs: AndroidProductRuntimeHostPreparedInputs
    ): AndroidProductRuntimeCreateResult =
        AndroidProductRuntimeAssembly.create(
            foundation = inputs.foundation,
            cognitiveStorage = inputs.cognitiveStorage,
            memoryStoreId = inputs.memoryStoreId,
            knowledgeStoreId = inputs.knowledgeStoreId,
            activeDek = inputs.activeDek,
            semanticRoot = inputs.semanticRoot,
            semanticEncoderFile = inputs.semanticEncoderFile,
            llamaAssembly = inputs.llamaAssembly,
            stagedModel = inputs.stagedModel,
            maxCandidatesPerSource = inputs.maxCandidatesPerSource,
            personaDefinition = inputs.personaDefinition,
            scope = inputs.scope,
            cognitiveMaterialization = inputs.cognitiveMaterialization,
            outcomeMaterialization = inputs.outcomeMaterialization,
            policies = inputs.policies,
            policyReference = inputs.policyReference,
            authority = inputs.authority,
            principal = inputs.principal,
            governance = inputs.governance,
            learningMaterialization = inputs.learningMaterialization,
            mutations = inputs.mutations,
            artifactIds = inputs.artifactIds,
            timestamps = inputs.timestamps,
            limits = inputs.limits,
            personaLimits = inputs.personaLimits
        )

    private fun safeClose(
        runtime: AndroidProductRuntimeAssembly
    ): HeartRuntimeCloseResult? =
        try {
            runtime.close()
        } catch (_: Exception) {
            null
        }
}
