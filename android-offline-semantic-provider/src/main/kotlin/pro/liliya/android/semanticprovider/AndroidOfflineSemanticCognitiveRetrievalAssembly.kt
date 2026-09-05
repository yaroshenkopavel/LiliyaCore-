package pro.liliya.android.semanticprovider

import pro.liliya.core.cognitive.CognitiveRelevanceRetrievalLimits
import pro.liliya.core.cognitive.KnowledgeAuthoritativeResolverPort
import pro.liliya.core.cognitive.KnowledgeCompositionAuthoritativeResolver
import pro.liliya.core.cognitive.KnowledgeRetrievalPort
import pro.liliya.core.cognitive.MemoryAuthoritativeResolverPort
import pro.liliya.core.cognitive.MemoryCompositionAuthoritativeResolver
import pro.liliya.core.cognitive.MemoryRetrievalPort
import pro.liliya.core.cognitive.PersistentKnowledgeCompositionAuthoritativeResolver
import pro.liliya.core.cognitive.PersistentMemoryCompositionAuthoritativeResolver
import pro.liliya.core.cognitive.RelevanceKnowledgeRetrievalPort
import pro.liliya.core.cognitive.RelevanceMemoryRetrievalPort
import pro.liliya.core.knowledge.KnowledgeComposition
import pro.liliya.core.knowledge.PersistentKnowledgeComposition
import pro.liliya.core.memory.MemoryComposition
import pro.liliya.core.memory.PersistentMemoryComposition

/**
 * Public production wiring from the offline semantic provider into Core Cognitive retrieval.
 *
 * The semantic provider supplies advisory ordered exact ID + generation candidates only.
 * Core authoritative resolvers revalidate every candidate against current Memory/Knowledge before
 * a snapshot can enter Cognitive Context. This assembly adds no fallback, retry, rebinding,
 * similarity-score authority or Android-to-Core dependency.
 */
class AndroidOfflineSemanticCognitiveRetrievalAssembly private constructor(
    val memoryRetrieval: MemoryRetrievalPort,
    val knowledgeRetrieval: KnowledgeRetrievalPort
) {
    companion object {
        fun create(
            semantic: AndroidOfflineSemanticProviderAssembly,
            memoryResolver: MemoryAuthoritativeResolverPort,
            knowledgeResolver: KnowledgeAuthoritativeResolverPort,
            maxCandidatesPerSource: Int
        ): AndroidOfflineSemanticCognitiveRetrievalAssembly = createFromPorts(
            memoryDiscovery = semantic.memoryRelevanceDiscovery,
            knowledgeDiscovery = semantic.knowledgeRelevanceDiscovery,
            memoryResolver = memoryResolver,
            knowledgeResolver = knowledgeResolver,
            maxCandidatesPerSource = maxCandidatesPerSource
        )

        fun create(
            semantic: AndroidOfflineSemanticProviderAssembly,
            memory: MemoryComposition,
            knowledge: KnowledgeComposition,
            maxCandidatesPerSource: Int
        ): AndroidOfflineSemanticCognitiveRetrievalAssembly = create(
            semantic = semantic,
            memoryResolver = MemoryCompositionAuthoritativeResolver(memory),
            knowledgeResolver = KnowledgeCompositionAuthoritativeResolver(knowledge),
            maxCandidatesPerSource = maxCandidatesPerSource
        )

        fun create(
            semantic: AndroidOfflineSemanticProviderAssembly,
            memory: PersistentMemoryComposition,
            knowledge: PersistentKnowledgeComposition,
            maxCandidatesPerSource: Int
        ): AndroidOfflineSemanticCognitiveRetrievalAssembly = create(
            semantic = semantic,
            memoryResolver = PersistentMemoryCompositionAuthoritativeResolver(memory),
            knowledgeResolver = PersistentKnowledgeCompositionAuthoritativeResolver(knowledge),
            maxCandidatesPerSource = maxCandidatesPerSource
        )

        internal fun createFromPorts(
            memoryDiscovery: pro.liliya.core.cognitive.MemoryRelevanceDiscoveryPort,
            knowledgeDiscovery: pro.liliya.core.cognitive.KnowledgeRelevanceDiscoveryPort,
            memoryResolver: MemoryAuthoritativeResolverPort,
            knowledgeResolver: KnowledgeAuthoritativeResolverPort,
            maxCandidatesPerSource: Int
        ): AndroidOfflineSemanticCognitiveRetrievalAssembly {
            val limits = CognitiveRelevanceRetrievalLimits(maxCandidatesPerSource)
            return AndroidOfflineSemanticCognitiveRetrievalAssembly(
                memoryRetrieval = RelevanceMemoryRetrievalPort(
                    discovery = memoryDiscovery,
                    resolver = memoryResolver,
                    limits = limits
                ),
                knowledgeRetrieval = RelevanceKnowledgeRetrievalPort(
                    discovery = knowledgeDiscovery,
                    resolver = knowledgeResolver,
                    limits = limits
                )
            )
        }
    }
}
