package pro.liliya.core.cognitive

import pro.liliya.core.personality.PersonalityTarget

sealed interface CognitiveContextAssemblyResult {
    data class Published(val itemCount: Int) : CognitiveContextAssemblyResult
    data object Stale : CognitiveContextAssemblyResult
    data class Rejected(val reason: CognitiveContextAssemblyFailure) : CognitiveContextAssemblyResult
}

enum class CognitiveContextAssemblyFailure {
    SELF_SNAPSHOT_FAILED,
    PERSONALITY_SNAPSHOT_FAILED,
    MEMORY_PROVIDER_FAILED,
    KNOWLEDGE_PROVIDER_FAILED,
    MEMORY_RESULT_LIMIT_REJECTED,
    KNOWLEDGE_RESULT_LIMIT_REJECTED,
    CONTEXT_LIMIT_REJECTED,
    PUBLICATION_FAILED
}

internal class CognitiveContextAssembler(
    private val turns: CognitiveTurnRegistry,
    private val memoryRetrieval: MemoryRetrievalPort,
    private val knowledgeRetrieval: KnowledgeRetrievalPort,
    private val selfSnapshots: SelfSnapshotPort,
    private val personalitySnapshots: PersonalitySnapshotPort,
    private val limits: CognitiveRuntimeLimits
) {
    fun assemble(reference: CognitiveTurnReference): CognitiveContextAssemblyResult {
        val input = turns.inputIfCurrent(reference) ?: return CognitiveContextAssemblyResult.Stale
        if (!turns.isCurrentAt(reference, CognitiveTurnLifecycle.CREATED)) {
            return CognitiveContextAssemblyResult.Stale
        }

        val self = try {
            selfSnapshots.current()
        } catch (_: Exception) {
            return CognitiveContextAssemblyResult.Rejected(
                CognitiveContextAssemblyFailure.SELF_SNAPSHOT_FAILED
            )
        }
        if (!turns.isCurrentAt(reference, CognitiveTurnLifecycle.CREATED)) {
            return CognitiveContextAssemblyResult.Stale
        }

        val personalities = if (self == null) {
            emptyList()
        } else {
            try {
                personalitySnapshots.snapshot().toList()
            } catch (_: Exception) {
                return CognitiveContextAssemblyResult.Rejected(
                    CognitiveContextAssemblyFailure.PERSONALITY_SNAPSHOT_FAILED
                )
            }
        }
        if (!turns.isCurrentAt(reference, CognitiveTurnLifecycle.CREATED)) {
            return CognitiveContextAssemblyResult.Stale
        }

        val memoryRequest = MemoryRetrievalRequest(
            turn = reference,
            input = input,
            maxResults = limits.maxRetrievalResults
        )
        val memory = try {
            memoryRetrieval.retrieve(memoryRequest)
        } catch (_: Exception) {
            return CognitiveContextAssemblyResult.Rejected(
                CognitiveContextAssemblyFailure.MEMORY_PROVIDER_FAILED
            )
        }
        if (!turns.isCurrentAt(reference, CognitiveTurnLifecycle.CREATED)) {
            return CognitiveContextAssemblyResult.Stale
        }
        if (memory.items.size > memoryRequest.maxResults) {
            return CognitiveContextAssemblyResult.Rejected(
                CognitiveContextAssemblyFailure.MEMORY_RESULT_LIMIT_REJECTED
            )
        }

        val knowledgeRequest = KnowledgeRetrievalRequest(
            turn = reference,
            input = input,
            maxResults = limits.maxRetrievalResults
        )
        val knowledge = try {
            knowledgeRetrieval.retrieve(knowledgeRequest)
        } catch (_: Exception) {
            return CognitiveContextAssemblyResult.Rejected(
                CognitiveContextAssemblyFailure.KNOWLEDGE_PROVIDER_FAILED
            )
        }
        if (!turns.isCurrentAt(reference, CognitiveTurnLifecycle.CREATED)) {
            return CognitiveContextAssemblyResult.Stale
        }
        if (knowledge.items.size > knowledgeRequest.maxResults) {
            return CognitiveContextAssemblyResult.Rejected(
                CognitiveContextAssemblyFailure.KNOWLEDGE_RESULT_LIMIT_REJECTED
            )
        }

        val items = mutableListOf<CognitiveContextItem>()

        if (self != null) {
            if (!items.addBounded(
                    source = CognitiveContextSourceReference.Self(
                        identityId = self.identity.id,
                        generation = self.generation
                    ),
                    content = self.identity.name.value
                )
            ) {
                return contextLimitRejected()
            }

            for (snapshot in personalities) {
                val target = snapshot.profile.target
                if (
                    target is PersonalityTarget.Self &&
                    target.identityId == self.identity.id &&
                    target.generation == self.generation
                ) {
                    val content = snapshot.profile.attributes.joinToString("\n") { attribute ->
                        "${attribute.key.value}=${attribute.value.value}"
                    }
                    if (!items.addBounded(
                            source = CognitiveContextSourceReference.Personality(
                                profileId = snapshot.profile.id,
                                generation = snapshot.generation
                            ),
                            content = content
                        )
                    ) {
                        return contextLimitRejected()
                    }
                }
            }
        }

        for (snapshot in memory.items) {
            if (!items.addBounded(
                    source = CognitiveContextSourceReference.Memory(
                        recordId = snapshot.record.id,
                        generation = snapshot.generation
                    ),
                    content = snapshot.record.content
                )
            ) {
                return contextLimitRejected()
            }
        }

        for (snapshot in knowledge.items) {
            if (!items.addBounded(
                    source = CognitiveContextSourceReference.Knowledge(
                        itemId = snapshot.item.id,
                        generation = snapshot.generation
                    ),
                    content = snapshot.item.content
                )
            ) {
                return contextLimitRejected()
            }
        }

        val context = CognitiveContextSnapshot(reference, items)
        return when (turns.publishContextIfCurrent(reference, context)) {
            CognitiveTurnPublicationResult.Published ->
                CognitiveContextAssemblyResult.Published(items.size)
            CognitiveTurnPublicationResult.Stale -> CognitiveContextAssemblyResult.Stale
            is CognitiveTurnPublicationResult.Rejected -> contextLimitRejected()
            is CognitiveTurnPublicationResult.Failed ->
                CognitiveContextAssemblyResult.Rejected(
                    CognitiveContextAssemblyFailure.PUBLICATION_FAILED
                )
        }
    }

    private fun MutableList<CognitiveContextItem>.addBounded(
        source: CognitiveContextSourceReference,
        content: String
    ): Boolean {
        if (size >= limits.maxContextItems || content.length > limits.maxContextItemChars) {
            return false
        }
        add(CognitiveContextItem(source = source, content = content))
        return true
    }

    private fun contextLimitRejected(): CognitiveContextAssemblyResult.Rejected =
        CognitiveContextAssemblyResult.Rejected(
            CognitiveContextAssemblyFailure.CONTEXT_LIMIT_REJECTED
        )
}
