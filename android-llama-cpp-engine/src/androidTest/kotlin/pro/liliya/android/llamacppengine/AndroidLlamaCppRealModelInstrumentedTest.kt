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

            val physicalLoader = AndroidLlamaCppPhysicalEngineLoader(
                LlamaCppEnginePolicy(
                    contextTokens = 128,
                    maxPromptTokens = 32,
                    maxGeneratedTokens = 8,
                    batchTokens = 32,
                    microBatchTokens = 16,
                    threadCount = 1,
                    useMmap = true
                )
            )
            val stagedLoader = AndroidAppPrivateStagedModelEngineLoader(backend, physicalLoader)
            val loadCoordinator = StagedModelEngineLoadCoordinator(staging, stagedLoader)

            val loaded = assertIs<ModelEngineLoadResult.Loaded>(loadCoordinator.load(ownership))
            val inferred = assertIs<ModelEngineInferenceResult.Succeeded>(
                loaded.ownership.infer(
                    ModelEngineInferenceRequest(
                        prompt = "Hello",
                        maxOutputChars = 32
                    )
                )
            )
            assertTrue(inferred.output.length <= 32)

            assertIs<ModelEngineCloseResult.Closed>(loaded.ownership.close())
            assertIs<LargeProtectedModelStagingRetireResult.Retired>(ownership.retire())
        }

    @Test
    fun non_gguf_source_fails_closed_and_releases_engine_use_lease() =
        withCleanRoot { targetContext, _ ->
            val invalidBytes = "not-a-gguf-model".encodeToByteArray()
            val backend = backend(targetContext)
            val staging = coordinator(backend, invalidBytes.size.toLong())
            val ownership = publishSegmented(
                coordinator = staging,
                input = ByteArrayInputStream(invalidBytes),
                totalBytes = invalidBytes.size.toLong(),
                packageId = "slice7-invalid-gguf"
            )

            val physicalLoader = AndroidLlamaCppPhysicalEngineLoader(
                LlamaCppEnginePolicy(
                    contextTokens = 128,
                    maxPromptTokens = 32,
                    maxGeneratedTokens = 8,
                    batchTokens = 32,
                    microBatchTokens = 16,
                    threadCount = 1,
                    useMmap = true
                )
            )
            val stagedLoader = AndroidAppPrivateStagedModelEngineLoader(backend, physicalLoader)
            val loadCoordinator = StagedModelEngineLoadCoordinator(staging, stagedLoader)

            assertIs<ModelEngineLoadResult.Rejected>(loadCoordinator.load(ownership))
            assertIs<LargeProtectedModelStagingRetireResult.Retired>(ownership.retire())
        }

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
            maxSegmentPlaintextBytes = SEGMENT_BYTES.toLong(),
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
            if (read < 0) {
                break
            }
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
        const val SEGMENT_BYTES = 256 * 1024
    }
}
