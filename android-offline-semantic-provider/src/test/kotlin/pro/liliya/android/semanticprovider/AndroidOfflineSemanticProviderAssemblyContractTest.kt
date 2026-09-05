package pro.liliya.android.semanticprovider

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test
import pro.liliya.core.cognitive.CognitiveInput
import pro.liliya.core.cognitive.CognitiveTurnGeneration
import pro.liliya.core.cognitive.CognitiveTurnId
import pro.liliya.core.cognitive.CognitiveTurnReference
import pro.liliya.core.cognitive.KnowledgeRelevanceDiscoveryRequest
import pro.liliya.core.cognitive.MemoryRelevanceDiscoveryRequest

class AndroidOfflineSemanticProviderAssemblyContractTest {

    @Test
    fun public_assembly_starts_unavailable_and_discovery_fails_closed() {
        val assembly = AndroidOfflineSemanticProviderAssembly.create()

        assertEquals(
            AndroidOfflineSemanticProviderState.UNAVAILABLE,
            assembly.state()
        )

        assertFailsWith<AndroidOfflineSemanticProviderUnavailableException> {
            assembly.memoryRelevanceDiscovery.discover(
                MemoryRelevanceDiscoveryRequest(
                    turn = turn(),
                    input = CognitiveInput("private query"),
                    maxCandidates = 4
                )
            )
        }

        assertFailsWith<AndroidOfflineSemanticProviderUnavailableException> {
            assembly.knowledgeRelevanceDiscovery.discover(
                KnowledgeRelevanceDiscoveryRequest(
                    turn = turn(),
                    input = CognitiveInput("private query"),
                    maxCandidates = 4
                )
            )
        }

        assertEquals(
            AndroidOfflineSemanticProviderState.UNAVAILABLE,
            assembly.state()
        )
    }

    @Test
    fun rebuild_before_exact_artifact_load_is_rejected_without_state_change() {
        val assembly = AndroidOfflineSemanticProviderAssembly.create()

        assertEquals(
            AndroidOfflineSemanticProviderRebuildResult.NotLoaded,
            assembly.rebuild(memory = emptyList(), knowledge = emptyList())
        )
        assertEquals(
            AndroidOfflineSemanticProviderState.UNAVAILABLE,
            assembly.state()
        )
    }

    private fun turn(): CognitiveTurnReference =
        CognitiveTurnReference(
            id = CognitiveTurnId("public-semantic-assembly-contract"),
            generation = CognitiveTurnGeneration(1)
        )
}
