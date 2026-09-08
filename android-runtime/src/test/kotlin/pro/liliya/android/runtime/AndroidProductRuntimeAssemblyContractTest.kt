package pro.liliya.android.runtime

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.junit.Test
import pro.liliya.core.cognitive.CognitiveGovernedLearningFailure
import pro.liliya.core.cognitive.CognitiveGovernedLearningResult
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.learning.LearningComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider

class AndroidProductRuntimeAssemblyContractTest {

    @Test
    fun start_passes_the_exact_shared_learning_owner_to_governed_learning() {
        val foundation = foundation()
        val learning = LearningComposition(foundation)
        val heart = FakeHeart()
        var received: LearningComposition? = null
        val runtime = AndroidProductRuntimeAssembly(
            heart = heart,
            learning = learning,
            governedLearningFactory = AndroidProductRuntimeGovernedLearningFactory {
                received = it
                readyGoverned(heart)
            }
        )

        assertEquals(AndroidProductRuntimeStartResult.Ready, runtime.start())
        assertSame(learning, received)
        assertNotNull(runtime.learningFollowUp())
        assertEquals(1, heart.startCalls)
        assertEquals(0, heart.closeCalls)
    }

    @Test
    fun heart_start_exception_is_bounded_and_does_not_touch_governed_learning() {
        val foundation = foundation()
        val heart = object : AndroidProductRuntimeHeartBridge {
            override fun state(): HeartRuntimeState = HeartRuntimeState.IDLE
            override fun start(): HeartRuntimeStartResult = error("PRIVATE-HEART-FAILURE")
            override fun chat(): ProductChatHost? = null
            override fun conversation(
                maxRetainedMessages: Int,
                maxRetainedCharacters: Int,
                maxMessageCharacters: Int
            ): ProductConversationHost? = null
            override fun close(): HeartRuntimeCloseResult = HeartRuntimeCloseResult.Closed
        }
        var governedCalls = 0
        val runtime = AndroidProductRuntimeAssembly(
            heart = heart,
            learning = LearningComposition(foundation),
            governedLearningFactory = AndroidProductRuntimeGovernedLearningFactory {
                governedCalls += 1
                error("must not run")
            }
        )

        val failed = assertIs<AndroidProductRuntimeStartResult.InternalFailure>(runtime.start())
        assertNull(failed.cleanup)
        assertEquals(0, governedCalls)
    }

    @Test
    fun governed_learning_exception_is_bounded_and_compensates_ready_heart() {
        val foundation = foundation()
        val heart = FakeHeart()
        val runtime = AndroidProductRuntimeAssembly(
            heart = heart,
            learning = LearningComposition(foundation),
            governedLearningFactory = AndroidProductRuntimeGovernedLearningFactory {
                error("PRIVATE-GOVERNED-FAILURE")
            }
        )

        val failed = assertIs<AndroidProductRuntimeStartResult.InternalFailure>(runtime.start())
        assertEquals(HeartRuntimeCloseResult.Closed, failed.cleanup)
        assertEquals(1, heart.closeCalls)
        assertEquals(HeartRuntimeState.CLOSED, runtime.state())
        assertNull(runtime.learningFollowUp())
    }

    @Test
    fun heart_start_failure_never_touches_governed_learning() {
        val foundation = foundation()
        val learning = LearningComposition(foundation)
        val heart = FakeHeart(
            startResult = HeartRuntimeStartResult.Failed(HeartRuntimePhase.STORAGE)
        )
        var governedCalls = 0
        val runtime = AndroidProductRuntimeAssembly(
            heart = heart,
            learning = learning,
            governedLearningFactory = AndroidProductRuntimeGovernedLearningFactory {
                governedCalls += 1
                error("governed learning must not run")
            }
        )

        val rejected = assertIs<AndroidProductRuntimeStartResult.HeartRejected>(
            runtime.start()
        )

        assertEquals(
            HeartRuntimeStartResult.Failed(HeartRuntimePhase.STORAGE),
            rejected.result
        )
        assertEquals(0, governedCalls)
        assertNull(runtime.learningFollowUp())
    }

    @Test
    fun governed_learning_rejection_compensates_by_closing_ready_heart() {
        val foundation = foundation()
        val learning = LearningComposition(foundation)
        val heart = FakeHeart()
        val runtime = AndroidProductRuntimeAssembly(
            heart = heart,
            learning = learning,
            governedLearningFactory = AndroidProductRuntimeGovernedLearningFactory {
                AndroidHeartProductionGovernedLearningCreateResult.Rejected(
                    AndroidHeartProductionGovernedLearningCreateFailure
                        .MUTATION_APPLICATION_UNAVAILABLE
                )
            }
        )

        val rejected =
            assertIs<AndroidProductRuntimeStartResult.GovernedLearningRejected>(
                runtime.start()
            )

        assertEquals(
            AndroidHeartProductionGovernedLearningCreateFailure
                .MUTATION_APPLICATION_UNAVAILABLE,
            rejected.reason
        )
        assertEquals(HeartRuntimeCloseResult.Closed, rejected.cleanup)
        assertEquals(1, heart.closeCalls)
        assertEquals(HeartRuntimeState.CLOSED, runtime.state())
        assertNull(runtime.learningFollowUp())
    }

    @Test
    fun ready_runtime_exposes_existing_product_surfaces_only_while_heart_is_ready() {
        val foundation = foundation()
        val learning = LearningComposition(foundation)
        val conversation = conversationHost()
        val chat = chatHost()
        val heart = FakeHeart(
            chat = chat,
            conversation = conversation
        )
        val runtime = AndroidProductRuntimeAssembly(
            heart = heart,
            learning = learning,
            governedLearningFactory = AndroidProductRuntimeGovernedLearningFactory {
                readyGoverned(heart)
            }
        )

        assertNull(runtime.chat())
        assertNull(runtime.conversation(4, 512, 128))
        assertNull(runtime.learningFollowUp())

        assertEquals(AndroidProductRuntimeStartResult.Ready, runtime.start())
        assertSame(chat, runtime.chat())
        assertSame(conversation, runtime.conversation(4, 512, 128))
        assertNotNull(runtime.learningFollowUp())

        heart.forceState(HeartRuntimeState.FAILED)
        assertNull(runtime.chat())
        assertNull(runtime.conversation(4, 512, 128))
        assertNull(runtime.learningFollowUp())
    }

    @Test
    fun close_delegates_to_heart_and_removes_new_product_surfaces() {
        val foundation = foundation()
        val learning = LearningComposition(foundation)
        val heart = FakeHeart(
            chat = chatHost(),
            conversation = conversationHost()
        )
        val runtime = AndroidProductRuntimeAssembly(
            heart = heart,
            learning = learning,
            governedLearningFactory = AndroidProductRuntimeGovernedLearningFactory {
                readyGoverned(heart)
            }
        )

        assertEquals(AndroidProductRuntimeStartResult.Ready, runtime.start())
        assertNotNull(runtime.learningFollowUp())

        assertEquals(HeartRuntimeCloseResult.Closed, runtime.close())
        assertEquals(1, heart.closeCalls)
        assertEquals(HeartRuntimeState.CLOSED, runtime.state())
        assertNull(runtime.chat())
        assertNull(runtime.conversation(4, 512, 128))
        assertNull(runtime.learningFollowUp())
    }

    private class FakeHeart(
        private val startResult: HeartRuntimeStartResult = HeartRuntimeStartResult.Ready,
        private val chat: ProductChatHost? = null,
        private val conversation: ProductConversationHost? = null
    ) : AndroidProductRuntimeHeartBridge {
        private var current = HeartRuntimeState.IDLE
        var startCalls = 0
            private set
        var closeCalls = 0
            private set

        override fun state(): HeartRuntimeState = current

        override fun start(): HeartRuntimeStartResult {
            startCalls += 1
            if (startResult == HeartRuntimeStartResult.Ready) {
                current = HeartRuntimeState.READY
            } else {
                current = HeartRuntimeState.FAILED
            }
            return startResult
        }

        override fun chat(): ProductChatHost? = chat

        override fun conversation(
            maxRetainedMessages: Int,
            maxRetainedCharacters: Int,
            maxMessageCharacters: Int
        ): ProductConversationHost? = conversation

        override fun close(): HeartRuntimeCloseResult {
            closeCalls += 1
            current = HeartRuntimeState.CLOSED
            return HeartRuntimeCloseResult.Closed
        }

        fun forceState(state: HeartRuntimeState) {
            current = state
        }
    }

    private fun readyGoverned(
        heart: FakeHeart
    ): AndroidHeartProductionGovernedLearningCreateResult.Ready {
        val governed = AndroidHeartGovernedLearningComposition(
            governed = AndroidHeartGovernedLearningPort {
                CognitiveGovernedLearningResult.Rejected(
                    CognitiveGovernedLearningFailure.CANDIDATE_MISSING_OR_MISMATCH
                )
            },
            semantic = AndroidHeartAppliedSemanticSyncPort {
                error("semantic sync must not run for rejected learning")
            },
            onSemanticUnavailable = {}
        )
        val bridge = object : AndroidHeartProductionGovernedLearningBridge {
            override fun state(): HeartRuntimeState = heart.state()
            override fun mutationApplicationPort(
                authorizationGate:
                    pro.liliya.core.learning.LearningApplicationMutationAuthorizationGate
            ): pro.liliya.core.learning.LearningApplicationMutationApplicationPort? = null
            override fun governedLearning(
                composition:
                    pro.liliya.core.cognitive.CognitiveGovernedLearningComposition
            ): AndroidHeartGovernedLearningComposition? = null
        }
        return AndroidHeartProductionGovernedLearningCreateResult.Ready(
            AndroidHeartProductionGovernedLearningComposition(
                bridge = bridge,
                governed = governed
            )
        )
    }

    private fun conversationHost(): ProductConversationHost =
        ProductConversationHost(
            sessionId = pro.liliya.core.cognitive.CognitiveConversationSessionId(
                "product-runtime-test"
            ),
            maxInputChars = 256,
            maxTurnIdChars = 64,
            maxRetainedMessages = 4,
            maxRetainedCharacters = 512,
            maxMessageCharacters = 128,
            turnIds = ProductConversationTurnIdSource { "turn" },
            turns = ProductConversationTurnRunner { request, _, _ ->
                ProductTurnResult.Rejected(
                    reason = ProductTurnFailure.HEART_NOT_READY
                )
            }
        )

    private fun chatHost(): ProductChatHost =
        ProductChatHost(
            maxInputChars = 256,
            maxTurnIdChars = 64,
            turnIds = ProductChatTurnIdSource { "turn" },
            turns = ProductChatTurnRunner { _, _ ->
                ProductTurnResult.Rejected(
                    reason = ProductTurnFailure.HEART_NOT_READY
                )
            }
        )

    private fun foundation(): FoundationComposition {
        val logs = InMemoryLogWriter()
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "product-runtime-test" }
        )
    }
}
