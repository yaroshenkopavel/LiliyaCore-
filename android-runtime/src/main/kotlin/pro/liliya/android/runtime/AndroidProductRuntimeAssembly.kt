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
import pro.liliya.core.decision.DecisionComposition
import pro.liliya.core.encryption.CognitiveDekReference
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.learning.EncryptedPersistentLearningApplicationMutationComposition
import pro.liliya.core.learning.LearningComposition
import pro.liliya.core.learning.LearningPolicyComposition
import pro.liliya.core.learning.LearningPolicyReference
import pro.liliya.core.persistence.PersistentStoreId
import pro.liliya.core.planning.PlanningComposition
import pro.liliya.core.protectedmodel.LargeProtectedModelStagedSourceOwnership
import pro.liliya.core.reasoning.ReasoningComposition
import pro.liliya.core.reflection.ReflectionComposition

enum class AndroidProductRuntimeCreateFailure {
    PERSONA_REJECTED,
    COMPOSITION_REJECTED
}

sealed interface AndroidProductRuntimeCreateResult {
    data class Ready(
        val runtime: AndroidProductRuntimeAssembly
    ) : AndroidProductRuntimeCreateResult

    data class Rejected(
        val reason: AndroidProductRuntimeCreateFailure,
        val personaFailure: AndroidHeartProductionPersonaCreateFailure? = null
    ) : AndroidProductRuntimeCreateResult
}

sealed interface AndroidProductRuntimeStartResult {
    data object Ready : AndroidProductRuntimeStartResult

    data class HeartRejected(
        val result: HeartRuntimeStartResult
    ) : AndroidProductRuntimeStartResult

    data class GovernedLearningRejected(
        val reason: AndroidHeartProductionGovernedLearningCreateFailure,
        val cleanup: HeartRuntimeCloseResult
    ) : AndroidProductRuntimeStartResult
}

internal fun interface AndroidProductRuntimeGovernedLearningFactory {
    fun create(
        learning: LearningComposition
    ): AndroidHeartProductionGovernedLearningCreateResult
}

internal interface AndroidProductRuntimeHeartBridge {
    fun state(): HeartRuntimeState
    fun start(): HeartRuntimeStartResult
    fun chat(): ProductChatHost?
    fun conversation(
        maxRetainedMessages: Int,
        maxRetainedCharacters: Int,
        maxMessageCharacters: Int
    ): ProductConversationHost?
    fun close(): HeartRuntimeCloseResult
}

/**
 * Canonical product composition over already-authoritative production owners.
 *
 * It owns one exact LearningComposition shared by Cognitive finalization and explicit governed
 * learning. It does not provision a DEK/model, mint License/Authority, auto-learn or own Execution.
 */
class AndroidProductRuntimeAssembly internal constructor(
    private val heart: AndroidProductRuntimeHeartBridge,
    private val learning: LearningComposition,
    private val governedLearningFactory: AndroidProductRuntimeGovernedLearningFactory
) {
    @Volatile
    private var learningFollowUpHost: ProductLearningFollowUpHost? = null

    fun state(): HeartRuntimeState = heart.state()

    @Synchronized
    fun start(): AndroidProductRuntimeStartResult {
        val heartResult = heart.start()
        if (heartResult != HeartRuntimeStartResult.Ready) {
            return AndroidProductRuntimeStartResult.HeartRejected(heartResult)
        }

        val governed = when (val result = governedLearningFactory.create(learning)) {
            is AndroidHeartProductionGovernedLearningCreateResult.Ready -> result.composition
            is AndroidHeartProductionGovernedLearningCreateResult.Rejected -> {
                val cleanup = heart.close()
                return AndroidProductRuntimeStartResult.GovernedLearningRejected(
                    reason = result.reason,
                    cleanup = cleanup
                )
            }
        }

        learningFollowUpHost = ProductLearningFollowUpHost(governed)
        return AndroidProductRuntimeStartResult.Ready
    }

    fun chat(): ProductChatHost? =
        if (heart.state() == HeartRuntimeState.READY) heart.chat() else null

    fun conversation(
        maxRetainedMessages: Int,
        maxRetainedCharacters: Int,
        maxMessageCharacters: Int
    ): ProductConversationHost? =
        if (heart.state() == HeartRuntimeState.READY) {
            heart.conversation(
                maxRetainedMessages = maxRetainedMessages,
                maxRetainedCharacters = maxRetainedCharacters,
                maxMessageCharacters = maxMessageCharacters
            )
        } else {
            null
        }

    fun learningFollowUp(): ProductLearningFollowUpHost? =
        if (heart.state() == HeartRuntimeState.READY) learningFollowUpHost else null

    @Synchronized
    fun close(): HeartRuntimeCloseResult {
        val result = heart.close()
        learningFollowUpHost = null
        return result
    }

    override fun toString(): String =
        "AndroidProductRuntimeAssembly(" +
            "heart=<redacted>,learning=<redacted>," +
            "learningFollowUpHost=" +
            if (learningFollowUpHost == null) "<absent>)" else "<redacted>)"

    companion object {
        fun create(
            foundation: FoundationComposition,
            cognitiveStorage: AndroidCognitiveStorageAssembly,
            memoryStoreId: PersistentStoreId,
            knowledgeStoreId: PersistentStoreId,
            activeDek: CognitiveDekReference,
            semanticRoot: File,
            semanticEncoderFile: File,
            llamaAssembly: AndroidLlamaCppCognitiveModelAssembly,
            stagedModel: LargeProtectedModelStagedSourceOwnership,
            maxCandidatesPerSource: Int,
            personaDefinition: AndroidHeartProductionPersonaDefinition,
            scope: CognitiveRuntimeScopeId,
            cognitiveMaterialization: CognitiveMaterializationPort,
            outcomeMaterialization: CognitiveOutcomeMaterializationPort,
            policies: LearningPolicyComposition,
            policyReference: LearningPolicyReference,
            authority: CapabilityAuthorityComposition,
            principal: AuthorityPrincipal,
            governance: CognitiveLearningGovernancePort,
            learningMaterialization: CognitiveLearningApplicationMaterializationPort,
            mutations: EncryptedPersistentLearningApplicationMutationComposition,
            artifactIds: CognitiveArtifactIdSource,
            timestamps: CognitiveTimestampSource,
            limits: CognitiveRuntimeLimits = CognitiveRuntimeLimits(),
            personaLimits: AndroidHeartProductionPersonaLimits =
                AndroidHeartProductionPersonaLimits()
        ): AndroidProductRuntimeCreateResult {
            return try {
                val learning = LearningComposition(foundation)
                val reflection = ReflectionComposition(foundation)
                val personaRuntime = when (
                    val result = AndroidHeartProductionPersonaRuntimeFactory.create(
                        foundation = foundation,
                        personaDefinition = personaDefinition,
                        scope = scope,
                        materialization = cognitiveMaterialization,
                        planning = PlanningComposition(foundation),
                        reasoning = ReasoningComposition(foundation),
                        decision = DecisionComposition(foundation),
                        artifactIds = artifactIds,
                        timestamps = timestamps,
                        outcomeMaterialization = outcomeMaterialization,
                        reflection = reflection,
                        learning = learning,
                        limits = limits,
                        personaLimits = personaLimits
                    )
                ) {
                    is AndroidHeartProductionPersonaRuntimeFactoryCreateResult.Ready -> result
                    is AndroidHeartProductionPersonaRuntimeFactoryCreateResult.Rejected ->
                        return AndroidProductRuntimeCreateResult.Rejected(
                            reason = AndroidProductRuntimeCreateFailure.PERSONA_REJECTED,
                            personaFailure = result.personaFailure
                        )
                }

                val heart = AndroidHeartRuntimeAssembly.create(
                    cognitiveStorage = cognitiveStorage,
                    memoryStoreId = memoryStoreId,
                    knowledgeStoreId = knowledgeStoreId,
                    activeDek = activeDek,
                    semanticRoot = semanticRoot,
                    semanticEncoderFile = semanticEncoderFile,
                    llamaAssembly = llamaAssembly,
                    stagedModel = stagedModel,
                    maxCandidatesPerSource = maxCandidatesPerSource,
                    cognitiveRuntimeFactory = personaRuntime.factory
                )

                val governedFactory = AndroidProductRuntimeGovernedLearningFactory {
                        exactLearning ->
                    AndroidHeartProductionGovernedLearningAssembly.create(
                        heart = heart,
                        foundation = foundation,
                        scope = scope,
                        learning = exactLearning,
                        policies = policies,
                        policyReference = policyReference,
                        authority = authority,
                        principal = principal,
                        governance = governance,
                        materialization = learningMaterialization,
                        mutations = mutations,
                        artifactIds = artifactIds,
                        timestamps = timestamps,
                        limits = limits
                    )
                }

                val heartBridge = object : AndroidProductRuntimeHeartBridge {
                    override fun state(): HeartRuntimeState = heart.state()
                    override fun start(): HeartRuntimeStartResult = heart.start()
                    override fun chat(): ProductChatHost? = heart.chat()
                    override fun conversation(
                        maxRetainedMessages: Int,
                        maxRetainedCharacters: Int,
                        maxMessageCharacters: Int
                    ): ProductConversationHost? =
                        heart.conversation(
                            maxRetainedMessages = maxRetainedMessages,
                            maxRetainedCharacters = maxRetainedCharacters,
                            maxMessageCharacters = maxMessageCharacters
                        )
                    override fun close(): HeartRuntimeCloseResult = heart.close()
                }

                AndroidProductRuntimeCreateResult.Ready(
                    AndroidProductRuntimeAssembly(
                        heart = heartBridge,
                        learning = learning,
                        governedLearningFactory = governedFactory
                    )
                )
            } catch (_: IllegalArgumentException) {
                AndroidProductRuntimeCreateResult.Rejected(
                    AndroidProductRuntimeCreateFailure.COMPOSITION_REJECTED
                )
            }
        }
    }
}
