package pro.liliya.android.protectedmodel.enginesource

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.android.protectedmodel.staging.AndroidAppPrivateProtectedModelStagingBackend
import pro.liliya.android.protectedmodel.staging.AndroidProtectedModelPhysicalEngineLoaderPort
import pro.liliya.android.protectedmodel.staging.AndroidProtectedModelStagingPolicy
import pro.liliya.core.modelengine.ModelEngineBackendId
import pro.liliya.core.modelengine.ModelEngineCloseResult
import pro.liliya.core.modelengine.ModelEngineHandleId
import pro.liliya.core.modelengine.ModelEngineInferenceRequest
import pro.liliya.core.modelengine.ModelEngineInferenceResult
import pro.liliya.core.modelengine.ModelEngineLoadFailure
import pro.liliya.core.modelengine.ModelEngineLoadResult
import pro.liliya.core.modelengine.ModelEngineSessionOwnership
import pro.liliya.core.modelengine.StagedModelEngineLoadCoordinator
import pro.liliya.core.protectedmodel.LargeProtectedModelPayloadProfile
import pro.liliya.core.protectedmodel.LargeProtectedModelStagedSourceOwnership
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingAppendResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingAttemptReference
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingBudgets
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingCoordinator
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingDeleteResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingFailure
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingGeneration
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingPrepareResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingPublishResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingRequest
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingRetireResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingStartResult
import pro.liliya.core.protectedmodel.ProtectedModelGeneration
import pro.liliya.core.protectedmodel.ProtectedModelPackageId
import pro.liliya.core.protectedmodel.ProtectedModelReference

@RunWith(AndroidJUnit4::class)
class AndroidAppPrivateStagedModelEngineLoaderInstrumentedTest {

    @Test
    fun real_sealed_source_handoff_retains_core_lease_until_engine_close() = withCleanRoot { context ->
        val backend = backend(context)
        val staging = coordinator(backend)
        val ownership = publish(staging)
        var physicalFile: File? = null
        var renderedCapability = ""

        val adapter = AndroidAppPrivateStagedModelEngineLoader(
            backend,
            AndroidProtectedModelPhysicalEngineLoaderPort { source, capability ->
                physicalFile = source
                renderedCapability = capability.toString()
                assertTrue(source.isFile)
                assertEquals(5L, source.length())
                assertEquals(ownership.source.model, capability.model)
                assertEquals(ownership.source.stagingGeneration, capability.stagingGeneration)
                ModelEngineLoadResult.Loaded(FakeEngineSession())
            }
        )
        val loader = StagedModelEngineLoadCoordinator(staging, adapter)

        val loaded = assertIs<ModelEngineLoadResult.Loaded>(loader.load(ownership))
        val file = assertNotNull(physicalFile)
        assertFalse(renderedCapability.contains(file.absolutePath))
        assertFalse(renderedCapability.contains(ownership.source.sourceId.value))

        val blocked = assertIs<LargeProtectedModelStagingRetireResult.Rejected>(ownership.retire())
        assertEquals(LargeProtectedModelStagingFailure.RETIRE_IN_USE, blocked.reason)
        assertTrue(file.exists())

        assertIs<ModelEngineCloseResult.Closed>(loaded.ownership.close())
        assertIs<LargeProtectedModelStagingRetireResult.Retired>(ownership.retire())
        assertFalse(file.exists())
    }

    @Test
    fun foreign_backend_instance_rejects_before_physical_engine_delegate() = withCleanRoot { context ->
        val ownerBackend = backend(context)
        val staging = coordinator(ownerBackend)
        val ownership = publish(staging)
        val foreignBackend = backend(context)
        var physicalCalls = 0

        val adapter = AndroidAppPrivateStagedModelEngineLoader(
            foreignBackend,
            AndroidProtectedModelPhysicalEngineLoaderPort { _, _ ->
                physicalCalls += 1
                ModelEngineLoadResult.Loaded(FakeEngineSession())
            }
        )
        val loader = StagedModelEngineLoadCoordinator(staging, adapter)

        val rejected = assertIs<ModelEngineLoadResult.Rejected>(loader.load(ownership))
        assertEquals(ModelEngineLoadFailure.LOAD_REJECTED, rejected.reason)
        assertEquals(0, physicalCalls)

        assertIs<LargeProtectedModelStagingRetireResult.Retired>(ownership.retire())
    }

    @Test
    fun tampered_physical_size_rejects_before_second_engine_handoff() = withCleanRoot { context ->
        val backend = backend(context)
        val staging = coordinator(backend)
        val ownership = publish(staging)
        var physicalCalls = 0
        var physicalFile: File? = null

        val adapter = AndroidAppPrivateStagedModelEngineLoader(
            backend,
            AndroidProtectedModelPhysicalEngineLoaderPort { source, _ ->
                physicalCalls += 1
                physicalFile = source
                ModelEngineLoadResult.Loaded(FakeEngineSession())
            }
        )
        val loader = StagedModelEngineLoadCoordinator(staging, adapter)

        val first = assertIs<ModelEngineLoadResult.Loaded>(loader.load(ownership))
        assertIs<ModelEngineCloseResult.Closed>(first.ownership.close())
        val file = assertNotNull(physicalFile)
        file.appendBytes(byteArrayOf(0x7f))

        val second = assertIs<ModelEngineLoadResult.Rejected>(loader.load(ownership))
        assertEquals(ModelEngineLoadFailure.LOAD_REJECTED, second.reason)
        assertEquals(1, physicalCalls)

        assertIs<LargeProtectedModelStagingRetireResult.Retired>(ownership.retire())
    }

    @Test
    fun missing_physical_file_rejects_without_invoking_engine_delegate() = withCleanRoot { context ->
        val backend = backend(context)
        val staging = coordinator(backend)
        val ownership = publish(staging)
        var physicalFile: File? = null
        var physicalCalls = 0

        val firstAdapter = AndroidAppPrivateStagedModelEngineLoader(
            backend,
            AndroidProtectedModelPhysicalEngineLoaderPort { source, _ ->
                physicalFile = source
                ModelEngineLoadResult.Loaded(FakeEngineSession())
            }
        )
        val first = assertIs<ModelEngineLoadResult.Loaded>(
            StagedModelEngineLoadCoordinator(staging, firstAdapter).load(ownership)
        )
        assertIs<ModelEngineCloseResult.Closed>(first.ownership.close())
        assertTrue(assertNotNull(physicalFile).delete())

        val rejectingAdapter = AndroidAppPrivateStagedModelEngineLoader(
            backend,
            AndroidProtectedModelPhysicalEngineLoaderPort { _, _ ->
                physicalCalls += 1
                ModelEngineLoadResult.Loaded(FakeEngineSession())
            }
        )
        val rejected = assertIs<ModelEngineLoadResult.Rejected>(
            StagedModelEngineLoadCoordinator(staging, rejectingAdapter).load(ownership)
        )
        assertEquals(ModelEngineLoadFailure.LOAD_REJECTED, rejected.reason)
        assertEquals(0, physicalCalls)
    }

    @Test
    fun physical_engine_callback_runs_outside_staging_backend_lock() = withCleanRoot { context ->
        val backend = backend(context)
        val staging = coordinator(backend)
        val ownership = publish(staging)
        var preparedDuringCallback: LargeProtectedModelStagingPrepareResult? = null

        val adapter = AndroidAppPrivateStagedModelEngineLoader(
            backend,
            AndroidProtectedModelPhysicalEngineLoaderPort { _, _ ->
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val probe = executor.submit<LargeProtectedModelStagingPrepareResult> {
                        backend.prepare(
                            attempt(modelGeneration = 2, stagingGeneration = 100),
                            1
                        )
                    }
                    preparedDuringCallback = probe.get(500, TimeUnit.MILLISECONDS)
                } finally {
                    executor.shutdownNow()
                }
                ModelEngineLoadResult.Loaded(FakeEngineSession())
            }
        )

        val loaded = assertIs<ModelEngineLoadResult.Loaded>(
            StagedModelEngineLoadCoordinator(staging, adapter).load(ownership)
        )
        val prepared = assertIs<LargeProtectedModelStagingPrepareResult.Prepared>(preparedDuringCallback)
        assertIs<LargeProtectedModelStagingDeleteResult.Deleted>(
            backend.delete(prepared.handle.artifactId)
        )
        assertIs<ModelEngineCloseResult.Closed>(loaded.ownership.close())
        assertIs<LargeProtectedModelStagingRetireResult.Retired>(ownership.retire())
    }

    @Test
    fun target_package_requests_no_storage_permission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        )
        val requested = info.requestedPermissions?.toSet().orEmpty()
        assertFalse(Manifest.permission.READ_EXTERNAL_STORAGE in requested)
        assertFalse(Manifest.permission.WRITE_EXTERNAL_STORAGE in requested)
        assertFalse(Manifest.permission.MANAGE_EXTERNAL_STORAGE in requested)
    }

    private fun backend(context: Context) = AndroidAppPrivateProtectedModelStagingBackend(
        context,
        AndroidProtectedModelStagingPolicy(freeSpaceReserveBytes = 0)
    )

    private fun coordinator(backend: AndroidAppPrivateProtectedModelStagingBackend) =
        LargeProtectedModelStagingCoordinator(
            backend = backend,
            budgets = LargeProtectedModelStagingBudgets(
                maxTotalPlaintextBytes = 1024,
                maxSegmentPlaintextBytes = 512,
                maxSegmentCount = 8,
                maxActiveAttempts = 1,
                maxOpaqueIdentifierChars = 64
            )
        )

    private fun publish(
        coordinator: LargeProtectedModelStagingCoordinator
    ): LargeProtectedModelStagedSourceOwnership {
        val started = assertIs<LargeProtectedModelStagingStartResult.Started>(
            coordinator.start(
                LargeProtectedModelStagingRequest(
                    model = ProtectedModelReference(
                        ProtectedModelPackageId("android-engine-source-test-model"),
                        ProtectedModelGeneration(1)
                    ),
                    profile = LargeProtectedModelPayloadProfile.SEGMENTED_AES_256_GCM_SHA256_V1,
                    expectedPlaintextBytes = 5,
                    expectedSegmentCount = 1
                )
            )
        )
        assertIs<LargeProtectedModelStagingAppendResult.Appended>(
            started.session.append(0, "alpha".encodeToByteArray())
        )
        return assertIs<LargeProtectedModelStagingPublishResult.Published>(
            started.session.sealAndPublish()
        ).ownership
    }

    private fun attempt(
        modelGeneration: Long,
        stagingGeneration: Long
    ) = LargeProtectedModelStagingAttemptReference(
        generation = LargeProtectedModelStagingGeneration(stagingGeneration),
        model = ProtectedModelReference(
            ProtectedModelPackageId("reentrant-probe-model"),
            ProtectedModelGeneration(modelGeneration)
        )
    )

    private inline fun withCleanRoot(block: (Context) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val root = File(context.filesDir, "large-protected-model-staging-v1")
        root.deleteRecursively()
        try {
            block(context)
        } finally {
            root.deleteRecursively()
        }
    }

    private class FakeEngineSession : ModelEngineSessionOwnership {
        override val backendId = ModelEngineBackendId("fake-physical-engine")
        override val handleId = ModelEngineHandleId("fake-engine-handle")
        private var closed = false

        override fun infer(request: ModelEngineInferenceRequest): ModelEngineInferenceResult =
            ModelEngineInferenceResult.Succeeded("ok")

        override fun close(): ModelEngineCloseResult {
            closed = true
            return ModelEngineCloseResult.Closed
        }
    }
}
