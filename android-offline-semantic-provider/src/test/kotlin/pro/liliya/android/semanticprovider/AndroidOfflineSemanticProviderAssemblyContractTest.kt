package pro.liliya.android.semanticprovider

import java.io.File
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

    @Test
    fun successful_close_releases_all_provider_and_index_ownership_from_public_assembly() {
        val session = FakeSession()
        val provider = loadedProvider(session)
        assertEquals(
            OfflineSemanticRebuildResult.Published(0),
            provider.rebuild(emptyList())
        )
        val assembly = AndroidOfflineSemanticProviderAssembly(provider)

        assertEquals(true, assembly.ownsProviderResources())
        assertEquals(AndroidOfflineSemanticProviderCloseResult.Closed, assembly.close())
        assertEquals(AndroidOfflineSemanticProviderState.CLOSED, assembly.state())
        assertEquals(false, assembly.ownsProviderResources())
        assertEquals(1, session.closeCalls)
        assertEquals(AndroidOfflineSemanticProviderCloseResult.AlreadyClosed, assembly.close())
        assertEquals(1, session.closeCalls)
    }

    @Test
    fun failed_close_retains_exact_provider_ownership_until_explicit_cleanup_retry_succeeds() {
        val session = FakeSession().apply {
            closeResult = SemanticEmbeddingCloseResult.ProviderFailed
        }
        val provider = loadedProvider(session)
        assertEquals(
            OfflineSemanticRebuildResult.Published(0),
            provider.rebuild(emptyList())
        )
        val assembly = AndroidOfflineSemanticProviderAssembly(provider)

        assertEquals(
            AndroidOfflineSemanticProviderCloseResult.ProviderFailed,
            assembly.close()
        )
        assertEquals(AndroidOfflineSemanticProviderState.FAILED, assembly.state())
        assertEquals(true, assembly.ownsProviderResources())
        assertEquals(1, session.closeCalls)

        session.closeResult = SemanticEmbeddingCloseResult.Closed
        assertEquals(AndroidOfflineSemanticProviderCloseResult.Closed, assembly.close())
        assertEquals(AndroidOfflineSemanticProviderState.CLOSED, assembly.state())
        assertEquals(false, assembly.ownsProviderResources())
        assertEquals(2, session.closeCalls)
    }

    private fun loadedProvider(session: FakeSession): OfflineSemanticProviderComposition {
        val provider = OfflineSemanticProviderComposition(
            profileGeneration = SemanticProfileGeneration(1),
            sessionLoader = SemanticProviderSessionLoader {
                SemanticProviderSessionLoadResult.Loaded(session)
            }
        )
        assertEquals(
            OfflineSemanticProviderLoadResult.Ready,
            provider.load(
                TestSemanticModelArtifacts.validated(
                    File("/private/test/semantic-model.onnx")
                )
            )
        )
        return provider
    }

    private class FakeSession : SemanticProviderEmbeddingSession {
        var closeCalls: Int = 0
        var closeResult: SemanticEmbeddingCloseResult = SemanticEmbeddingCloseResult.Closed

        override fun embed(preparedText: String): SemanticEmbeddingResult {
            val values = FloatArray(SemanticEmbeddingVector.DIMENSION)
            values[0] = 1f
            return SemanticEmbeddingResult.Embedded(SemanticEmbeddingVector(values))
        }

        override fun close(): SemanticEmbeddingCloseResult {
            closeCalls += 1
            return closeResult
        }
    }

    private fun turn(): CognitiveTurnReference =
        CognitiveTurnReference(
            id = CognitiveTurnId("public-semantic-assembly-contract"),
            generation = CognitiveTurnGeneration(1)
        )
}
