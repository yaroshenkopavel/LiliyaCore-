package pro.liliya.android.semanticprovider

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import org.junit.Test

class OfflineSemanticProviderConcurrencyContractTest {

    @Test
    fun second_discovery_and_close_are_busy_while_query_embedding_is_in_flight() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val session = BlockingSession(entered, release)
        val provider = provider(session)
        provider.load(artifact())
        assertEquals(OfflineSemanticRebuildResult.Published(0), provider.rebuild(emptyList()))

        val first = thread(start = true, name = "semantic-discovery-test") {
            provider.discover(SemanticIndexDomain.MEMORY, "first", 1)
        }

        check(entered.await(5, TimeUnit.SECONDS))
        assertEquals(
            SemanticProviderFailure(SemanticProviderFailureKind.BUSY),
            provider.discover(SemanticIndexDomain.MEMORY, "second", 1)
        )
        assertEquals(OfflineSemanticProviderCloseResult.Busy, provider.close())

        release.countDown()
        first.join(5_000)
        check(!first.isAlive)
    }

    @Test
    fun rebuild_rejects_over_total_capacity_before_any_embedding() {
        val session = CountingSession()
        val limits = SemanticFlatIndexLimits(
            maxMemoryEntries = 2,
            maxKnowledgeEntries = 2,
            maxTotalEntries = 2
        )
        val provider = provider(session, limits)
        provider.load(artifact())

        val observations = listOf(
            observation("a"),
            observation("b"),
            observation("c")
        )

        assertEquals(OfflineSemanticRebuildResult.IndexRejected, provider.rebuild(observations))
        assertEquals(0, session.embedCalls)
    }

    private fun provider(
        session: SemanticProviderEmbeddingSession,
        limits: SemanticFlatIndexLimits = SemanticFlatIndexLimits()
    ): OfflineSemanticProviderComposition = OfflineSemanticProviderComposition(
        profileGeneration = SemanticProfileGeneration(1),
        sessionLoader = SemanticProviderSessionLoader {
            SemanticProviderSessionLoadResult.Loaded(session)
        },
        limits = limits
    )

    private fun artifact(): ValidatedSemanticModelArtifact = ValidatedSemanticModelArtifact(
        file = File("/private/test/model.gguf"),
        spec = SemanticModelArtifactSpec(
            profileGeneration = SemanticProfileGeneration(1),
            expectedSizeBytes = 1,
            expectedSha256 = "0".repeat(64)
        )
    )

    private fun observation(id: String): SemanticSourceObservation = SemanticSourceObservation(
        source = SemanticIndexSourceReference.Memory(
            pro.liliya.core.memory.MemoryRecordId(id),
            pro.liliya.core.memory.MemoryGeneration(1)
        ),
        content = "content-$id"
    )

    private class BlockingSession(
        private val entered: CountDownLatch,
        private val release: CountDownLatch
    ) : SemanticProviderEmbeddingSession {
        override fun embed(preparedText: String): SemanticEmbeddingResult {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            return SemanticEmbeddingResult.Embedded(unitVector())
        }

        override fun close(): SemanticEmbeddingCloseResult = SemanticEmbeddingCloseResult.Closed
    }

    private class CountingSession : SemanticProviderEmbeddingSession {
        var embedCalls: Int = 0

        override fun embed(preparedText: String): SemanticEmbeddingResult {
            embedCalls += 1
            return SemanticEmbeddingResult.Embedded(unitVector())
        }

        override fun close(): SemanticEmbeddingCloseResult = SemanticEmbeddingCloseResult.Closed
    }

    private companion object {
        fun unitVector(): SemanticEmbeddingVector {
            val values = FloatArray(SemanticEmbeddingVector.DIMENSION)
            values[0] = 1f
            return SemanticEmbeddingVector(values)
        }
    }
}
