package pro.liliya.android.protectedmodel.staging

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.core.modelengine.ModelEngineLoadFailure
import pro.liliya.core.modelengine.ModelEngineLoadResult
import pro.liliya.core.modelengine.StagedModelEngineLoadCoordinator
import pro.liliya.core.modelengine.StagedModelEngineLoaderPort
import pro.liliya.core.protectedmodel.LargeProtectedModelEngineSourceCapability
import pro.liliya.core.protectedmodel.LargeProtectedModelPayloadProfile
import pro.liliya.core.protectedmodel.LargeProtectedModelStagedSourceOwnership
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingAppendBackendResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingAppendResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingAttemptReference
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingBudgets
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingCoordinator
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingGeneration
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingPrepareResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingPublishResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingRequest
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingSealResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingStartResult
import pro.liliya.core.protectedmodel.ProtectedModelGeneration
import pro.liliya.core.protectedmodel.ProtectedModelPackageId
import pro.liliya.core.protectedmodel.ProtectedModelReference

@RunWith(AndroidJUnit4::class)
class AndroidProtectedModelEngineSourceValidationInstrumentedTest {

    @Test
    fun matching_id_but_working_record_is_not_engine_eligible() = withCleanRoot { context ->
        val fixture = capabilityFixture(context, token('a'))
        val conflicting = backend(context, token('a'))
        assertIs<LargeProtectedModelStagingPrepareResult.Prepared>(
            conflicting.prepare(
                attempt(
                    model = fixture.capability.model,
                    stagingGeneration = fixture.capability.stagingGeneration.value
                ),
                fixture.capability.plaintextBytes
            )
        )

        assertRejectedBeforeProvider(conflicting, fixture.capability)
    }

    @Test
    fun matching_id_but_wrong_model_is_rejected() = withCleanRoot { context ->
        val fixture = capabilityFixture(context, token('b'))
        val conflicting = backend(context, token('b'))
        sealDirect(
            conflicting,
            attempt(
                model = ProtectedModelReference(
                    ProtectedModelPackageId("different-model"),
                    ProtectedModelGeneration(77)
                ),
                stagingGeneration = fixture.capability.stagingGeneration.value
            ),
            "alpha".encodeToByteArray()
        )

        assertRejectedBeforeProvider(conflicting, fixture.capability)
    }

    @Test
    fun matching_id_but_wrong_staging_generation_is_rejected() = withCleanRoot { context ->
        val fixture = capabilityFixture(context, token('c'))
        val conflicting = backend(context, token('c'))
        sealDirect(
            conflicting,
            attempt(
                model = fixture.capability.model,
                stagingGeneration = fixture.capability.stagingGeneration.value + 1
            ),
            "alpha".encodeToByteArray()
        )

        assertRejectedBeforeProvider(conflicting, fixture.capability)
    }

    @Test
    fun matching_id_but_wrong_plaintext_size_is_rejected() = withCleanRoot { context ->
        val fixture = capabilityFixture(context, token('d'))
        val conflicting = backend(context, token('d'))
        sealDirect(
            conflicting,
            attempt(
                model = fixture.capability.model,
                stagingGeneration = fixture.capability.stagingGeneration.value
            ),
            "alphax".encodeToByteArray()
        )

        assertRejectedBeforeProvider(conflicting, fixture.capability)
    }

    @Test
    fun symlink_replacement_of_sealed_file_is_rejected_without_following_link() = withCleanRoot { context ->
        val fixture = capabilityFixture(context, token('e'))
        val physical = assertNotNull(
            fixture.backend.physicalFileForTesting(fixture.ownership.source.sourceId)
        )
        val target = File(context.filesDir, "engine-source-symlink-target")
        target.writeBytes("alpha".encodeToByteArray())
        assertFalse(physical.delete().not())
        Files.createSymbolicLink(physical.toPath(), target.toPath())

        try {
            assertRejectedBeforeProvider(fixture.backend, fixture.capability)
        } finally {
            Files.deleteIfExists(physical.toPath())
            target.delete()
        }
    }

    private fun assertRejectedBeforeProvider(
        backend: AndroidAppPrivateProtectedModelStagingBackend,
        capability: LargeProtectedModelEngineSourceCapability
    ) {
        var providerCalls = 0
        val result = backend.loadEngineSource(
            capability,
            AndroidProtectedModelPhysicalEngineLoaderPort { _, _ ->
                providerCalls += 1
                ModelEngineLoadResult.Rejected(ModelEngineLoadFailure.UNSUPPORTED_MODEL)
            }
        )
        val rejected = assertIs<ModelEngineLoadResult.Rejected>(result)
        assertEquals(ModelEngineLoadFailure.LOAD_REJECTED, rejected.reason)
        assertEquals(0, providerCalls)
    }

    private fun capabilityFixture(
        context: Context,
        token: String
    ): CapabilityFixture {
        val backend = backend(context, token)
        val coordinator = coordinator(backend)
        val ownership = publish(coordinator)
        var capability: LargeProtectedModelEngineSourceCapability? = null
        val capture = StagedModelEngineLoadCoordinator(
            coordinator,
            StagedModelEngineLoaderPort { source ->
                capability = source
                ModelEngineLoadResult.Rejected(ModelEngineLoadFailure.UNSUPPORTED_MODEL)
            }
        )
        assertIs<ModelEngineLoadResult.Rejected>(capture.load(ownership))
        return CapabilityFixture(
            backend = backend,
            ownership = ownership,
            capability = assertNotNull(capability)
        )
    }

    private fun sealDirect(
        backend: AndroidAppPrivateProtectedModelStagingBackend,
        attempt: LargeProtectedModelStagingAttemptReference,
        bytes: ByteArray
    ) {
        val prepared = assertIs<LargeProtectedModelStagingPrepareResult.Prepared>(
            backend.prepare(attempt, bytes.size.toLong())
        )
        assertIs<LargeProtectedModelStagingAppendBackendResult.Appended>(
            backend.append(prepared.handle, 0, bytes)
        )
        assertIs<LargeProtectedModelStagingSealResult.Sealed>(backend.seal(prepared.handle))
    }

    private fun backend(
        context: Context,
        token: String
    ) = AndroidAppPrivateProtectedModelStagingBackend(
        context,
        AndroidProtectedModelStagingPolicy(0)
    ) { token }

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
                        ProtectedModelPackageId("engine-source-validation-model"),
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
        model: ProtectedModelReference,
        stagingGeneration: Long
    ) = LargeProtectedModelStagingAttemptReference(
        generation = LargeProtectedModelStagingGeneration(stagingGeneration),
        model = model
    )

    private fun token(character: Char): String = character.toString().repeat(32)

    private inline fun withCleanRoot(block: (Context) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val probe = AndroidAppPrivateProtectedModelStagingBackend(
            context,
            AndroidProtectedModelStagingPolicy(0)
        )
        val root = probe.adapterRootForTesting()
        root.deleteRecursively()
        try {
            block(context)
        } finally {
            root.deleteRecursively()
            File(context.filesDir, "engine-source-symlink-target").delete()
        }
    }

    private data class CapabilityFixture(
        val backend: AndroidAppPrivateProtectedModelStagingBackend,
        val ownership: LargeProtectedModelStagedSourceOwnership,
        val capability: LargeProtectedModelEngineSourceCapability
    )
}
