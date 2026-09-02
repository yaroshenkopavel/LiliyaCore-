package pro.liliya.android.llamacppengine

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import pro.liliya.core.modelengine.ModelEngineCloseFailure
import pro.liliya.core.modelengine.ModelEngineCloseResult
import pro.liliya.core.modelengine.ModelEngineHandleId
import pro.liliya.core.modelengine.ModelEngineInferenceFailure
import pro.liliya.core.modelengine.ModelEngineInferenceRequest
import pro.liliya.core.modelengine.ModelEngineInferenceResult
import pro.liliya.core.modelengine.ModelEngineLoadFailure

class LlamaCppSessionOwnershipContractTest {

    @Test
    fun policy_rejects_invalid_or_overlapping_resource_budgets() {
        assertFailsPolicy(context = 0)
        assertFailsPolicy(prompt = 0)
        assertFailsPolicy(generated = 0)
        assertFailsPolicy(batch = 0)
        assertFailsPolicy(microBatch = 0)
        assertFailsPolicy(threads = 0)
        assertFailsPolicy(promptChars = 0)
        assertFailsPolicy(promptBytes = 0)
        assertFailsPolicy(outputChars = 0)
        assertFailsPolicy(outputBytes = 0)
        assertFailsPolicy(context = 8, prompt = 9)
        assertFailsPolicy(context = 8, generated = 9)
        assertFailsPolicy(context = 8, prompt = 5, generated = 4)
        assertFailsPolicy(context = 8, batch = 9)
        assertFailsPolicy(context = 8, batch = 4, microBatch = 5)
        assertFailsPolicy(promptChars = 2, promptBytes = 9)
        assertFailsPolicy(outputChars = 2, outputBytes = 9)
    }

    @Test
    fun representation_budgets_reject_before_native_infer() {
        val native = FakeNativePort()
        val session = session(native)

        val promptChars = assertIs<ModelEngineInferenceResult.Rejected>(
            session.infer(ModelEngineInferenceRequest("123456789", maxOutputChars = 4))
        )
        assertEquals(ModelEngineInferenceFailure.RESOURCE_LIMIT_REJECTED, promptChars.reason)

        val promptBytes = assertIs<ModelEngineInferenceResult.Rejected>(
            session.infer(ModelEngineInferenceRequest("яяяяя", maxOutputChars = 4))
        )
        assertEquals(ModelEngineInferenceFailure.RESOURCE_LIMIT_REJECTED, promptBytes.reason)

        val outputChars = assertIs<ModelEngineInferenceResult.Rejected>(
            session.infer(ModelEngineInferenceRequest("ok", maxOutputChars = 9))
        )
        assertEquals(ModelEngineInferenceFailure.RESOURCE_LIMIT_REJECTED, outputChars.reason)
        assertEquals(0, native.inferCalls.get())
    }

    @Test
    fun output_character_budget_is_independent_and_close_is_idempotent() {
        val native = FakeNativePort().apply {
            inferResult = LlamaCppNativeInferenceResult.Succeeded("abcdefghij")
        }
        val session = session(native)

        val inferred = assertIs<ModelEngineInferenceResult.Succeeded>(
            session.infer(ModelEngineInferenceRequest("private", maxOutputChars = 4))
        )
        assertEquals("abcd", inferred.output)
        assertEquals(1, native.inferCalls.get())

        assertIs<ModelEngineCloseResult.Closed>(session.close())
        assertIs<ModelEngineCloseResult.Closed>(session.close())
        assertEquals(1, native.closeCalls.get())

        val afterClose = assertIs<ModelEngineInferenceResult.Rejected>(
            session.infer(ModelEngineInferenceRequest("another", maxOutputChars = 4))
        )
        assertEquals(ModelEngineInferenceFailure.SESSION_FAILED, afterClose.reason)
        assertEquals(1, native.inferCalls.get())
        assertFalse(session.handleId.toString().contains("native"))
        assertTrue(session.handleId.toString().contains("redacted"))
    }

    @Test
    fun failed_close_never_reopens_inference_and_can_retry_cleanup() {
        val native = FakeNativePort().apply {
            closeResults.add(
                LlamaCppNativeCloseResult.Failed(ModelEngineCloseFailure.CLOSE_FAILED)
            )
            closeResults.add(LlamaCppNativeCloseResult.Closed)
        }
        val session = session(native)

        val first = assertIs<ModelEngineCloseResult.Failed>(session.close())
        assertEquals(ModelEngineCloseFailure.CLOSE_FAILED, first.reason)

        val rejected = assertIs<ModelEngineInferenceResult.Rejected>(
            session.infer(ModelEngineInferenceRequest("private", maxOutputChars = 4))
        )
        assertEquals(ModelEngineInferenceFailure.SESSION_FAILED, rejected.reason)
        assertEquals(0, native.inferCalls.get())

        assertIs<ModelEngineCloseResult.Closed>(session.close())
        assertEquals(2, native.closeCalls.get())
        assertIs<ModelEngineCloseResult.Closed>(session.close())
        assertEquals(2, native.closeCalls.get())
    }

    @Test
    fun provider_exceptions_map_structurally_without_message_propagation() {
        val privateMessage = "/data/user/0/private/model.gguf private prompt"
        val native = FakeNativePort().apply {
            inferThrowable = IllegalStateException(privateMessage)
            closeThrowable = IllegalArgumentException(privateMessage)
        }
        val session = session(native)

        val inferred = assertIs<ModelEngineInferenceResult.Rejected>(
            session.infer(ModelEngineInferenceRequest("private", maxOutputChars = 4))
        )
        assertEquals(ModelEngineInferenceFailure.PROVIDER_FAILED, inferred.reason)
        assertFalse(inferred.toString().contains(privateMessage))

        val closed = assertIs<ModelEngineCloseResult.Failed>(session.close())
        assertEquals(ModelEngineCloseFailure.PROVIDER_FAILED, closed.reason)
        assertFalse(closed.toString().contains(privateMessage))
    }

    @Test
    fun close_waits_for_in_flight_infer_and_no_new_infer_runs_after_close() {
        val inferEntered = CountDownLatch(1)
        val releaseInfer = CountDownLatch(1)
        val closeEntered = CountDownLatch(1)
        val native = FakeNativePort().apply {
            onInfer = {
                inferEntered.countDown()
                check(releaseInfer.await(5, TimeUnit.SECONDS))
            }
            onClose = { closeEntered.countDown() }
        }
        val session = session(native)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val inferFuture = executor.submit<ModelEngineInferenceResult> {
                session.infer(ModelEngineInferenceRequest("private", maxOutputChars = 4))
            }
            assertTrue(inferEntered.await(5, TimeUnit.SECONDS))

            val closeFuture = executor.submit<ModelEngineCloseResult> { session.close() }
            assertFalse(closeEntered.await(200, TimeUnit.MILLISECONDS))

            releaseInfer.countDown()
            assertIs<ModelEngineInferenceResult.Succeeded>(inferFuture.get(5, TimeUnit.SECONDS))
            assertIs<ModelEngineCloseResult.Closed>(closeFuture.get(5, TimeUnit.SECONDS))
            assertTrue(closeEntered.await(1, TimeUnit.SECONDS))

            val rejected = assertIs<ModelEngineInferenceResult.Rejected>(
                session.infer(ModelEngineInferenceRequest("later", maxOutputChars = 4))
            )
            assertEquals(ModelEngineInferenceFailure.SESSION_FAILED, rejected.reason)
            assertEquals(1, native.inferCalls.get())
            assertEquals(1, native.closeCalls.get())
        } finally {
            releaseInfer.countDown()
            executor.shutdownNow()
        }
    }

    private fun session(native: LlamaCppNativeSessionPort): LlamaCppSessionOwnership =
        LlamaCppSessionOwnership(
            handleId = ModelEngineHandleId("test-session-id"),
            nativeSessionId = 73L,
            nativePort = native,
            policy = policy()
        )

    private fun policy() = LlamaCppEnginePolicy(
        contextTokens = 16,
        maxPromptTokens = 8,
        maxGeneratedTokens = 8,
        batchTokens = 8,
        microBatchTokens = 4,
        threadCount = 2,
        maxPromptChars = 8,
        maxPromptUtf8Bytes = 8,
        maxOutputChars = 8,
        maxOutputUtf8Bytes = 16,
        useMmap = true
    )

    private fun assertFailsPolicy(
        context: Int = 16,
        prompt: Int = 8,
        generated: Int = 8,
        batch: Int = 8,
        microBatch: Int = 4,
        threads: Int = 2,
        promptChars: Int = 8,
        promptBytes: Int = 8,
        outputChars: Int = 8,
        outputBytes: Int = 16
    ) {
        var failed = false
        try {
            LlamaCppEnginePolicy(
                contextTokens = context,
                maxPromptTokens = prompt,
                maxGeneratedTokens = generated,
                batchTokens = batch,
                microBatchTokens = microBatch,
                threadCount = threads,
                maxPromptChars = promptChars,
                maxPromptUtf8Bytes = promptBytes,
                maxOutputChars = outputChars,
                maxOutputUtf8Bytes = outputBytes,
                useMmap = true
            )
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }

    private class FakeNativePort : LlamaCppNativeSessionPort {
        val inferCalls = AtomicInteger(0)
        val closeCalls = AtomicInteger(0)
        val closeResults = ArrayDeque<LlamaCppNativeCloseResult>()
        var inferResult: LlamaCppNativeInferenceResult =
            LlamaCppNativeInferenceResult.Succeeded("ok")
        var inferThrowable: Throwable? = null
        var closeThrowable: Throwable? = null
        var onInfer: (() -> Unit)? = null
        var onClose: (() -> Unit)? = null

        override fun load(
            sourcePath: String,
            policy: LlamaCppEnginePolicy
        ): LlamaCppNativeLoadResult =
            LlamaCppNativeLoadResult.Rejected(ModelEngineLoadFailure.LOAD_REJECTED)

        override fun infer(
            nativeSessionId: Long,
            promptUtf8: ByteArray,
            maxOutputChars: Int
        ): LlamaCppNativeInferenceResult {
            inferCalls.incrementAndGet()
            onInfer?.invoke()
            inferThrowable?.let { throw it }
            return inferResult
        }

        override fun close(nativeSessionId: Long): LlamaCppNativeCloseResult {
            closeCalls.incrementAndGet()
            onClose?.invoke()
            closeThrowable?.let { throw it }
            return if (closeResults.isEmpty()) {
                LlamaCppNativeCloseResult.Closed
            } else {
                closeResults.removeFirst()
            }
        }
    }
}
