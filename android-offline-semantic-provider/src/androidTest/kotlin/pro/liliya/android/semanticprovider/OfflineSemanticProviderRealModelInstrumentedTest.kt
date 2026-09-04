package pro.liliya.android.semanticprovider

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineSemanticProviderRealModelInstrumentedTest {

    @Test
    fun pinned_multilingual_e5_onnx_loads_and_closes_without_embedding() {
        withFixture { fixture, root, selection ->
            val validated = assertIs<SemanticModelArtifactValidationResult.Validated>(
                fixtureValidator(root, selection).validate(fixture, fixtureSpec(selection))
            ).artifact

            val session = assertIs<SemanticEmbeddingSessionLoadResult.Loaded>(
                SemanticEmbeddingSessionLoader(testPolicy()).load(validated)
            ).session

            assertIs<SemanticEmbeddingCloseResult.Closed>(session.close())
            assertIs<SemanticEmbeddingCloseResult.StaleOrAlreadyClosed>(session.close())
        }
    }

    @Test
    fun generation_and_semantic_jni_coexist_in_one_process() {
        val generationBridge = Class.forName(
            "pro.liliya.android.llamacppengine.LlamaCppNativeBridge"
        )
        val instanceField = generationBridge.getDeclaredField("INSTANCE").apply {
            isAccessible = true
        }
        val nativeLinkProbe = generationBridge.getDeclaredMethod("nativeLinkProbe").apply {
            isAccessible = true
        }
        val generationProbe = nativeLinkProbe.invoke(instanceField.get(null)) as Int

        assertTrue(generationProbe > 0, "generation JNI link probe failed")
        assertTrue(SemanticNativeBridge.nativeLinkProbe() > 0, "semantic JNI link probe failed")
    }

    @Test
    fun native_registry_allows_only_one_live_session_and_reopens_after_close() {
        withFixture { fixture, root, selection ->
            val validated = assertIs<SemanticModelArtifactValidationResult.Validated>(
                fixtureValidator(root, selection).validate(fixture, fixtureSpec(selection))
            ).artifact
            val loader = SemanticEmbeddingSessionLoader(testPolicy())

            val first = assertIs<SemanticEmbeddingSessionLoadResult.Loaded>(
                loader.load(validated)
            ).session
            try {
                assertEquals(
                    SemanticEmbeddingSessionLoadResult.ResourceRejected,
                    loader.load(validated)
                )
            } finally {
                assertIs<SemanticEmbeddingCloseResult.Closed>(first.close())
            }

            val reopened = assertIs<SemanticEmbeddingSessionLoadResult.Loaded>(
                loader.load(validated)
            ).session
            assertIs<SemanticEmbeddingCloseResult.Closed>(reopened.close())
        }
    }

    @Test
    fun pinned_model_rejects_more_than_512_tokens_without_truncation() {
        withSession { session ->
            val overTokenBound = "query: " + List(600) { "hello" }.joinToString(" ")
            assertTrue(
                overTokenBound.toByteArray().size <= SemanticTextProfile.MAX_PREPARED_UTF8_BYTES
            )
            assertEquals(
                SemanticEmbeddingResult.ResourceRejected,
                session.embed(overTokenBound)
            )
        }
    }

    @Test
    fun pinned_multilingual_e5_onnx_runs_through_validator_native_session_and_close() {
        withFixture { fixture, root, selection ->
            val validator = fixtureValidator(root, selection)
            val validated = assertIs<SemanticModelArtifactValidationResult.Validated>(
                validator.validate(fixture, fixtureSpec(selection))
            ).artifact

            val session = assertIs<SemanticEmbeddingSessionLoadResult.Loaded>(
                SemanticEmbeddingSessionLoader(testPolicy()).load(validated)
            ).session

            val vector = assertIs<SemanticEmbeddingResult.Embedded>(
                session.embed("query: where did I leave my keys?")
            ).vector

            assertEquals(SemanticEmbeddingVector.DIMENSION, vector.copyValues().size)
            assertNormalized(vector)
            assertIs<SemanticEmbeddingCloseResult.Closed>(session.close())
            assertIs<SemanticEmbeddingCloseResult.StaleOrAlreadyClosed>(session.close())
            assertIs<SemanticEmbeddingResult.StaleSession>(
                session.embed("query: this must not run after close")
            )
        }
    }

    @Test
    fun pinned_model_preserves_relevance_order_for_english_russian_and_ukrainian() {
        withSession { session ->
            assertRelevantRanksHigher(
                session = session,
                query = "query: where did I leave my keys?",
                relevant = "passage: I left the keys on the kitchen table.",
                irrelevant = "passage: Whales migrate through the ocean."
            )
            assertRelevantRanksHigher(
                session = session,
                query = "query: где я оставил ключи?",
                relevant = "passage: Я оставил ключи на кухонном столе.",
                irrelevant = "passage: Киты мигрируют через океан."
            )
            assertRelevantRanksHigher(
                session = session,
                query = "query: де я залишив ключі?",
                relevant = "passage: Я залишив ключі на кухонному столі.",
                irrelevant = "passage: Кити мігрують через океан."
            )
        }
    }

    @Test
    fun pinned_model_preserves_cross_language_relevance_for_russian_ukrainian_and_english() {
        withSession { session ->
            assertRelevantRanksHigher(
                session = session,
                query = "query: где я оставил ключи от квартиры?",
                relevant = "passage: Після вечері ключі від квартири залишилися на кухонному столі.",
                irrelevant = "passage: Сьогодні біля узбережжя спостерігали міграцію китів."
            )
            assertRelevantRanksHigher(
                session = session,
                query = "query: де лежать ключі від квартири?",
                relevant = "passage: После ужина ключи от квартиры остались на кухонном столе.",
                irrelevant = "passage: Сегодня у побережья наблюдали миграцию китов."
            )
            assertRelevantRanksHigher(
                session = session,
                query = "query: where are the apartment keys?",
                relevant = "passage: После ужина ключи от квартиры остались на кухонном столе.",
                irrelevant = "passage: Сегодня у побережья наблюдали миграцию китов."
            )
            assertRelevantRanksHigher(
                session = session,
                query = "query: где лежат ключи от квартиры?",
                relevant = "passage: After dinner, the apartment keys were left on the kitchen table.",
                irrelevant = "passage: Whales migrate long distances through the ocean each year."
            )
        }
    }

    @Test
    fun pinned_model_rejects_reference_validated_lexical_overlap_distractors() {
        withSession { session ->
            assertRelevantRanksHigher(
                session = session,
                query = "query: как утром добраться до железнодорожного вокзала?",
                relevant = "passage: Утром до железнодорожного вокзала удобнее всего доехать на автобусе номер двенадцать.",
                irrelevant = "passage: Железнодорожный вокзал утром изображён на фотографии в туристическом буклете."
            )
            assertRelevantRanksHigher(
                session = session,
                query = "query: где хранится мой паспорт?",
                relevant = "passage: Мой паспорт хранится дома в синей папке в верхнем ящике письменного стола.",
                irrelevant = "passage: В моём паспорте указано место рождения и дата выдачи документа."
            )
        }
    }

    @Test
    fun pinned_model_preserves_same_topic_paraphrase_relevance() {
        withSession { session ->
            assertRelevantRanksHigher(
                session = session,
                query = "query: как мне добраться до железнодорожного вокзала утром?",
                relevant = "passage: Утром до станции поездов удобнее всего доехать на автобусе номер двенадцать.",
                irrelevant = "passage: Утром я обычно завариваю кофе и открываю окно на кухне."
            )
            assertRelevantRanksHigher(
                session = session,
                query = "query: як вранці дістатися до залізничного вокзалу?",
                relevant = "passage: До станції поїздів у ранковий час найзручніше їхати дванадцятим автобусом.",
                irrelevant = "passage: Вранці я зазвичай готую каву та відчиняю кухонне вікно."
            )
        }
    }

    private fun withSession(block: (SemanticEmbeddingSessionOwnership) -> Unit) {
        withFixture { fixture, root, selection ->
            val validated = assertIs<SemanticModelArtifactValidationResult.Validated>(
                fixtureValidator(root, selection).validate(fixture, fixtureSpec(selection))
            ).artifact
            val session = assertIs<SemanticEmbeddingSessionLoadResult.Loaded>(
                SemanticEmbeddingSessionLoader(testPolicy()).load(validated)
            ).session
            try {
                block(session)
            } finally {
                assertIs<SemanticEmbeddingCloseResult.Closed>(session.close())
            }
        }
    }

    private fun assertRelevantRanksHigher(
        session: SemanticEmbeddingSessionOwnership,
        query: String,
        relevant: String,
        irrelevant: String
    ) {
        val queryVector = embedded(session, query)
        val relevantVector = embedded(session, relevant)
        val irrelevantVector = embedded(session, irrelevant)

        val relevantScore = dot(queryVector, relevantVector)
        val irrelevantScore = dot(queryVector, irrelevantVector)
        assertTrue(
            relevantScore > irrelevantScore,
            "expected relevant passage to outrank distractor without relying on an absolute threshold"
        )
    }

    private fun embedded(
        session: SemanticEmbeddingSessionOwnership,
        text: String
    ): SemanticEmbeddingVector =
        assertIs<SemanticEmbeddingResult.Embedded>(session.embed(text)).vector

    private fun dot(left: SemanticEmbeddingVector, right: SemanticEmbeddingVector): Float {
        val a = left.copyValues()
        val b = right.copyValues()
        var sum = 0f
        for (index in a.indices) sum += a[index] * b[index]
        return sum
    }

    private fun assertNormalized(vector: SemanticEmbeddingVector) {
        val values = vector.copyValues()
        var normSquared = 0.0
        values.forEach { value -> normSquared += value.toDouble() * value.toDouble() }
        assertTrue(abs(normSquared - 1.0) <= 0.001)
    }

    private fun withFixture(
        block: (
            File,
            File,
            SelfReproducedSemanticModelFixture.Selection
        ) -> Unit
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val testContext = instrumentation.context
        val selection = SelfReproducedSemanticModelFixture.select(
            InstrumentationRegistry.getArguments()
        )
        val root = File(targetContext.filesDir, "offline-semantic-real-model-test")
        root.deleteRecursively()
        check(root.mkdirs())
        val fixture = File(root, selection.identity.modelFileName)
        val tokenizerFixture = File(root, selection.identity.tokenizerFileName)
        try {
            testContext.assets.open(selection.identity.modelFileName).use { input ->
                fixture.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
            }
            testContext.assets.open(selection.identity.tokenizerFileName).use { input ->
                tokenizerFixture.outputStream().use { output ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                }
            }
            assertEquals(selection.identity.expectedSizeBytes, fixture.length())
            assertEquals(
                selection.identity.tokenizerExpectedSizeBytes,
                tokenizerFixture.length()
            )
            block(fixture, root, selection)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun fixtureSpec(
        selection: SelfReproducedSemanticModelFixture.Selection
    ): SemanticModelArtifactSpec =
        SemanticModelArtifactSpec(selection.identity)

    private fun fixtureValidator(
        root: File,
        selection: SelfReproducedSemanticModelFixture.Selection
    ) = SemanticModelArtifactValidator(
        root,
        selection.identity,
        selection.acceptance
    )

    private fun testPolicy() = SemanticEmbeddingPolicy(
        contextTokens = 512,
        batchTokens = 512,
        threadCount = 1,
        maxInputUtf8Bytes = SemanticTextProfile.MAX_PREPARED_UTF8_BYTES,
        useMmap = true
    )
}
