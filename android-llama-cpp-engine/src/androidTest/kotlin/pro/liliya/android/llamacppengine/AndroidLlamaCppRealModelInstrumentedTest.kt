package pro.liliya.android.llamacppengine

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.android.protectedmodel.enginesource.AndroidAppPrivateStagedModelEngineLoader
import pro.liliya.android.protectedmodel.staging.AndroidAppPrivateProtectedModelStagingBackend
import pro.liliya.android.protectedmodel.staging.AndroidProtectedModelStagingPolicy
import pro.liliya.core.modelengine.ModelEngineCloseResult
import pro.liliya.core.modelengine.ModelEngineInferenceRequest
import pro.liliya.core.modelengine.ModelEngineInferenceResult
import pro.liliya.core.modelengine.ModelEngineLoadResult
import pro.liliya.core.modelengine.ModelEngineStreamControl
import pro.liliya.core.modelengine.ModelEngineStreamingSessionOwnership
import pro.liliya.core.modelengine.ModelEngineStreamingSink
import pro.liliya.core.modelengine.StagedModelEngineLoadCoordinator
import pro.liliya.core.protectedmodel.LargeProtectedModelPayloadProfile
import pro.liliya.core.protectedmodel.LargeProtectedModelStagedSourceOwnership
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingAppendResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingBudgets
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingCoordinator
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingPublishResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingRequest
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingRetireResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingStartResult
import pro.liliya.core.protectedmodel.ProtectedModelGeneration
import pro.liliya.core.protectedmodel.ProtectedModelPackageId
import pro.liliya.core.protectedmodel.ProtectedModelReference

@RunWith(AndroidJUnit4::class)
class AndroidLlamaCppRealModelInstrumentedTest {

    @Test
    fun verified_upstream_fixture_reaches_real_load_infer_close_through_capability_path() =
        withCleanRoot { targetContext, testContext ->
            val backend = backend(targetContext)
            val staging = coordinator(backend, STORIES_15M_BYTES)
            val ownership = testContext.assets.open(STORIES_15M_ASSET).use { input ->
                publishSegmented(
                    coordinator = staging,
                    input = input,
                    totalBytes = STORIES_15M_BYTES,
                    packageId = "slice7-stories15m"
                )
            }

            val prompt = "Once upon a time"
            val broadLoader = AndroidLlamaCppPhysicalEngineLoader(
                enginePolicy(maxGeneratedTokens = 8)
            )
            val broadCoordinator = StagedModelEngineLoadCoordinator(
                staging,
                AndroidAppPrivateStagedModelEngineLoader(backend, broadLoader)
            )
            val broadLoaded = assertIs<ModelEngineLoadResult.Loaded>(
                broadCoordinator.load(ownership)
            )
            val broadInference = assertIs<ModelEngineInferenceResult.Succeeded>(
                broadLoaded.ownership.infer(
                    ModelEngineInferenceRequest(
                        prompt = prompt,
                        maxOutputChars = 64
                    )
                )
            )
            assertTrue(broadInference.output.length <= 64)
            assertIs<ModelEngineCloseResult.Closed>(broadLoaded.ownership.close())

            val oneTokenLoader = AndroidLlamaCppPhysicalEngineLoader(
                enginePolicy(maxGeneratedTokens = 1)
            )
            val oneTokenCoordinator = StagedModelEngineLoadCoordinator(
                staging,
                AndroidAppPrivateStagedModelEngineLoader(backend, oneTokenLoader)
            )
            val oneTokenLoaded = assertIs<ModelEngineLoadResult.Loaded>(
                oneTokenCoordinator.load(ownership)
            )
            val oneTokenInference = assertIs<ModelEngineInferenceResult.Succeeded>(
                oneTokenLoaded.ownership.infer(
                    ModelEngineInferenceRequest(
                        prompt = prompt,
                        maxOutputChars = 64
                    )
                )
            )
            assertTrue(oneTokenInference.output.isNotEmpty())
            assertTrue(oneTokenInference.output.length < 64)
            assertTrue(broadInference.output.startsWith(oneTokenInference.output))
            assertTrue(broadInference.output.length > oneTokenInference.output.length)
            assertIs<ModelEngineCloseResult.Closed>(oneTokenLoaded.ownership.close())

            assertIs<LargeProtectedModelStagingRetireResult.Retired>(ownership.retire())
        }

    @Test
    fun verified_fixture_streams_incrementally_cancels_and_reuses_same_native_session() =
        withCleanRoot { targetContext, testContext ->
            val backend = backend(targetContext)
            val staging = coordinator(backend, STORIES_15M_BYTES)
            val ownership = testContext.assets.open(STORIES_15M_ASSET).use { input ->
                publishSegmented(
                    coordinator = staging,
                    input = input,
                    totalBytes = STORIES_15M_BYTES,
                    packageId = "streaming-stories15m"
                )
            }

            val loader = AndroidLlamaCppPhysicalEngineLoader(
                enginePolicy(maxGeneratedTokens = 8)
            )
            val coordinator = StagedModelEngineLoadCoordinator(
                staging,
                AndroidAppPrivateStagedModelEngineLoader(backend, loader)
            )
            val loaded = assertIs<ModelEngineLoadResult.Loaded>(
                coordinator.load(ownership)
            )
            val streaming = assertIs<ModelEngineStreamingSessionOwnership>(
                loaded.ownership
            )

            val chunks = mutableListOf<String>()
            val sequences = mutableListOf<Long>()
            val streamed = assertIs<ModelEngineInferenceResult.Succeeded>(
                streaming.stream(
                    ModelEngineInferenceRequest(
                        prompt = "Once upon a time",
                        maxOutputChars = 64
                    ),
                    ModelEngineStreamingSink { chunk ->
                        sequences += chunk.sequence
                        chunks += chunk.text
                        ModelEngineStreamControl.CONTINUE
                    }
                )
            )

            assertTrue(chunks.size > 1)
            assertEquals(
                (1L..chunks.size.toLong()).toList(),
                sequences
            )
            assertEquals(streamed.output, chunks.joinToString(""))
            assertTrue(streamed.output.isNotBlank())

            var cancellationChunks = 0
            val cancelled = assertIs<ModelEngineInferenceResult.Rejected>(
                streaming.stream(
                    ModelEngineInferenceRequest(
                        prompt = "Once upon a time",
                        maxOutputChars = 64
                    ),
                    ModelEngineStreamingSink {
                        cancellationChunks += 1
                        ModelEngineStreamControl.STOP
                    }
                )
            )
            assertEquals(
                pro.liliya.core.modelengine.ModelEngineInferenceFailure.CANCELLED,
                cancelled.reason
            )
            assertEquals(1, cancellationChunks)

            val reused = assertIs<ModelEngineInferenceResult.Succeeded>(
                loaded.ownership.infer(
                    ModelEngineInferenceRequest(
                        prompt = "Once upon a time",
                        maxOutputChars = 64
                    )
                )
            )
            assertTrue(reused.output.isNotBlank())

            assertIs<ModelEngineCloseResult.Closed>(loaded.ownership.close())
            assertIs<LargeProtectedModelStagingRetireResult.Retired>(ownership.retire())
        }

    @Test
    fun non_gguf_source_fails_closed_and_releases_engine_use_lease() =
        withCleanRoot { targetContext, _ ->
            val invalidBytes = "not-a-gguf-model".encodeToByteArray()
            val backend = backend(targetContext)
            val staging = coordinator(backend, invalidBytes.size.toLong())
            val ownership = try {
                publishSegmented(
                    coordinator = staging,
                    input = ByteArrayInputStream(invalidBytes),
                    totalBytes = invalidBytes.size.toLong(),
                    packageId = "slice7-invalid-gguf"
                )
            } finally {
                invalidBytes.fill(0)
            }

            val physicalLoader = AndroidLlamaCppPhysicalEngineLoader(enginePolicy())
            val stagedLoader = AndroidAppPrivateStagedModelEngineLoader(backend, physicalLoader)
            val loadCoordinator = StagedModelEngineLoadCoordinator(staging, stagedLoader)

            assertIs<ModelEngineLoadResult.Rejected>(loadCoordinator.load(ownership))
            assertIs<LargeProtectedModelStagingRetireResult.Retired>(ownership.retire())
        }

    @Test
    fun truncated_gguf_source_fails_closed_and_releases_engine_use_lease() =
        withCleanRoot { targetContext, testContext ->
            val truncatedBytes = testContext.assets.open(STORIES_15M_ASSET).use { input ->
                readExactSegment(input, TRUNCATED_GGUF_BYTES)
            }
            assertEquals(TRUNCATED_GGUF_BYTES, truncatedBytes.size)

            val backend = backend(targetContext)
            val staging = coordinator(backend, truncatedBytes.size.toLong())
            val ownership = try {
                publishSegmented(
                    coordinator = staging,
                    input = ByteArrayInputStream(truncatedBytes),
                    totalBytes = truncatedBytes.size.toLong(),
                    packageId = "slice7-truncated-gguf"
                )
            } finally {
                truncatedBytes.fill(0)
            }

            val physicalLoader = AndroidLlamaCppPhysicalEngineLoader(enginePolicy())
            val stagedLoader = AndroidAppPrivateStagedModelEngineLoader(backend, physicalLoader)
            val loadCoordinator = StagedModelEngineLoadCoordinator(staging, stagedLoader)

            assertIs<ModelEngineLoadResult.Rejected>(loadCoordinator.load(ownership))
            assertIs<LargeProtectedModelStagingRetireResult.Retired>(ownership.retire())
        }

    @Test
    fun unknown_native_session_id_fails_closed_structurally() {
        val promptUtf8 = "probe".encodeToByteArray()
        val packet = try {
            LlamaCppNativeBridge.nativeInfer(
                nativeSessionId = Long.MAX_VALUE,
                promptUtf8 = promptUtf8,
                maxOutputChars = 8
            )
        } finally {
            promptUtf8.fill(0)
        }

        assertEquals(1, packet.size)
        assertEquals(LlamaCppNativeBridge.INFER_STALE_SESSION, packet[0])
        assertEquals(
            LlamaCppNativeBridge.CLOSE_FAILED,
            LlamaCppNativeBridge.nativeClose(Long.MAX_VALUE)
        )
    }

    private fun enginePolicy(maxGeneratedTokens: Int = 8) = LlamaCppEnginePolicy(
        contextTokens = 128,
        maxPromptTokens = 32,
        maxGeneratedTokens = maxGeneratedTokens,
        batchTokens = 32,
        microBatchTokens = 16,
        threadCount = 1,
        maxPromptChars = 128,
        maxPromptUtf8Bytes = 256,
        maxOutputChars = 64,
        maxOutputUtf8Bytes = 256,
        useMmap = true
    )

    private fun backend(context: Context) = AndroidAppPrivateProtectedModelStagingBackend(
        context,
        AndroidProtectedModelStagingPolicy(freeSpaceReserveBytes = 0L)
    )

    private fun coordinator(
        backend: AndroidAppPrivateProtectedModelStagingBackend,
        totalBytes: Long
    ) = LargeProtectedModelStagingCoordinator(
        backend = backend,
        budgets = LargeProtectedModelStagingBudgets(
            maxTotalPlaintextBytes = totalBytes,
            maxSegmentPlaintextBytes = minOf(SEGMENT_BYTES.toLong(), totalBytes),
            maxSegmentCount = segmentCount(totalBytes),
            maxActiveAttempts = 1,
            maxOpaqueIdentifierChars = 64
        )
    )

    private fun publishSegmented(
        coordinator: LargeProtectedModelStagingCoordinator,
        input: InputStream,
        totalBytes: Long,
        packageId: String
    ): LargeProtectedModelStagedSourceOwnership {
        val expectedSegments = segmentCount(totalBytes)
        val started = assertIs<LargeProtectedModelStagingStartResult.Started>(
            coordinator.start(
                LargeProtectedModelStagingRequest(
                    model = ProtectedModelReference(
                        packageId = ProtectedModelPackageId(packageId),
                        generation = ProtectedModelGeneration(1)
                    ),
                    profile = LargeProtectedModelPayloadProfile.SEGMENTED_AES_256_GCM_SHA256_V1,
                    expectedPlaintextBytes = totalBytes,
                    expectedSegmentCount = expectedSegments
                )
            )
        )

        var segmentIndex = 0
        var appendedBytes = 0L
        while (segmentIndex < expectedSegments) {
            val remaining = totalBytes - appendedBytes
            val wanted = minOf(SEGMENT_BYTES.toLong(), remaining).toInt()
            val segment = readExactSegment(input, wanted)
            assertEquals(wanted, segment.size)
            assertIs<LargeProtectedModelStagingAppendResult.Appended>(
                started.session.append(segmentIndex, segment)
            )
            segment.fill(0)
            appendedBytes += segment.size.toLong()
            segmentIndex += 1
        }

        assertEquals(totalBytes, appendedBytes)
        assertEquals(-1, input.read())
        return assertIs<LargeProtectedModelStagingPublishResult.Published>(
            started.session.sealAndPublish()
        ).ownership
    }

    private fun readExactSegment(input: InputStream, wanted: Int): ByteArray {
        val buffer = ByteArray(wanted)
        var offset = 0
        while (offset < wanted) {
            val read = input.read(buffer, offset, wanted - offset)
            if (read < 0) break
            offset += read
        }
        return if (offset == buffer.size) buffer else buffer.copyOf(offset)
    }

    private fun segmentCount(totalBytes: Long): Int =
        ((totalBytes + SEGMENT_BYTES - 1L) / SEGMENT_BYTES).toInt()

    private inline fun withCleanRoot(block: (Context, Context) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext.applicationContext
        val testContext = instrumentation.context
        val root = File(targetContext.filesDir, "large-protected-model-staging-v1")
        root.deleteRecursively()
        try {
            block(targetContext, testContext)
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val STORIES_15M_ASSET = "stories15M-q4_0.gguf"
        const val STORIES_15M_BYTES = 19_077_344L
        const val TRUNCATED_GGUF_BYTES = 4 * 1024
        const val SEGMENT_BYTES = 256 * 1024
    }
}
