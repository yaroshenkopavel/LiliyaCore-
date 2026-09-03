package pro.liliya.android.semanticprovider

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryRecordId

class OfflineSemanticProviderCompositionTest {

    @Test
    fun load_transitions_empty_to_ready_and_duplicate_load_is_busy() {
        val fakeSession = FakeSession()
        val provider = provider(fakeSession)
        val artifact = artifact()

        assertEquals(OfflineSemanticProviderLifecycle.EMPTY, provider.lifecycle())
        assertEquals(OfflineSemanticProviderLoadResult.Ready, provider.load(artifact))
        assertEquals(OfflineSemanticProviderLifecycle.READY, provider.lifecycle())
        assertEquals(OfflineSemanticProviderLoadResult.Busy, provider.load(artifact))
        assertEquals(1, fakeSession.loaderCalls)
    }

    @Test
    fun discovery_is_unavailable_until_complete_index_is_published() {
        val provider = provider(FakeSession())
        assertEquals(OfflineSemanticProviderLoadResult.Ready, provider.load(artifact()))

        val before = provider.discover(SemanticIndexDomain.MEMORY, "query", 1)
        assertEquals(
            SemanticProviderFailure(SemanticProviderFailureKind.INDEX_UNAVAILABLE),
            before
        )

        assertIs<OfflineSemanticRebuildResult.Published>(provider.rebuild(emptyList()))
        assertEquals(SemanticCandidates(emptyList()), provider.discover(SemanticIndexDomain.MEMORY, "query", 1))
    }

    @Test
    fun rebuild_embeds_passage_and_discovery_embeds_query_then_returns_exact_candidate() {
        val fakeSession = FakeSession()
        val provider = provider(fakeSession)
        provider.load(artifact())
        val source = SemanticIndexSourceReference.Memory(
            MemoryRecordId("memory-1"),
            MemoryGeneration(9)
        )

        val rebuilt = provider.rebuild(
            listOf(SemanticSourceObservation(source, "important fact"))
        )
        assertEquals(OfflineSemanticRebuildResult.Published(1), rebuilt)

        val discovered = assertIs<SemanticCandidates>(
            provider.discover(SemanticIndexDomain.MEMORY, "important", 1)
        )
        assertEquals(listOf(source), discovered.candidates)
        assertEquals(listOf("passage: important fact", "query: important"), fakeSession.embeddedTexts)
    }

    @Test
    fun failed_rebuild_does_not_publish_partial_replacement() {
        val fakeSession = FakeSession()
        val provider = provider(fakeSession)
        provider.load(artifact())
        val first = SemanticIndexSourceReference.Memory(MemoryRecordId("first"), MemoryGeneration(1))
        val second = SemanticIndexSourceReference.Memory(MemoryRecordId("second"), MemoryGeneration(1))

        assertEquals(
            OfflineSemanticRebuildResult.Published(1),
            provider.rebuild(listOf(SemanticSourceObservation(first, "first content")))
        )

        fakeSession.failOnText = "passage: second content"
        assertEquals(
            OfflineSemanticRebuildResult.EmbeddingFailed,
            provider.rebuild(
                listOf(
                    SemanticSourceObservation(first, "first replacement"),
                    SemanticSourceObservation(second, "second content")
                )
            )
        )
        assertEquals(OfflineSemanticProviderLifecycle.FAILED, provider.lifecycle())
        assertEquals(
            SemanticProviderFailure(SemanticProviderFailureKind.SESSION_FAILED),
            provider.discover(SemanticIndexDomain.MEMORY, "first", 2)
        )
    }

    @Test
    fun discovery_is_explicitly_unavailable_while_rebuild_is_in_flight() {
        val enteredEmbedding = CountDownLatch(1)
        val releaseEmbedding = CountDownLatch(1)
        val fakeSession = FakeSession(
            onEmbed = { text ->
                if (text.startsWith("passage: ")) {
                    enteredEmbedding.countDown()
                    check(releaseEmbedding.await(5, TimeUnit.SECONDS))
                }
            }
        )
        val provider = provider(fakeSession)
        provider.load(artifact())
        assertEquals(OfflineSemanticRebuildResult.Published(0), provider.rebuild(emptyList()))

        val rebuildThread = thread(start = true, name = "semantic-rebuild-test") {
            provider.rebuild(
                listOf(
                    SemanticSourceObservation(
                        SemanticIndexSourceReference.Memory(MemoryRecordId("blocked"), MemoryGeneration(1)),
                        "blocked content"
                    )
                )
            )
        }

        check(enteredEmbedding.await(5, TimeUnit.SECONDS))
        assertEquals(
            SemanticProviderFailure(SemanticProviderFailureKind.INDEX_UNAVAILABLE),
            provider.discover(SemanticIndexDomain.MEMORY, "query", 1)
        )
        assertEquals(OfflineSemanticProviderCloseResult.Busy, provider.close())

        releaseEmbedding.countDown()
        rebuildThread.join(5_000)
        check(!rebuildThread.isAlive)
    }

    @Test
    fun fatal_embedding_failure_poisons_session_without_hidden_reload() {
        val fakeSession = FakeSession().apply { failOnText = "query: poison" }
        val provider = provider(fakeSession)
        provider.load(artifact())
        provider.rebuild(emptyList())

        assertEquals(
            SemanticProviderFailure(SemanticProviderFailureKind.OPERATION_FAILED),
            provider.discover(SemanticIndexDomain.MEMORY, "poison", 1)
        )
        assertEquals(OfflineSemanticProviderLifecycle.FAILED, provider.lifecycle())
        assertEquals(
            SemanticProviderFailure(SemanticProviderFailureKind.SESSION_FAILED),
            provider.discover(SemanticIndexDomain.MEMORY, "again", 1)
        )
        assertEquals(1, fakeSession.loaderCalls)
    }

    @Test
    fun incremental_add_replace_and_remove_preserve_exact_generation_semantics() {
        val fakeSession = FakeSession()
        val provider = provider(fakeSession)
        provider.load(artifact())
        val first = SemanticIndexSourceReference.Memory(MemoryRecordId("incremental"), MemoryGeneration(2))
        val stale = SemanticIndexSourceReference.Memory(MemoryRecordId("incremental"), MemoryGeneration(1))
        val second = SemanticIndexSourceReference.Memory(MemoryRecordId("incremental"), MemoryGeneration(3))

        assertEquals(
            OfflineSemanticAddResult.Indexed,
            provider.add(SemanticSourceObservation(first, "first content"))
        )
        assertEquals(
            OfflineSemanticAddResult.DuplicateExact,
            provider.add(SemanticSourceObservation(first, "duplicate content"))
        )
        assertEquals(
            OfflineSemanticAddResult.EntityAlreadyIndexed,
            provider.add(SemanticSourceObservation(second, "implicit replacement forbidden"))
        )
        assertEquals(
            OfflineSemanticReplaceResult.StaleExpected,
            provider.replace(stale, SemanticSourceObservation(second, "stale replacement"))
        )
        assertEquals(
            OfflineSemanticReplaceResult.Replaced,
            provider.replace(first, SemanticSourceObservation(second, "second content"))
        )
        assertEquals(OfflineSemanticRemoveResult.StaleOrMissing, provider.remove(first))

        assertEquals(
            SemanticCandidates(listOf(second)),
            provider.discover(SemanticIndexDomain.MEMORY, "second", 1)
        )
        assertEquals(OfflineSemanticRemoveResult.Removed, provider.remove(second))
        assertEquals(
            SemanticCandidates(emptyList()),
            provider.discover(SemanticIndexDomain.MEMORY, "second", 1)
        )
        assertEquals(
            listOf(
                "passage: first content",
                "passage: duplicate content",
                "passage: implicit replacement forbidden",
                "passage: stale replacement",
                "passage: second content",
                "query: second",
                "query: second"
            ),
            fakeSession.embeddedTexts
        )
    }

    @Test
    fun incremental_embedding_is_single_flight_and_blocks_discovery_rebuild_and_close() {
        val enteredEmbedding = CountDownLatch(1)
        val releaseEmbedding = CountDownLatch(1)
        val fakeSession = FakeSession(
            onEmbed = { text ->
                if (text == "passage: incremental content") {
                    enteredEmbedding.countDown()
                    check(releaseEmbedding.await(5, TimeUnit.SECONDS))
                }
            }
        )
        val provider = provider(fakeSession)
        provider.load(artifact())
        val result = AtomicReference<OfflineSemanticAddResult>()
        val updateThread = thread(start = true, name = "semantic-incremental-test") {
            result.set(
                provider.add(
                    SemanticSourceObservation(
                        SemanticIndexSourceReference.Memory(
                            MemoryRecordId("incremental-busy"),
                            MemoryGeneration(1)
                        ),
                        "incremental content"
                    )
                )
            )
        }

        check(enteredEmbedding.await(5, TimeUnit.SECONDS))
        assertEquals(
            SemanticProviderFailure(SemanticProviderFailureKind.BUSY),
            provider.discover(SemanticIndexDomain.MEMORY, "query", 1)
        )
        assertEquals(OfflineSemanticRebuildResult.Busy, provider.rebuild(emptyList()))
        assertEquals(OfflineSemanticProviderCloseResult.Busy, provider.close())

        releaseEmbedding.countDown()
        updateThread.join(5_000)
        check(!updateThread.isAlive)
        assertEquals(OfflineSemanticAddResult.Indexed, result.get())
    }

    @Test
    fun close_is_explicit_and_closed_provider_rejects_discovery() {
        val fakeSession = FakeSession()
        val provider = provider(fakeSession)
        provider.load(artifact())

        assertEquals(OfflineSemanticProviderCloseResult.Closed, provider.close())
        assertEquals(OfflineSemanticProviderLifecycle.CLOSED, provider.lifecycle())
        assertEquals(1, fakeSession.closeCalls)
        assertEquals(OfflineSemanticProviderCloseResult.AlreadyClosed, provider.close())
        assertEquals(
            SemanticProviderFailure(SemanticProviderFailureKind.CLOSED),
            provider.discover(SemanticIndexDomain.MEMORY, "query", 1)
        )
    }

    private fun provider(session: FakeSession): OfflineSemanticProviderComposition {
        val loader = SemanticProviderSessionLoader {
            session.loaderCalls += 1
            SemanticProviderSessionLoadResult.Loaded(session)
        }
        return OfflineSemanticProviderComposition(
            profileGeneration = SemanticProfileGeneration(1),
            sessionLoader = loader
        )
    }

    private fun artifact(): ValidatedSemanticModelArtifact =
        TestSemanticModelArtifacts.validated(File("/private/test/model.gguf"))

    private class FakeSession(
        private val onEmbed: (String) -> Unit = {}
    ) : SemanticProviderEmbeddingSession {
        var loaderCalls: Int = 0
        var closeCalls: Int = 0
        var failOnText: String? = null
        val embeddedTexts = mutableListOf<String>()

        override fun embed(preparedText: String): SemanticEmbeddingResult {
            embeddedTexts += preparedText
            onEmbed(preparedText)
            if (preparedText == failOnText) return SemanticEmbeddingResult.OperationFailed
            return SemanticEmbeddingResult.Embedded(unitVector())
        }

        override fun close(): SemanticEmbeddingCloseResult {
            closeCalls += 1
            return SemanticEmbeddingCloseResult.Closed
        }
    }

    private companion object {
        fun unitVector(): SemanticEmbeddingVector {
            val values = FloatArray(SemanticEmbeddingVector.DIMENSION)
            values[0] = 1f
            return SemanticEmbeddingVector(values)
        }
    }
}
