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
        if (memory.items.size > memoryRequest.maxResults) {
            return CognitiveContextAssemblyResult.Rejected(
                CognitiveContextAssemblyFailure.MEMORY_RESULT_LIMIT_REJECTED
            )
        }
        if (!turns.isCurrentAt(reference, CognitiveTurnLifecycle.CREATED)) {
            return CognitiveContextAssemblyResult.Stale
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
        if (knowledge.items.size > knowledgeRequest.maxResults) {
            return CognitiveContextAssemblyResult.Rejected(
                CognitiveContextAssemblyFailure.KNOWLEDGE_RESULT_LIMIT_REJECTED
            )
        }
        if (!turns.isCurrentAt(reference, CognitiveTurnLifecycle.CREATED)) {
            return CognitiveContextAssemblyResult.Stale
        }

        val items = buildList {
            if (self != null) {
                add(
                    CognitiveContextItem(
                        source = CognitiveContextSourceReference.Self(
                            identityId = self.identity.id,
                            generation = self.generation
                        ),
                        content = self.identity.name.value
                    )
                )

                personalities.forEach { snapshot ->
                    val target = snapshot.profile.target
                    if (
                        target is PersonalityTarget.Self &&
                        target.identityId == self.identity.id &&
                        target.generation == self.generation
                    ) {
                        add(
                            CognitiveContextItem(
                                source = CognitiveContextSourceReference.Personality(
                                    profileId = snapshot.profile.id,
                                    generation = snapshot.generation
                                ),
                                content = snapshot.profile.attributes.joinToString("\n") { attribute ->
                                    "${attribute.key.value}=${attribute.value.value}"
                                }
                            )
                        )
                    }
                }
            }

            memory.items.forEach { snapshot ->
                add(
                    CognitiveContextItem(
                        source = CognitiveContextSourceReference.Memory(
                            recordId = snapshot.record.id,
                            generation = snapshot.generation
                        ),
                        content = snapshot.record.content
                    )
                )
            }

            knowledge.items.forEach { snapshot ->
                add(
                    CognitiveContextItem(
                        source = CognitiveContextSourceReference.Knowledge(
                            itemId = snapshot.item.id,
                            generation = snapshot.generation
                        ),
                        content = snapshot.item.content
                    )
                )
            }
        }

        if (
            items.size > limits.maxContextItems ||
            items.any { it.content.length > limits.maxContextItemChars }
        ) {
            return CognitiveContextAssemblyResult.Rejected(
                CognitiveContextAssemblyFailure.CONTEXT_LIMIT_REJECTED
            )
        }

        val context = CognitiveContextSnapshot(reference, items)
        return when (turns.publishContextIfCurrent(reference, context)) {
            CognitiveTurnPublicationResult.Published ->
                CognitiveContextAssemblyResult.Published(items.size)
            CognitiveTurnPublicationResult.Stale -> CognitiveContextAssemblyResult.Stale
            is CognitiveTurnPublicationResult.Rejected ->
                CognitiveContextAssemblyResult.Rejected(
                    CognitiveContextAssemblyFailure.CONTEXT_LIMIT_REJECTED
                )
            is CognitiveTurnPublicationResult.Failed ->
                CognitiveContextAssemblyResult.Rejected(
                    CognitiveContextAssemblyFailure.PUBLICATION_FAILED
                )
        }
    }
}
