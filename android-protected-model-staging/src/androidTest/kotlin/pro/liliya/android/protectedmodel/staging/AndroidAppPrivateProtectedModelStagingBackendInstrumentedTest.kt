package pro.liliya.android.protectedmodel.staging

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.core.protectedmodel.LargeProtectedModelOpaqueArtifactId
import pro.liliya.core.protectedmodel.LargeProtectedModelPayloadProfile
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingAppendBackendResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingAppendResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingAttemptReference
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingBudgets
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingCleanupStatus
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingCoordinator
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingDeleteResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingDurabilityLevel
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingGeneration
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingPrepareResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingPublishResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingRequest
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingRetireResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingSealResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingStartResult
import pro.liliya.core.protectedmodel.ProtectedModelGeneration
import pro.liliya.core.protectedmodel.ProtectedModelPackageId
import pro.liliya.core.protectedmodel.ProtectedModelReference

@RunWith(AndroidJUnit4::class)
class AndroidAppPrivateProtectedModelStagingBackendInstrumentedTest {

    @Test
    fun real_backend_stages_seals_publishes_and_retires_exact_bytes() = withCleanRoot { context ->
        val backend = AndroidAppPrivateProtectedModelStagingBackend(
            context,
            AndroidProtectedModelStagingPolicy(freeSpaceReserveBytes = 0)
        )
        val coordinator = coordinator(backend)
        val started = assertIs<LargeProtectedModelStagingStartResult.Started>(
            coordinator.start(request(total = 10, count = 2))
        )

        val alpha = "alpha".encodeToByteArray()
        val omega = "omega".encodeToByteArray()
        assertIs<LargeProtectedModelStagingAppendResult.Appended>(started.session.append(0, alpha))
        assertIs<LargeProtectedModelStagingAppendResult.Appended>(started.session.append(1, omega))
        assertContentEquals("alpha".encodeToByteArray(), alpha)
        assertContentEquals("omega".encodeToByteArray(), omega)

        val published = assertIs<LargeProtectedModelStagingPublishResult.Published>(
            started.session.sealAndPublish()
        )
        assertEquals(
            LargeProtectedModelStagingDurabilityLevel.ATOMIC_VISIBILITY_RENAMED,
            published.ownership.source.durabilityLevel
        )
        val finalFile = assertNotNull(
            backend.physicalFileForTesting(published.ownership.source.sourceId)
        )
        assertTrue(finalFile.toPath().toAbsolutePath().normalize().startsWith(
            context.filesDir.toPath().toAbsolutePath().normalize()
        ))
        assertContentEquals("alphaomega".encodeToByteArray(), finalFile.readBytes())

        assertIs<LargeProtectedModelStagingRetireResult.Retired>(published.ownership.retire())
        assertFalse(finalFile.exists())
        assertNull(backend.physicalFileForTesting(published.ownership.source.sourceId))
    }

    @Test
    fun incomplete_attempt_cleanup_deletes_working_artifact() = withCleanRoot { context ->
        val token = token('a')
        val backend = AndroidAppPrivateProtectedModelStagingBackend(
            context,
            AndroidProtectedModelStagingPolicy(0)
        ) { token }
        val coordinator = coordinator(backend)
        val started = assertIs<LargeProtectedModelStagingStartResult.Started>(
            coordinator.start(request(total = 10, count = 2))
        )
        assertIs<LargeProtectedModelStagingAppendResult.Appended>(
            started.session.append(0, "alpha".encodeToByteArray())
        )
        val id = LargeProtectedModelOpaqueArtifactId(token)
        val working = assertNotNull(backend.physicalFileForTesting(id))
        assertTrue(working.exists())

        val rejected = assertIs<LargeProtectedModelStagingPublishResult.Rejected>(
            started.session.sealAndPublish()
        )
        assertEquals(LargeProtectedModelStagingCleanupStatus.DELETED, rejected.cleanup.status)
        assertFalse(working.exists())
        assertNull(backend.physicalFileForTesting(id))
    }

    @Test
    fun atomic_target_collision_fails_closed_without_overwrite() = withCleanRoot { context ->
        val token = token('b')
        val backend = AndroidAppPrivateProtectedModelStagingBackend(
            context,
            AndroidProtectedModelStagingPolicy(0)
        ) { token }
        val prepared = assertIs<LargeProtectedModelStagingPrepareResult.Prepared>(
            backend.prepare(attempt(), 5)
        )
        assertIs<LargeProtectedModelStagingAppendBackendResult.Appended>(
            backend.append(prepared.handle, 0, "alpha".encodeToByteArray())
        )

        val collision = backend.finalFileForTesting(prepared.handle.artifactId)
        collision.writeBytes("sentinel".encodeToByteArray())
        val seal = backend.seal(prepared.handle)
        assertIs<LargeProtectedModelStagingSealResult.Rejected>(seal)
        assertContentEquals("sentinel".encodeToByteArray(), collision.readBytes())
        assertContentEquals(
            "alpha".encodeToByteArray(),
            assertNotNull(backend.physicalFileForTesting(prepared.handle.artifactId)).readBytes()
        )
    }

    @Test
    fun prepare_collision_never_truncates_existing_working_artifact() = withCleanRoot { context ->
        val token = token('c')
        val first = AndroidAppPrivateProtectedModelStagingBackend(
            context,
            AndroidProtectedModelStagingPolicy(0)
        ) { token }
        val firstPrepared = assertIs<LargeProtectedModelStagingPrepareResult.Prepared>(
            first.prepare(attempt(), 5)
        )
        assertIs<LargeProtectedModelStagingAppendBackendResult.Appended>(
            first.append(firstPrepared.handle, 0, "alpha".encodeToByteArray())
        )

        val second = AndroidAppPrivateProtectedModelStagingBackend(
            context,
            AndroidProtectedModelStagingPolicy(0)
        ) { token }
        assertIs<LargeProtectedModelStagingPrepareResult.Rejected>(
            second.prepare(attempt(generation = 2), 5)
        )
        assertContentEquals(
            "alpha".encodeToByteArray(),
            assertNotNull(first.physicalFileForTesting(firstPrepared.handle.artifactId)).readBytes()
        )
    }

    @Test
    fun new_backend_instance_does_not_adopt_or_delete_leftover_artifact() = withCleanRoot { context ->
        val token = token('d')
        val first = AndroidAppPrivateProtectedModelStagingBackend(
            context,
            AndroidProtectedModelStagingPolicy(0)
        ) { token }
        val prepared = assertIs<LargeProtectedModelStagingPrepareResult.Prepared>(
            first.prepare(attempt(), 5)
        )
        assertIs<LargeProtectedModelStagingAppendBackendResult.Appended>(
            first.append(prepared.handle, 0, "alpha".encodeToByteArray())
        )
        val leftover = assertNotNull(first.physicalFileForTesting(prepared.handle.artifactId))

        val restarted = AndroidAppPrivateProtectedModelStagingBackend(
            context,
            AndroidProtectedModelStagingPolicy(0)
        )
        assertNull(restarted.physicalFileForTesting(prepared.handle.artifactId))
        assertIs<LargeProtectedModelStagingDeleteResult.Rejected>(
            restarted.delete(prepared.handle.artifactId)
        )
        assertTrue(leftover.exists())
    }

    @Test
    fun malformed_token_and_overflowing_space_requirement_reject_before_allocation() = withCleanRoot { context ->
        val malformed = AndroidAppPrivateProtectedModelStagingBackend(
            context,
            AndroidProtectedModelStagingPolicy(0)
        ) { "../model.gguf" }
        val malformedResult = malformed.prepare(attempt(), 5)
        assertIs<LargeProtectedModelStagingPrepareResult.Rejected>(malformedResult)
        assertFalse(malformedResult.toString().contains(context.filesDir.absolutePath))
        assertIs<LargeProtectedModelStagingDeleteResult.Rejected>(
            malformed.delete(LargeProtectedModelOpaqueArtifactId("../foreign"))
        )

        val impossible = AndroidAppPrivateProtectedModelStagingBackend(
            context,
            AndroidProtectedModelStagingPolicy(Long.MAX_VALUE)
        )
        assertIs<LargeProtectedModelStagingPrepareResult.Rejected>(
            impossible.prepare(attempt(), 1)
        )
    }

    @Test
    fun duplicate_seal_and_append_after_seal_fail_closed() = withCleanRoot { context ->
        val backend = AndroidAppPrivateProtectedModelStagingBackend(
            context,
            AndroidProtectedModelStagingPolicy(0)
        ) { token('e') }
        val prepared = assertIs<LargeProtectedModelStagingPrepareResult.Prepared>(
            backend.prepare(attempt(), 5)
        )
        assertIs<LargeProtectedModelStagingAppendBackendResult.Appended>(
            backend.append(prepared.handle, 0, "alpha".encodeToByteArray())
        )
        assertIs<LargeProtectedModelStagingSealResult.Sealed>(backend.seal(prepared.handle))
        assertIs<LargeProtectedModelStagingSealResult.Rejected>(backend.seal(prepared.handle))
        assertIs<LargeProtectedModelStagingAppendBackendResult.Rejected>(
            backend.append(prepared.handle, 1, "x".encodeToByteArray())
        )
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

    private fun request(total: Long, count: Int) = LargeProtectedModelStagingRequest(
        model = ProtectedModelReference(
            ProtectedModelPackageId("android-staging-test-model"),
            ProtectedModelGeneration(1)
        ),
        profile = LargeProtectedModelPayloadProfile.SEGMENTED_AES_256_GCM_SHA256_V1,
        expectedPlaintextBytes = total,
        expectedSegmentCount = count
    )

    private fun attempt(generation: Long = 1) = LargeProtectedModelStagingAttemptReference(
        generation = LargeProtectedModelStagingGeneration(generation),
        model = ProtectedModelReference(
            ProtectedModelPackageId("android-staging-backend-test"),
            ProtectedModelGeneration(generation)
        )
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
        }
    }
}
