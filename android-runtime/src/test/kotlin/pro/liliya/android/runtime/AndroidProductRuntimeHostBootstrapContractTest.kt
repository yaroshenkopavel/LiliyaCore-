package pro.liliya.android.runtime

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import org.junit.Test
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.learning.LearningComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider

class AndroidProductRuntimeHostBootstrapContractTest {

    @Test
    fun ready_returns_the_exact_canonical_runtime() {
        val runtime = runtime(FakeHeart())
        val result = AndroidProductRuntimeHostBootstrap.start(
            AndroidProductRuntimeHostCreateFactory {
                AndroidProductRuntimeCreateResult.Ready(runtime)
            }
        )

        val ready = assertIs<AndroidProductRuntimeHostBootstrapResult.Ready>(result)
        assertSame(runtime, ready.runtime)
        assertEquals(HeartRuntimeState.READY, runtime.state())
    }

    @Test
    fun create_rejection_is_bounded_and_does_not_attempt_start() {
        val result = AndroidProductRuntimeHostBootstrap.start(
            AndroidProductRuntimeHostCreateFactory {
                AndroidProductRuntimeCreateResult.Rejected(
                    AndroidProductRuntimeCreateFailure.COMPOSITION_REJECTED
                )
            }
        )

        val rejected = assertIs<AndroidProductRuntimeHostBootstrapResult.Rejected>(result)
        assertEquals(
            AndroidProductRuntimeHostBootstrapFailure.CREATE_REJECTED,
            rejected.reason
        )
        assertEquals(null, rejected.cleanup)
    }

    @Test
    fun start_rejection_closes_constructed_runtime_fail_closed() {
        val heart = FakeHeart(
            startResult = HeartRuntimeStartResult.Failed(HeartRuntimePhase.STORAGE)
        )
        val runtime = runtime(heart)

        val result = AndroidProductRuntimeHostBootstrap.start(
            AndroidProductRuntimeHostCreateFactory {
                AndroidProductRuntimeCreateResult.Ready(runtime)
            }
        )

        val rejected = assertIs<AndroidProductRuntimeHostBootstrapResult.Rejected>(result)
        assertEquals(
            AndroidProductRuntimeHostBootstrapFailure.START_REJECTED,
            rejected.reason
        )
        assertEquals(HeartRuntimeCloseResult.Closed, rejected.cleanup)
        assertEquals(1, heart.closeCalls)
        assertEquals(HeartRuntimeState.CLOSED, runtime.state())
    }

    @Test
    fun create_exception_is_bounded_without_private_exception_text() {
        val result = AndroidProductRuntimeHostBootstrap.start(
            AndroidProductRuntimeHostCreateFactory {
                error("PRIVATE-HOST-BOOTSTRAP-FAILURE")
            }
        )

        val rejected = assertIs<AndroidProductRuntimeHostBootstrapResult.Rejected>(result)
        assertEquals(
            AndroidProductRuntimeHostBootstrapFailure.INTERNAL_FAILURE,
            rejected.reason
        )
        assertEquals(null, rejected.cleanup)
        assertEquals(
            "Rejected(reason=INTERNAL_FAILURE, cleanup=null)",
            rejected.toString()
        )
    }

    private fun runtime(
        heart: FakeHeart
    ): AndroidProductRuntimeAssembly {
        val foundation = foundation()
        return AndroidProductRuntimeAssembly(
            heart = heart,
            learning = LearningComposition(foundation),
            governedLearningFactory = AndroidProductRuntimeGovernedLearningFactory {
                AndroidHeartProductionGovernedLearningCreateResult.Ready(
                    dummyGoverned()
                )
            }
        )
    }

    private fun dummyGoverned(): AndroidHeartProductionGovernedLearningComposition {
        val bridge = object : AndroidHeartProductionGovernedLearningBridge {
            override fun state(): HeartRuntimeState = HeartRuntimeState.READY

            override fun mutationApplicationPort(
                authorizationGate: pro.liliya.core.learning.LearningApplicationMutationAuthorizationGate
            ): pro.liliya.core.learning.LearningApplicationMutationApplicationPort? = null

            override fun governedLearning(
                composition: pro.liliya.core.cognitive.CognitiveGovernedLearningComposition
            ): AndroidHeartGovernedLearningComposition? = null
        }
        val governed = AndroidHeartGovernedLearningComposition(
            governed = AndroidHeartGovernedLearningPort {
                error("not used by bootstrap contract")
            },
            semantic = AndroidHeartAppliedSemanticSyncPort {
                error("not used by bootstrap contract")
            },
            onSemanticUnavailable = {}
        )
        return AndroidHeartProductionGovernedLearningComposition(
            bridge = bridge,
            governed = governed
        )
    }

    private fun foundation(): FoundationComposition {
        val writer = InMemoryLogWriter()
        val correlations = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context ->
                StructuredLogger(context, writer)
            },
            correlationIds = CorrelationIdGenerator {
                "host-bootstrap-" + correlations.incrementAndGet()
            }
        )
    }

    private class FakeHeart(
        private val startResult: HeartRuntimeStartResult = HeartRuntimeStartResult.Ready
    ) : AndroidProductRuntimeHeartBridge {
        private var currentState = HeartRuntimeState.IDLE
        var closeCalls: Int = 0
            private set

        override fun state(): HeartRuntimeState = currentState

        override fun start(): HeartRuntimeStartResult {
            if (startResult == HeartRuntimeStartResult.Ready) {
                currentState = HeartRuntimeState.READY
            }
            return startResult
        }

        override fun recoverSemantic(): AndroidHeartSemanticRecoveryResult =
            AndroidHeartSemanticRecoveryResult.NotRequired

        override fun chat(): ProductChatHost? = null

        override fun conversation(
            maxRetainedMessages: Int,
            maxRetainedCharacters: Int,
            maxMessageCharacters: Int
        ): ProductConversationHost? = null

        override fun close(): HeartRuntimeCloseResult {
            closeCalls += 1
            currentState = HeartRuntimeState.CLOSED
            return HeartRuntimeCloseResult.Closed
        }
    }
}
